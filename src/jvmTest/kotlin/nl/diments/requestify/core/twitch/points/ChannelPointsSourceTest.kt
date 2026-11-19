package nl.diments.requestify.core.twitch.points

import nl.diments.requestify.core.settings.setForTest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.settings.ChannelPointsSettings
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.Redemption
import nl.diments.requestify.core.twitch.RedemptionStatus.CANCELED
import nl.diments.requestify.core.twitch.RedemptionStatus.FULFILLED
import nl.diments.requestify.core.twitch.chat.TwitchTestRig
import nl.diments.requestify.core.twitch.chat.awaitUntil
import nl.diments.requestify.core.twitch.chat.settle
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class ChannelPointsSourceTest {
    private lateinit var dir: Path
    private lateinit var savedLocale: Locale

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("requestify-points")
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(savedLocale)
        dir.deleteRecursively()
    }

    private val idA = "A".repeat(22)
    private val idB = "B".repeat(22)
    private val idC = "C".repeat(22)
    private val idD = "D".repeat(22)
    private val idY = "Y".repeat(22)
    private val idZ = "Z".repeat(22)
    private fun link(id: String) = "https://open.spotify.com/track/$id"

    /** Like [TwitchTestRig.track]: every track is 200 s long. */
    private fun track(id: String, name: String) =
        Track(id, "spotify:track:$id", name, listOf("Artist $name"), 200_000, false, null, listOf("art-$id"))

    private fun outboxFile() =
        JsonFileStore(dir.resolve("redemptions.json"), ListSerializer(OutboxEntry.serializer())) { emptyList() }

    private class Harness(
        val rig: TwitchTestRig,
        val helix: FakeRedemptionsApi,
        val outbox: RedemptionOutbox,
        val source: ChannelPointsSource,
        val freshSessions: MutableSharedFlow<Unit>,
    ) {
        val sends get() = rig.twitch.sends
        val outboxed: List<OutboxEntry> get() = outbox.entries.value

        fun redeem(id: String, input: String, viewer: String = "u1", reward: String = REWARD) {
            rig.events.tryEmit(EventSubEvent.Redemption(id, reward, viewer, "login_$viewer", "Name $viewer", input))
        }
    }

    private fun waiting(id: String, input: String, viewer: String) =
        Redemption(id, REWARD, viewer, "login_$viewer", "Name $viewer", input, redeemedAtEpochMs = 1_000)

    /** The outbox is not started: what the source asks of it stays in [Harness.outboxed]. */
    private fun TestScope.harness(
        managed: Boolean = true,
        start: Boolean = true,
        waiting: List<Redemption> = emptyList(),
    ): Harness {
        val rig = TwitchTestRig(this, dir)
        rig.catalog.tracks[idA] = track(idA, "Alpha")
        rig.catalog.tracks[idB] = track(idB, "Bravo")
        rig.catalog.tracks[idC] = track(idC, "Charlie")
        rig.settings.setForTest {
            it.copy(twitch = it.twitch.copy(channelPoints = ChannelPointsSettings(enabled = true, rewardId = REWARD, managed = managed)))
        }
        val helix = FakeRedemptionsApi { testScheduler.currentTime }
        helix.unfulfilled += waiting
        val outbox = RedemptionOutbox(outboxFile(), helix.api, backgroundScope, context = inCallerSaveContext())
        val freshSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val source = ChannelPointsSource(
            events = rig.events,
            freshSessions = freshSessions,
            intake = rig.intake,
            controller = rig.controller,
            api = helix.api,
            outbox = outbox,
            settings = rig.settings,
            replies = rig.replies,
            sender = rig.sender,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        rig.sender.start()
        if (start) {
            source.startSession() // starts the lifecycle part too
            runCurrent()
        }
        return Harness(rig, helix, outbox, source, freshSessions)
    }

    /** A Channel Points request of Requestify's own reward, as a request saved by an earlier run. */
    private fun cp(id: String, trackId: String, viewer: String) = Request(
        id, track(trackId, "Song $id"), Requester("Name $viewer", "login_$viewer", viewer),
        RequestSource.CHANNEL_POINTS, null, 0, sourceRef = "cp:$REWARD:$id",
    )

    private fun saveQueue(playback: PersistedPlayback) =
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })
            .save(playback)

    /** Hands the head over near the end of Zulu, then lets Spotify start it (Alpha). */
    private fun TestScope.playHead(h: Harness, id: String) {
        h.rig.player.plays(track(idZ, "Zulu"), progressMs = 190_000)
        h.rig.controller.start()
        awaitUntil("handed over") { h.rig.controller.handedOver.value?.id == id }
        h.rig.player.plays(track(idA, "Alpha"), progressMs = 500)
        advanceTimeBy(1_000)
        awaitUntil("playing") { h.rig.controller.snapshot().playing?.id == id }
    }

    @Test
    fun redemptionQueuedAndUnfulfilledUntilItPlays() = runTest {
        val h = harness()
        h.redeem("red1", link(idA))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val queued = h.rig.queued.single()
        assertEquals("red1", queued.id)
        assertEquals("Alpha", queued.track.name)
        assertEquals(RequestSource.CHANNEL_POINTS, queued.source)
        assertNull(queued.payment)
        assertEquals("cp:$REWARD:red1", queued.sourceRef)
        assertEquals(Requester("Name u1", "login_u1", "u1"), queued.requester)
        val reply = h.sends.single()
        assertNull(reply.replyTo)
        assertTrue(reply.message.startsWith("@Name u1 "), reply.message)
        assertTrue("Alpha" in reply.message, reply.message)
        assertTrue(h.outboxed.isEmpty(), "unfulfilled while it waits")

        // Handed over to Spotify near the end of the song playing now: still unfulfilled.
        h.rig.player.plays(track(idZ, "Zulu"), progressMs = 190_000)
        h.rig.controller.start()
        awaitUntil("handed over") { h.rig.controller.handedOver.value?.id == "red1" }
        settle()
        assertTrue(h.outboxed.isEmpty(), "a hand-over is not a start")

        // Spotify starts it.
        h.rig.player.plays(track(idA, "Alpha"), progressMs = 500)
        advanceTimeBy(1_000)
        awaitUntil("fulfilled") { h.outboxed.isNotEmpty() }
        assertEquals(listOf(OutboxEntry(REWARD, "red1", FULFILLED)), h.outboxed)
    }

    @Test
    fun rejectedRedemptionIsRefundedAndReplyMentionsRefund() = runTest {
        val h = harness()
        h.rig.open.value = false
        h.redeem("red1", link(idA))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertTrue(h.rig.queued.isEmpty())
        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed)
        val entry = h.rig.rejectedEntries.single()
        assertEquals(RejectReason.Closed, entry.reason)
        assertTrue(entry.refunded)
        val reply = h.sends.single()
        assertNull(reply.replyTo)
        assertTrue(reply.message.startsWith("@Name u1 "), reply.message)
        assertTrue("closed" in reply.message, reply.message)
        assertTrue("refunded" in reply.message, reply.message)
    }

    @Test
    fun redemptionWithShortLinkIsFollowedToItsTrack() = runTest {
        val h = harness()
        h.rig.shortLinks.leadsTo("https://spotify.link/abc", "${link(idB)}?si=x")
        h.redeem("red1", "spotify.link/abc")

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertEquals("Bravo", h.rig.queued.single().track.name)
        assertTrue(h.rig.catalog.calls.none { it.startsWith("search:") }, h.rig.catalog.calls.toString())
        assertTrue(h.outboxed.isEmpty())
    }

    @Test
    fun redemptionWithUnresolvableShortLinkIsRefundedAndListed() = runTest {
        val h = harness()
        h.redeem("red1", "https://spotify.link/nowhere")

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.rig.catalog.calls.isEmpty())
        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed)
        assertEquals(RejectReason.ShortLinkUnresolved, h.rig.rejectedEntries.single().reason)
        assertEquals("@Name u1 Use the open.spotify.com link. Your points were refunded.", h.sends.single().message)
    }

    @Test
    fun catchUpMarksARewardTwitchNoLongerKnowsAsRemoved() = runTest {
        val h = harness(start = false)
        h.helix.listStatus = 404 // deleted in the Twitch dashboard while Requestify was off
        h.source.startSession()
        awaitUntil("the catch-up asked") { h.helix.lists.isNotEmpty() }
        settle()
        assertTrue(h.rig.settings.settings.value.twitch.channelPoints.rewardRemoved)
        assertEquals(REWARD, h.rig.settings.settings.value.twitch.channelPoints.rewardId, "not recreated by itself")
    }

    @Test
    fun blockedViewerIsRefundedWithoutReply() = runTest {
        val h = harness()
        h.rig.blacklist.addViewer("u1", "login_u1", null, 0)
        h.redeem("red1", link(idA))
        h.redeem("probe", link(idB), viewer = "u2")

        awaitUntil("probe reply") { h.sends.isNotEmpty() }
        settle()
        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed)
        assertEquals(1, h.sends.size)
        assertTrue(h.sends.single().message.startsWith("@Name u2 "), h.sends.single().message)
    }

    @Test
    fun removedBeforePlayingIsRefunded() = runTest {
        val h = harness()
        h.redeem("red1", link(idA), viewer = "u1")
        h.redeem("red2", link(idB), viewer = "u2")
        h.redeem("red3", link(idC), viewer = "u3")
        awaitUntil("three replies") { h.sends.size == 3 }
        val chat = Request("chat1", track(idD, "Delta"), Requester("Name u4", "login_u4", "u4"), RequestSource.CHAT, null, 0)
        h.rig.controller.enqueue(chat)
        awaitUntil("chat request queued") { h.rig.queued.size == 4 }

        h.rig.controller.remove("red1", RemoveReason.Streamer) // the streamer's X
        h.rig.controller.remove("red2", RemoveReason.WrongSong) // the viewer's !wrongsong
        h.rig.controller.removeMatching(RemoveReason.StreamEnded) { it } // the end-of-stream clear

        awaitUntil("three refunds") { h.outboxed.size == 3 }
        settle()
        assertEquals(
            listOf(
                OutboxEntry(REWARD, "red1", CANCELED),
                OutboxEntry(REWARD, "red2", CANCELED),
                OutboxEntry(REWARD, "red3", CANCELED),
            ),
            h.outboxed,
            "the chat request has no redemption",
        )
    }

    @Test
    fun handedOverThatReturnsToHeadIsStillRefundable() = runTest {
        val h = harness()
        h.redeem("red1", link(idA))
        awaitUntil("reply") { h.sends.isNotEmpty() }
        h.rig.player.plays(track(idZ, "Zulu"), progressMs = 190_000)
        h.rig.controller.start()
        awaitUntil("handed over") { h.rig.controller.handedOver.value?.id == "red1" }

        // The streamer cleared Spotify's queue: another song plays, and Spotify neither has it queued nor played it.
        h.rig.player.plays(track(idY, "Yankee"), progressMs = 0)
        h.rig.player.userQueueResult = SpotifyResult.Ok(emptyList())
        h.rig.player.recentSinceResult = SpotifyResult.Ok(emptyList())
        advanceTimeBy(1_000)
        awaitUntil("back at the head") { h.rig.queued.singleOrNull()?.id == "red1" }
        settle()
        assertTrue(h.outboxed.isEmpty(), "never fulfilled")

        h.rig.controller.remove("red1")
        awaitUntil("refunded") { h.outboxed.isNotEmpty() }
        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed)
    }

    @Test
    fun catchUpSettlesPlayedQueuedAndUnknown() = runTest {
        // What the app knew when it was closed.
        saveQueue(
            PersistedPlayback(
                queue = RequestQueue(listOf(cp("redQ", "q".repeat(22), "v1"))),
                handedOver = cp("redH", "h".repeat(22), "v2"),
                playing = cp("redP", "p".repeat(22), "v3"),
                history = listOf(PlayedEntry(cp("redD", "d".repeat(22), "v4"), playedAtEpochMs = 1)),
            ),
        )
        outboxFile().write(listOf(OutboxEntry(REWARD, "redO", CANCELED)))
        val h = harness(
            start = false,
            waiting = listOf(
                waiting("redD", "played", "v4"),
                waiting("redQ", "queued", "v1"),
                waiting("redH", "handed over", "v2"),
                waiting("redP", "playing", "v3"),
                waiting("redO", "refund on its way", "v5"),
                waiting("redN", link(idA), "v6"),
            ),
        )

        h.source.startSession() // before the controller has read its saved state
        awaitUntil("the unknown one answered") { h.sends.isNotEmpty() }
        settle()
        assertEquals(listOf(REWARD), h.helix.lists)
        assertEquals(
            listOf(
                OutboxEntry(REWARD, "redO", CANCELED),
                OutboxEntry(REWARD, "redD", FULFILLED),
                OutboxEntry(REWARD, "redP", FULFILLED),
            ),
            h.outboxed,
        )
        assertEquals(listOf("redQ", "redN"), h.rig.queued.map { it.id })
        assertEquals("cp:$REWARD:redN", h.rig.queued.last().sourceRef)
        assertTrue(h.sends.single().message.startsWith("@Name v6 "), h.sends.single().message)

        // A fresh EventSub session: whatever came in meanwhile is caught up too, the rest only once.
        h.helix.unfulfilled += waiting("redM", link(idB), "v7")
        h.freshSessions.emit(Unit)
        awaitUntil("the new one answered") { h.sends.size == 2 }
        settle()
        assertEquals(2, h.helix.lists.size)
        assertEquals(listOf("redQ", "redN", "redM"), h.rig.queued.map { it.id })
        assertTrue(h.sends.last().message.startsWith("@Name v7 "), h.sends.last().message)
        assertEquals(3, h.outboxed.size)
    }

    @Test
    fun catchUpRefundsUnknownWhileClosed() = runTest {
        val h = harness(start = false, waiting = listOf(waiting("redN", link(idA), "v1")))
        h.rig.open.value = false

        h.source.startSession()
        awaitUntil("answered") { h.sends.isNotEmpty() }
        assertEquals(listOf(OutboxEntry(REWARD, "redN", CANCELED)), h.outboxed)
        assertTrue(h.rig.queued.isEmpty())
        assertTrue("refunded" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun catchUpRefundsARedemptionThatLeftTheQueueWhileStopped() = runTest {
        val h = harness()
        h.redeem("red1", link(idA))
        awaitUntil("reply") { h.sends.isNotEmpty() }

        h.source.stopSession()
        h.source.stopLifecycle() // e.g. a wiring that missed it: nothing hears the removal
        h.rig.controller.remove("red1")
        awaitUntil("removed") { h.rig.queued.isEmpty() }
        settle()
        assertTrue(h.outboxed.isEmpty(), "nobody listened")

        h.helix.unfulfilled += waiting("red1", link(idA), "u1")
        h.source.startSession()
        awaitUntil("refunded") { h.outboxed.isNotEmpty() }
        settle()
        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed)
        assertTrue(h.rig.queued.isEmpty(), "not queued again")
        assertEquals(1, h.sends.size)
    }

    @Test
    fun redemptionArrivingTwiceIsHandledOnce() = runTest {
        val h = harness()
        h.rig.open.value = false
        h.outbox.start()
        h.helix.unfulfilled += waiting("red1", link(idA), "u1") // Twitch still lists it until the refund is in
        h.redeem("red1", link(idA))
        h.redeem("red1", link(idA))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        awaitUntil("refund sent") { h.helix.patches.isNotEmpty() && h.outboxed.isEmpty() }
        h.freshSessions.emit(Unit)
        awaitUntil("caught up") { h.helix.lists.size == 2 }
        settle()
        assertEquals(1, h.sends.size)
        assertEquals(1, h.rig.rejectedEntries.size)
        assertTrue(h.rig.catalog.calls.size == 1, "resolved once: ${h.rig.catalog.calls}")
        assertTrue(h.helix.patches.all { it.redemptionId == "red1" && it.status == "CANCELED" }, "${h.helix.patches}")
    }

    @Test
    fun linkedForeignRewardNeverTouchesOutboxAndNeverClaimsRefund() = runTest {
        val h = harness(managed = false)
        h.outbox.start()
        h.rig.open.value = false
        h.redeem("red1", link(idA))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val reply = h.sends.single().message
        assertTrue(reply.startsWith("@Name u1 ") && "closed" in reply, reply)
        assertFalse("refunded" in reply, reply)
        assertFalse(h.rig.rejectedEntries.single().refunded)

        h.rig.open.value = true
        h.redeem("red2", link(idB), viewer = "u2")
        awaitUntil("second reply") { h.sends.size == 2 }
        assertEquals("cpx:$REWARD:red2", h.rig.queued.single().sourceRef)
        h.rig.controller.remove("red2")
        awaitUntil("removed") { h.rig.queued.isEmpty() }

        h.freshSessions.emit(Unit)
        settle()
        assertTrue(h.outboxed.isEmpty())
        assertTrue(h.helix.patches.isEmpty(), "never paused, fulfilled or refunded")
        assertTrue(h.helix.lists.isEmpty(), "no catch-up: Twitch would not list them")
    }

    @Test
    fun otherRewardsAreIgnored() = runTest {
        val h = harness()
        h.redeem("red1", link(idA), reward = "another-reward")
        settle()
        h.rig.settings.setForTest { it.copy(twitch = it.twitch.copy(channelPoints = it.twitch.channelPoints.copy(enabled = false))) }
        h.redeem("red2", link(idA)) // its own reward, but Channel Points are switched off
        settle()

        assertTrue(h.sends.isEmpty())
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.rig.rejectedEntries.isEmpty())
        assertTrue(h.rig.catalog.calls.isEmpty())
        assertTrue(h.outboxed.isEmpty())
    }

    @Test
    fun refundIsFinalWhenTheRequestIsAddedAnywayAndPlays() = runTest {
        val h = harness()
        h.rig.open.value = false
        h.redeem("red1", link(idA))
        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertTrue("refunded" in h.sends.single().message, h.sends.single().message)
        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed) // not sent yet (e.g. a dead login)

        h.rig.open.value = true
        h.rig.intake.addAnyway("red1") // the streamer lets it in anyway: same cp: reference
        awaitUntil("queued again") { h.rig.queued.singleOrNull()?.id == "red1" }
        playHead(h, "red1")
        settle()

        assertEquals(listOf(OutboxEntry(REWARD, "red1", CANCELED)), h.outboxed, "the viewer was told: refunded")
    }

    @Test
    fun refundIsFinalAfterItWasSent() = runTest {
        val h = harness()
        h.outbox.start()
        h.rig.open.value = false
        h.redeem("red1", link(idA))
        awaitUntil("refund sent") { h.helix.patches.isNotEmpty() && h.outboxed.isEmpty() }

        h.rig.open.value = true
        h.rig.intake.addAnyway("red1")
        awaitUntil("queued again") { h.rig.queued.singleOrNull()?.id == "red1" }
        playHead(h, "red1")
        settle()

        assertEquals(listOf("red1:CANCELED"), h.helix.patches.map { "${it.redemptionId}:${it.status}" })
        assertTrue(h.outboxed.isEmpty())
    }

    @Test
    fun lifecycleRunsWhileTheSessionIsStopped() = runTest {
        saveQueue(PersistedPlayback(queue = RequestQueue(listOf(cp("redQ", "q".repeat(22), "v1")))))
        val h = harness(start = false)
        h.source.startLifecycle() // at app start: no Twitch session (yet)
        h.rig.controller.remove("redQ")
        awaitUntil("refunded without a session") { h.outboxed.isNotEmpty() }
        assertEquals(listOf(OutboxEntry(REWARD, "redQ", CANCELED)), h.outboxed)
        assertTrue(h.helix.lists.isEmpty(), "no catch-up without a session")

        h.source.startSession()
        h.redeem("red1", link(idA))
        awaitUntil("reply") { h.sends.isNotEmpty() }
        h.source.stopSession() // logged out
        h.redeem("red2", link(idB), viewer = "u2")
        h.rig.controller.remove("red1")
        awaitUntil("refunded after the session ended") { h.outboxed.size == 2 }
        settle()
        assertEquals(OutboxEntry(REWARD, "red1", CANCELED), h.outboxed.last())
        assertEquals(1, h.sends.size, "no redemptions without a session")
        assertTrue(h.rig.queued.isEmpty())
    }

    @Test
    fun justRefundedRedemptionIsNeverRunThroughTheRulesAgain() = runTest {
        saveQueue(PersistedPlayback(queue = RequestQueue(listOf(cp("redQ", idA, "v1")))))
        val h = harness(start = false)
        h.source.startLifecycle()
        h.outbox.start()
        h.rig.controller.remove("redQ") // queued in an earlier run, removed before any catch-up saw it
        awaitUntil("refund sent") { h.helix.patches.isNotEmpty() && h.outboxed.isEmpty() }

        h.helix.unfulfilled += waiting("redQ", link(idA), "v1") // read just before the refund landed
        h.source.startSession()
        awaitUntil("caught up") { h.helix.lists.isNotEmpty() }
        settle()

        assertTrue(h.rig.queued.isEmpty(), "not queued again")
        assertTrue(h.sends.isEmpty())
        assertTrue(h.rig.catalog.calls.isEmpty())
        assertTrue(h.helix.patches.all { it.status == "CANCELED" }, "${h.helix.patches}")
    }

    @Test
    fun catchUpNeverRequeuesWhatTheOutboxSent() = runTest {
        outboxFile().write(listOf(OutboxEntry(REWARD, "redX", CANCELED))) // left by an earlier run
        val h = harness(start = false)
        h.outbox.start()
        awaitUntil("refund sent") { h.helix.patches.isNotEmpty() && h.outboxed.isEmpty() }

        h.helix.unfulfilled += waiting("redX", link(idA), "v1") // read just before the refund landed
        h.source.startSession()
        awaitUntil("caught up") { h.helix.lists.isNotEmpty() }
        settle()

        assertTrue(h.rig.queued.isEmpty(), "not queued again")
        assertTrue(h.sends.isEmpty())
        assertTrue(h.rig.catalog.calls.isEmpty())
    }

    private companion object {
        const val REWARD = "rw1"
    }
}
