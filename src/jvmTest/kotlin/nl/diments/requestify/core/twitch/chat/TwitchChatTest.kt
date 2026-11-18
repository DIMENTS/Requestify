package nl.diments.requestify.core.twitch.chat

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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.intake.FakeShortLinks
import nl.diments.requestify.core.intake.FakeSpotifyCatalog
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.PendingResolveStore
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.intake.RejectedStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class TwitchChatTest {
    private lateinit var dir: Path
    private lateinit var savedLocale: Locale

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("requestify-chat")
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
    private fun link(id: String) = "https://open.spotify.com/track/$id"

    private fun track(id: String, name: String) =
        Track(id, "spotify:track:$id", name, listOf("Artist $name"), 200_000, false, null, listOf("art-$id"))

    private class Harness(
        val events: MutableSharedFlow<EventSubEvent>,
        val twitch: RecordingTwitchApi,
        val catalog: FakeSpotifyCatalog,
        val settings: SettingsStore,
        val open: MutableStateFlow<Boolean>,
        val controller: PlaybackController,
        val player: FakeSpotifyPlayer,
        val intake: RequestIntake,
        val rejected: RejectedStore,
        val sender: ChatReplySender,
        val chat: TwitchChat,
        val shortLinks: FakeShortLinks,
    ) {
        fun message(
            id: String,
            text: String,
            viewer: String = "u1",
            badges: Set<String> = emptySet(),
            source: String? = null,
        ) {
            events.tryEmit(EventSubEvent.ChatMessage(id, viewer, "login_$viewer", "Name $viewer", text, badges, source))
        }

        val queued get() = controller.queue.value.items
        val rejectedEntries: List<RejectedEntry> get() = rejected.entries.value
    }

    private fun TestScope.harness(): Harness {
        val events = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 64)
        val twitch = RecordingTwitchApi()
        val catalog = FakeSpotifyCatalog().apply {
            tracks[idA] = track(idA, "Alpha")
            tracks[idB] = track(idB, "Bravo")
            tracks[idC] = track(idC, "Charlie")
        }
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
        val open = MutableStateFlow(true)
        val player = FakeSpotifyPlayer { testScheduler.currentTime }
        val controller = PlaybackController(
            player = player,
            store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
            requestsOpen = open,
            paidPriority = { true },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val blacklist = BlacklistStore(JsonFileStore(dir.resolve("blacklist.json"), Blacklist.serializer()) { Blacklist() })
        val rejected = RejectedStore(
            JsonFileStore(dir.resolve("rejected.json"), ListSerializer(RejectedEntry.serializer())) { emptyList() },
        )
        val pending = PendingResolveStore(
            JsonFileStore(dir.resolve("pending.json"), ListSerializer(IncomingRequest.serializer())) { emptyList() },
        )
        val shortLinks = FakeShortLinks()
        val intake = RequestIntake(
            controller, catalog, settings, blacklist, rejected, pending, open,
            CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])),
            clock = { testScheduler.currentTime },
            shortLinks = shortLinks,
        )
        val sender = ChatReplySender(twitch.api, backgroundScope)
        val chat = TwitchChat(
            events = events,
            intake = intake,
            controller = controller,
            api = twitch.api,
            settings = settings,
            replies = ChatReplies(),
            sender = sender,
            broadcasterId = { RecordingTwitchApi.BROADCASTER_ID },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        sender.start()
        chat.start()
        runCurrent()
        return Harness(events, twitch, catalog, settings, open, controller, player, intake, rejected, sender, chat, shortLinks)
    }

    private fun Harness.setPermission(permission: ChatPermission) =
        settings.update { it.copy(twitch = it.twitch.copy(chatPermission = permission)) }

    @Test
    fun srAddsAndRepliesWithPositionAsReply() = runTest {
        val h = harness()
        h.message("msg1", "!sr ${link(idA)}", viewer = "u1")

        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        val added = h.queued.single()
        assertEquals("Alpha", added.track.name)
        assertEquals(RequestSource.CHAT, added.source)
        assertEquals("msg1", added.id)
        assertEquals("u1", added.requester.twitchUserId)
        assertEquals("login_u1", added.requester.login)
        assertEquals("Name u1", added.requester.displayName)
        val reply = h.twitch.sends.single()
        assertEquals("msg1", reply.replyTo)
        assertTrue("Alpha" in reply.message, reply.message)
        assertTrue("#1" in reply.message, reply.message)
        assertTrue(reply.message.length <= 500)
    }

    @Test
    fun srWithoutInputRepliesAUsageHint() = runTest {
        val h = harness()
        h.message("msg1", "!sr")

        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        assertTrue("!sr" in h.twitch.sends.single().message)
        assertEquals("msg1", h.twitch.sends.single().replyTo)
        assertTrue(h.queued.isEmpty())
        assertTrue(h.rejectedEntries.isEmpty())
        assertTrue(h.catalog.calls.isEmpty())
    }

    @Test
    fun srWhileClosedRepliesClosed() = runTest {
        val h = harness()
        h.open.value = false
        h.message("msg1", "!sr ${link(idA)}")

        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        assertEquals("msg1", h.twitch.sends.single().replyTo)
        assertTrue("closed" in h.twitch.sends.single().message.lowercase(), h.twitch.sends.single().message)
        assertTrue(h.queued.isEmpty())
        assertEquals(1, h.rejectedEntries.size)
    }

    @Test
    fun srWithShortLinkIsFollowedToItsTrack() = runTest {
        val h = harness()
        h.shortLinks.leadsTo("https://spotify.link/abc", "${link(idB)}?si=x")
        h.message("msg1", "!sr https://spotify.link/abc")

        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        assertEquals("Bravo", h.queued.single().track.name)
        assertTrue(h.catalog.calls.none { it.startsWith("search:") }, h.catalog.calls.toString())
        assertTrue("Bravo" in h.twitch.sends.single().message, h.twitch.sends.single().message)
    }

    @Test
    fun srWithUnresolvableShortLinkSaysToUseTheFullLink() = runTest {
        val h = harness()
        h.message("msg1", "!sr https://spotify.link/nowhere")

        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        assertTrue(h.queued.isEmpty())
        assertTrue(h.catalog.calls.isEmpty())
        assertEquals(RejectReason.ShortLinkUnresolved, h.rejectedEntries.single().reason)
        assertEquals("Use the open.spotify.com link.", h.twitch.sends.single().message)
        assertEquals("msg1", h.twitch.sends.single().replyTo)
    }

    @Test
    fun blockedViewerGetsNoReply() = runTest {
        val h = harness()
        h.intake.blockViewer("u1", "login_u1", null)
        h.message("msg1", "!sr ${link(idA)}", viewer = "u1")
        h.message("probe", "!song", viewer = "u2")

        awaitUntil("probe reply") { h.twitch.sends.isNotEmpty() }
        settle()
        assertEquals(listOf("probe"), h.twitch.sends.map { it.replyTo })
        assertTrue(h.queued.isEmpty())
        assertTrue(h.rejectedEntries.isEmpty())
    }

    @Test
    fun blockedViewerGetsNoReplyForAnyRejection() = runTest {
        val h = harness()
        h.intake.blockViewer("u1", "login_u1", null)
        h.message("msg1", "!sr https://open.spotify.com/album/$idA", viewer = "u1")
        h.message("msg2", "!sr ${link(idC.replace('C', 'Z'))}", viewer = "u1") // no such track
        h.message("probe", "!song", viewer = "u2")

        awaitUntil("probe reply") { h.twitch.sends.isNotEmpty() }
        settle()
        assertEquals(listOf("probe"), h.twitch.sends.map { it.replyTo })
        assertTrue(h.rejectedEntries.isEmpty())
    }

    @Test
    fun requestsAreHandledInTheOrderTheyWereSent() = runTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.catalog.trackGates[idA] = gate // the first viewer's lookup is slow
        h.message("m1", "!sr ${link(idA)}", viewer = "u1")
        h.message("m2", "!sr ${link(idB)}", viewer = "u2")

        awaitUntil("first lookup started") { "track:$idA" in h.catalog.calls }
        settle()
        assertEquals(listOf("track:$idA"), h.catalog.calls) // the second waits its turn
        assertTrue(h.queued.isEmpty())

        gate.complete(Unit)
        awaitUntil("both replies") { h.twitch.sends.size == 2 }
        assertEquals(listOf("Alpha", "Bravo"), h.queued.map { it.track.name })
        assertEquals(listOf("m1", "m2"), h.twitch.sends.map { it.replyTo })
        assertTrue("#1" in h.twitch.sends[0].message, h.twitch.sends[0].message)
        assertTrue("#2" in h.twitch.sends[1].message, h.twitch.sends[1].message)
    }

    @Test
    fun followersLevelChecksOnceAndCaches() = runTest {
        val h = harness()
        h.setPermission(ChatPermission.Followers)
        h.twitch.followers += "u1"

        h.message("m1", "!sr ${link(idA)}", viewer = "u1")
        awaitUntil("first reply") { h.twitch.sends.size == 1 }
        h.message("m2", "!sr ${link(idB)}", viewer = "u1")
        awaitUntil("second reply") { h.twitch.sends.size == 2 }
        assertEquals(listOf("Alpha", "Bravo"), h.queued.map { it.track.name })
        assertEquals(listOf("u1"), h.twitch.followerLookups.toList())

        // Not a follower: told so, no Rejected entry, and the answer is cached as well.
        h.message("m3", "!sr ${link(idC)}", viewer = "u2")
        awaitUntil("refusal") { h.twitch.sends.size == 3 }
        assertEquals("m3", h.twitch.sends.last().replyTo)
        h.message("m4", "!sr ${link(idC)}", viewer = "u2")
        awaitUntil("second refusal") { h.twitch.sends.size == 4 }
        assertEquals(listOf("u1", "u2"), h.twitch.followerLookups.toList())
        assertEquals(2, h.queued.size)
        assertTrue(h.rejectedEntries.isEmpty())

        // A subscriber needs no follower check.
        h.message("m5", "!sr ${link(idC)}", viewer = "u3", badges = setOf("subscriber"))
        awaitUntil("subscriber reply") { h.twitch.sends.size == 5 }
        assertEquals(listOf("u1", "u2"), h.twitch.followerLookups.toList())
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), h.queued.map { it.track.name })
    }

    @Test
    fun roleLevelsNeedAtLeastTheRole() = runTest {
        val h = harness()
        h.setPermission(ChatPermission.Vips)

        h.message("m1", "!sr ${link(idA)}", viewer = "u1", badges = setOf("subscriber"))
        awaitUntil("refusal") { h.twitch.sends.size == 1 }
        assertTrue(h.queued.isEmpty())
        h.message("m2", "!sr ${link(idA)}", viewer = "u2", badges = setOf("vip"))
        awaitUntil("vip reply") { h.twitch.sends.size == 2 }
        h.message("m3", "!sr ${link(idB)}", viewer = "u3", badges = setOf("lead_moderator"))
        awaitUntil("moderator reply") { h.twitch.sends.size == 3 }
        h.message("m4", "!sr ${link(idC)}", viewer = "u4", badges = setOf("broadcaster"))
        awaitUntil("broadcaster reply") { h.twitch.sends.size == 4 }
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), h.queued.map { it.track.name })
        assertTrue(h.twitch.followerLookups.isEmpty())
        assertTrue(h.rejectedEntries.isEmpty())
    }

    @Test
    fun sharedChatFromOtherChannelIgnored() = runTest {
        val h = harness()
        h.message("m1", "!sr ${link(idA)}", source = "other-channel")
        h.message("m2", "!sr ${link(idB)}", source = RecordingTwitchApi.BROADCASTER_ID, viewer = "u2")

        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        settle()
        assertEquals(listOf("m2"), h.twitch.sends.map { it.replyTo })
        assertEquals(listOf("Bravo"), h.queued.map { it.track.name })
    }

    @Test
    fun ownRepliesAreIgnored() = runTest {
        val h = harness()
        h.message("m1", "!song")
        awaitUntil("own message remembered") { h.twitch.sends.size == 1 && h.sender.isOwnMessage("own-1") }

        h.message("own-1", "!song") // the app's own reply coming back through chat
        h.message("probe", "!song", viewer = "u2")
        awaitUntil("probe reply") { h.twitch.sends.size == 2 }
        settle()
        assertEquals(listOf("m1", "probe"), h.twitch.sends.map { it.replyTo })
    }

    @Test
    fun chatSwitchedOffIgnoresEverything() = runTest {
        val h = harness()
        h.settings.update { it.copy(twitch = it.twitch.copy(chatEnabled = false)) }
        h.message("m1", "!sr ${link(idA)}")
        h.message("m2", "!song")
        settle()
        assertTrue(h.twitch.sends.isEmpty())
        assertTrue(h.queued.isEmpty())

        h.settings.update { it.copy(twitch = it.twitch.copy(chatEnabled = true)) }
        h.message("m3", "!song")
        awaitUntil("reply") { h.twitch.sends.isNotEmpty() }
        assertEquals(listOf("m3"), h.twitch.sends.map { it.replyTo })
    }

    @Test
    fun plainChatIsIgnored() = runTest {
        val h = harness()
        h.message("m1", "hello everyone")
        h.message("m2", "!skip") // a plain viewer cannot
        h.message("probe", "!song", viewer = "u2")

        awaitUntil("probe reply") { h.twitch.sends.isNotEmpty() }
        settle()
        assertEquals(listOf("probe"), h.twitch.sends.map { it.replyTo })
    }

    @Test
    fun songAndQueueReplies() = runTest {
        val h = harness()

        h.message("s1", "!song")
        awaitUntil("nothing playing") { h.twitch.sends.size == 1 }
        assertTrue("nothing" in h.twitch.sends.last().message.lowercase(), h.twitch.sends.last().message)
        assertEquals("s1", h.twitch.sends.last().replyTo)

        h.message("q0", "!queue")
        awaitUntil("empty queue") { h.twitch.sends.size == 2 }
        assertTrue("empty" in h.twitch.sends.last().message.lowercase(), h.twitch.sends.last().message)

        h.message("r1", "!sr ${link(idA)}", viewer = "u1")
        awaitUntil("added A") { h.twitch.sends.size == 3 }
        h.message("r2", "!sr ${link(idB)}", viewer = "u2")
        awaitUntil("added B") { h.twitch.sends.size == 4 }
        h.message("r3", "!sr ${link(idC)}", viewer = "u1")
        awaitUntil("added C") { h.twitch.sends.size == 5 }

        h.message("q1", "!queue", viewer = "u1")
        awaitUntil("queue") { h.twitch.sends.size == 6 }
        val queue = h.twitch.sends.last()
        assertEquals("q1", queue.replyTo)
        assertTrue("1. Alpha" in queue.message, queue.message)
        assertTrue("2. Bravo" in queue.message, queue.message)
        assertTrue("3. Charlie" in queue.message, queue.message)
        assertTrue("#1" in queue.message && "#3" in queue.message, queue.message)
        assertFalse("#2" in queue.message, queue.message)

        // Spotify plays the streamer's own music.
        h.player.plays(track("own", "Own Song"), progressMs = 10_000)
        h.controller.start()
        awaitUntil("now playing") { h.controller.nowPlaying.value != null }
        h.message("s2", "!song")
        awaitUntil("song") { h.twitch.sends.size == 7 }
        assertTrue("Own Song" in h.twitch.sends.last().message, h.twitch.sends.last().message)
        assertTrue("Artist Own Song" in h.twitch.sends.last().message, h.twitch.sends.last().message)
        assertEquals("s2", h.twitch.sends.last().replyTo)
    }

    @Test
    fun queueIsCutToFiveItemsAndFiveHundredCharacters() = runTest {
        val h = harness()
        repeat(7) { i ->
            val id = ('D' + i).toString().repeat(22)
            h.catalog.tracks[id] = track(id, "Song ${"x".repeat(150)} $i")
            h.message("r$i", "!sr ${link(id)}", viewer = "viewer$i")
            awaitUntil("added $i") { h.queued.size == i + 1 }
        }
        awaitUntil("replies") { h.twitch.sends.size == 7 }

        h.message("q", "!queue", viewer = "viewer6")
        awaitUntil("queue") { h.twitch.sends.size == 8 }
        val text = h.twitch.sends.last().message
        assertTrue(text.length <= 500, "${text.length}")
        assertTrue("1. Song" in text)
        assertFalse("6. Song" in text, text)
        assertFalse(text.endsWith("..."), text)
        assertTrue("#7" in text, text) // the asker's own position stays
    }

    @Test
    fun wrongSongRemovesNewestOwnQueuedRequestOnly() = runTest {
        val h = harness()
        h.message("r1", "!sr ${link(idA)}", viewer = "u1")
        awaitUntil("added A") { h.queued.size == 1 }
        h.message("r2", "!sr ${link(idB)}", viewer = "u1")
        awaitUntil("added B") { h.queued.size == 2 }
        h.message("r3", "!sr ${link(idC)}", viewer = "u2")
        awaitUntil("added C") { h.queued.size == 3 && h.twitch.sends.size == 3 }

        h.message("w1", "!wrongsong", viewer = "u1")
        awaitUntil("removed") { h.twitch.sends.size == 4 }
        assertEquals(listOf("Alpha", "Charlie"), h.queued.map { it.track.name })
        assertTrue("Bravo" in h.twitch.sends.last().message, h.twitch.sends.last().message)
        assertEquals("w1", h.twitch.sends.last().replyTo)

        // Someone with nothing queued removes nothing of anyone else's.
        h.message("w2", "!wrongsong", viewer = "u3")
        awaitUntil("none") { h.twitch.sends.size == 5 }
        assertEquals(listOf("Alpha", "Charlie"), h.queued.map { it.track.name })
        assertFalse("Alpha" in h.twitch.sends.last().message || "Charlie" in h.twitch.sends.last().message)
    }
}
