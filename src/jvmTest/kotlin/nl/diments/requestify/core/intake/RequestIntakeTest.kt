package nl.diments.requestify.core.intake

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.playback.RequestEvent
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.rules.Rules
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.ShortLinkAnswer
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class RequestIntakeTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-intake") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val catalog = FakeSpotifyCatalog()
    private val shortLinks = FakeShortLinks()
    private val settings by lazy {
        SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
    }
    private val open = MutableStateFlow(true)

    private val idA = "A".repeat(22)
    private val idB = "B".repeat(22)
    private fun link(id: String) = "https://open.spotify.com/track/$id"

    private fun track(id: String, artist: String = "Artist", artistId: String = "art-$id", playable: Boolean = true) =
        Track(id, "spotify:track:$id", "Song $id", listOf(artist), 200_000, false, null, listOf(artistId), playable)

    private fun incoming(
        id: String,
        input: String = link(idA),
        viewer: String = "u-$id",
        source: RequestSource = RequestSource.CHAT,
        payment: Payment? = null,
        linkOnly: Boolean = false,
        sourceRef: String? = null,
    ) = IncomingRequest(id, input, linkOnly, Requester("V $viewer", viewer, viewer), source, payment, sourceRef, 1L)

    private class Parts(
        val controller: PlaybackController,
        val blacklist: BlacklistStore,
        val rejected: RejectedStore,
        val pending: PendingResolveStore,
        val intake: RequestIntake,
        val job: Job,
    )

    /** An intake on the files in [dir]; calling it again builds a second one on the same files. */
    private fun TestScope.parts(): Parts {
        val job = Job(backgroundScope.coroutineContext[Job])
        val controller = PlaybackController(
            player = FakeSpotifyPlayer { testScheduler.currentTime },
            store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
            requestsOpen = open,
            paidPriority = { true },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val blacklist = BlacklistStore(JsonFileStore(dir.resolve("blacklist.json"), Blacklist.serializer()) { Blacklist() })
        val rejected = RejectedStore(JsonFileStore(dir.resolve("rejected.json"), ListSerializer(RejectedEntry.serializer())) { emptyList() })
        val pending = pendingStore()
        val intake = RequestIntake(
            controller, catalog, settings, blacklist, rejected, pending, open,
            CoroutineScope(backgroundScope.coroutineContext + job),
            clock = { testScheduler.currentTime }, shortLinks = shortLinks, retryDelaysMs = listOf(5_000, 15_000),
        )
        return Parts(controller, blacklist, rejected, pending, intake, job)
    }

    private fun pendingStore() =
        PendingResolveStore(JsonFileStore(dir.resolve("pending.json"), ListSerializer(IncomingRequest.serializer())) { emptyList() })

    @Test
    fun linkResolvesAndIsQueuedWithPosition() = runTest {
        catalog.tracks[idA] = track(idA)
        catalog.tracks[idB] = track(idB)
        val p = parts()
        val first = p.intake.submit(incoming("r1", viewer = "u1"))
        val second = p.intake.submit(incoming("r2", input = link(idB), viewer = "u2"))

        assertIs<IntakeOutcome.Accepted>(first)
        assertEquals(1, first.position)
        assertEquals("r1", first.request.id)
        assertEquals(RequestSource.CHAT, first.request.source)
        assertEquals(2, assertIs<IntakeOutcome.Accepted>(second).position)
    }

    @Test
    fun searchTakesFirstPlayableResult() = runTest {
        catalog.searchResult = SpotifyResult.Ok(listOf(track("x1", playable = false), track("x2"), track("x3")))
        val p = parts()
        val out = p.intake.submit(incoming("r1", input = "some song"))

        assertEquals("x2", assertIs<IntakeOutcome.Accepted>(out).request.track.id)
        assertEquals(listOf("search:some song:5"), catalog.calls)
    }

    @Test
    fun albumLinkIsNotATrackAndListed() = runTest {
        val p = parts()
        val out = p.intake.submit(incoming("r1", input = "https://open.spotify.com/album/$idA"))

        assertEquals(IntakeOutcome.Rejected(RejectReason.NotATrack("album"), null, listed = true), out)
        assertEquals(listOf("r1"), p.rejected.entries.value.map { it.id })
        assertEquals(RejectReason.NotATrack("album"), p.rejected.entries.value.single().reason)
        assertTrue(catalog.calls.isEmpty())
    }

    @Test
    fun bitsWithoutLinkIsIgnored() = runTest {
        val p = parts()
        val bits = incoming("r1", input = "cheer100 nice song", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS)

        assertEquals(IntakeOutcome.Ignored, p.intake.submit(bits))
        assertTrue(p.rejected.entries.value.isEmpty())
        assertTrue(p.pending.pending.value.isEmpty())
        assertTrue(catalog.calls.isEmpty())
    }

    @Test
    fun shortLinkIsFollowedToItsTrack() = runTest {
        catalog.tracks[idA] = track(idA)
        shortLinks.leadsTo("https://spotify.link/abc123", "https://spotify.app.link/abc123?_p=x")
        shortLinks.leadsTo("https://spotify.app.link/abc123?_p=x", "${link(idA)}?si=zz")
        val p = parts()
        val out = p.intake.submit(incoming("r1", input = "this one spotify.link/abc123 pls"))

        assertEquals(idA, assertIs<IntakeOutcome.Accepted>(out).request.track.id)
        assertEquals(listOf("track:$idA"), catalog.calls) // the link text is never searched
    }

    @Test
    fun shortLinkThatLeadsNowhereIsRejectedAndListed() = runTest {
        val p = parts()
        val free = p.intake.submit(incoming("r1", input = "https://spotify.link/nothing"))
        val paid = p.intake.submit(
            incoming("r2", input = "Cheer100 https://spotify.link/nothing", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS),
        )

        assertEquals(IntakeOutcome.Rejected(RejectReason.ShortLinkUnresolved, null, listed = true), free)
        assertEquals(IntakeOutcome.Rejected(RejectReason.ShortLinkUnresolved, null, listed = true), paid)
        assertEquals(listOf("r1", "r2"), p.rejected.entries.value.map { it.id }.sorted())
        assertTrue(catalog.calls.isEmpty())
    }

    @Test
    fun paidShortLinkWaitsWhileItCannotBeFollowed() = runTest {
        catalog.tracks[idA] = track(idA)
        shortLinks.answers["https://spotify.link/abc123"] = ShortLinkAnswer.Unreachable
        val p = parts()
        val paid = incoming("p1", input = "spotify.link/abc123", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS)

        assertEquals(IntakeOutcome.Pending, p.intake.submit(paid))
        shortLinks.leadsTo("https://spotify.link/abc123", link(idA))
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(listOf("p1"), p.controller.queue.value.items.map { it.id })
        assertTrue(p.pending.pending.value.isEmpty())
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun unknownTrackAndUnplayableTrackAreRejected() = runTest {
        catalog.tracks[idB] = track(idB, playable = false)
        val p = parts()

        assertEquals(RejectReason.NoMatch, assertIs<IntakeOutcome.Rejected>(p.intake.submit(incoming("r1", input = link(idA)))).reason)
        assertEquals(RejectReason.Unplayable, assertIs<IntakeOutcome.Rejected>(p.intake.submit(incoming("r2", input = link(idB)))).reason)
        assertEquals(RejectReason.NoMatch, assertIs<IntakeOutcome.Rejected>(p.intake.submit(incoming("r3", input = "nothing"))).reason)
    }

    @Test
    fun freeRequestWhileSpotifyDownIsRejectedSpotifyUnavailable() = runTest {
        catalog.failure = SpotifyResult.Failed("boom")
        val p = parts()
        val out = p.intake.submit(incoming("r1"))

        assertEquals(IntakeOutcome.Rejected(RejectReason.SpotifyUnavailable, null, listed = true), out)
        assertEquals(RejectReason.SpotifyUnavailable, p.rejected.entries.value.single().reason)
        assertTrue(p.pending.pending.value.isEmpty())
    }

    @Test
    fun paidRequestWaitsForSpotifyAcrossRestart() = runTest {
        catalog.failure = SpotifyResult.Failed("boom")
        val paid = incoming("p1", payment = Payment.Bits(500), source = RequestSource.BITS)
        val first = parts()
        assertEquals(IntakeOutcome.Pending, first.intake.submit(paid))
        assertEquals(listOf(paid), pendingStore().pending.value) // on disk
        first.job.cancel() // the app is closed

        catalog.failure = null
        catalog.tracks[idA] = track(idA)
        val second = parts()
        val settled = mutableListOf<Pair<IncomingRequest, IntakeOutcome>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { second.intake.pendingResolved.collect { settled += it } }
        second.intake.start()
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(1, settled.size)
        assertEquals(paid, settled.single().first)
        assertIs<IntakeOutcome.Accepted>(settled.single().second)
        assertEquals(listOf("p1"), second.controller.queue.value.items.map { it.id })
        assertTrue(pendingStore().pending.value.isEmpty())
    }

    @Test
    fun pendingRequestStaysPendingWhileSpotifyStaysDown() = runTest {
        catalog.failure = SpotifyResult.Failed("boom")
        val p = parts()
        p.intake.submit(incoming("p1", payment = Payment.Bits(500), source = RequestSource.BITS))
        advanceTimeBy(5_001); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        advanceTimeBy(15_001); runCurrent() // the last delay repeats

        assertEquals(listOf("p1"), p.pending.pending.value.map { it.id })
        assertEquals(4, catalog.calls.size) // the submit and three retries
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun blockedViewerFreeRequestIsNotListedButPaidIs() = runTest {
        catalog.tracks[idA] = track(idA)
        val p = parts()
        p.blacklist.addViewer("u1", "viewer1", null, 0)

        val free = p.intake.submit(incoming("r1", viewer = "u1"))
        assertEquals(IntakeOutcome.Rejected(RejectReason.ViewerBlocked, track(idA), listed = false), free)
        assertTrue(p.rejected.entries.value.isEmpty())

        val paid = p.intake.submit(incoming("r2", viewer = "u1", payment = Payment.Bits(300), source = RequestSource.BITS))
        assertEquals(RejectReason.ViewerBlocked, assertIs<IntakeOutcome.Rejected>(paid).reason)
        assertTrue(paid.listed)
        assertEquals(listOf("r2"), p.rejected.entries.value.map { it.id })
        assertTrue(p.controller.queue.value.items.isEmpty())
    }

    @Test
    fun cooldownIsAtomicWithSubmit() = runTest {
        catalog.tracks[idA] = track(idA)
        catalog.tracks[idB] = track(idB)
        settings.tryUpdate { it.copy(rules = Rules(cooldownMinutes = 5)) }
        val p = parts()

        val first = p.intake.submit(incoming("r1", viewer = "u1"))
        val second = p.intake.submit(incoming("r2", input = link(idB), viewer = "u1"))

        assertIs<IntakeOutcome.Accepted>(first)
        assertIs<RejectReason.Cooldown>(assertIs<IntakeOutcome.Rejected>(second).reason)
        assertEquals(listOf("r1"), p.controller.queue.value.items.map { it.id })
    }

    @Test
    fun aRejectedRequestDoesNotStartTheCooldown() = runTest {
        catalog.tracks[idA] = track(idA)
        catalog.tracks[idB] = track(idB).copy(explicit = true)
        settings.tryUpdate { it.copy(rules = Rules(cooldownMinutes = 5, blockExplicit = true)) }
        val p = parts()

        assertIs<IntakeOutcome.Rejected>(p.intake.submit(incoming("r1", input = link(idB), viewer = "u1")))
        assertIs<IntakeOutcome.Accepted>(p.intake.submit(incoming("r2", viewer = "u1")))
    }

    @Test
    fun addAnywaySkipsRulesAndRemovesEntry() = runTest {
        catalog.tracks[idA] = track(idA)
        settings.tryUpdate { it.copy(rules = Rules(maxQueueLength = 0)) }
        val p = parts()
        val out = p.intake.submit(incoming("r1"))
        assertEquals(RejectReason.QueueFull(0), assertIs<IntakeOutcome.Rejected>(out).reason)

        p.intake.addAnyway("r1")
        runCurrent()

        val queued = p.controller.queue.value.items.single()
        assertEquals("r1", queued.id)
        assertTrue(queued.rulesWaived)
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun dismissRemovesEntry() = runTest {
        val p = parts()
        p.intake.submit(incoming("r1", input = "https://open.spotify.com/album/$idA"))
        p.intake.dismiss("r1")
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun blockArtistSweepsQueuedViewerRequestsButNotOwnOrWaived() = runTest {
        val p = parts()
        fun queued(id: String, source: RequestSource = RequestSource.CHAT, waived: Boolean = false, artistId: String = "bad") =
            Request(id, track("t-$id", "Bad", artistId), Requester("V", "v-$id", "u-$id"), source, null, 1L, rulesWaived = waived)
        p.controller.enqueue(queued("manual", RequestSource.MANUAL))
        p.controller.enqueue(queued("waived", waived = true))
        p.controller.enqueue(queued("chat"))
        p.controller.enqueue(queued("other", artistId = "good"))
        runCurrent()
        val events = mutableListOf<RequestEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.controller.events.collect { events += it } }

        assertTrue(p.intake.blockArtist("bad", "Bad", null))
        runCurrent()

        assertEquals(listOf("manual", "waived", "other"), p.controller.queue.value.items.map { it.id })
        val entry = p.rejected.entries.value.single()
        assertEquals("chat", entry.id)
        assertEquals(RejectReason.ArtistBlocked, entry.reason)
        assertEquals("chat", entry.request?.id)
        val removed = events.filterIsInstance<RequestEvent.Removed>().single()
        assertEquals("chat", removed.request.id)
        assertEquals(RemoveReason.Blacklisted, removed.why)
    }

    @Test
    fun blockViewerSweepDoesNotListTheirFreeRequestsButListsPaid() = runTest {
        val p = parts()
        fun queued(id: String, payment: Payment?) =
            Request(id, track("t-$id"), Requester("V", "v", "u1"), RequestSource.CHAT, payment, 1L)
        p.controller.enqueue(queued("free", null))
        p.controller.enqueue(queued("paid", Payment.Bits(100)))
        runCurrent()

        assertTrue(p.intake.blockViewer("u1", "v", "mod"))

        assertTrue(p.controller.queue.value.items.isEmpty())
        assertEquals(listOf("paid"), p.rejected.entries.value.map { it.id })
        assertEquals(RejectReason.ViewerBlocked, p.rejected.entries.value.single().reason)
    }

    /** Makes writes of [name] fail (a directory sits where its temp file goes) until [healWrites]. */
    private fun breakWrites(name: String) { dir.resolve("$name.tmp").toFile().mkdirs() }
    private fun healWrites(name: String) { dir.resolve("$name.tmp").toFile().deleteRecursively() }

    @Test
    fun blockedViewerFreeRejectionsOfAnyReasonAreNotListed() = runTest {
        catalog.tracks[idB] = track(idB, playable = false)
        val p = parts()
        p.blacklist.addViewer("u1", "viewer1", null, 0)

        val album = p.intake.submit(incoming("r1", input = "https://open.spotify.com/album/$idA", viewer = "u1"))
        val noMatch = p.intake.submit(incoming("r2", input = "asdf", viewer = "u1"))
        val unplayable = p.intake.submit(incoming("r3", input = link(idB), viewer = "u1"))

        listOf(album, noMatch, unplayable).forEach { assertEquals(false, assertIs<IntakeOutcome.Rejected>(it).listed) }
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun blockedViewerFreeRequestWhileSpotifyDownIsNotListed() = runTest {
        catalog.failure = SpotifyResult.Failed("boom")
        val p = parts()
        p.blacklist.addViewer("u1", "viewer1", null, 0)

        val out = p.intake.submit(incoming("r1", viewer = "u1"))

        assertEquals(IntakeOutcome.Rejected(RejectReason.SpotifyUnavailable, null, listed = false), out)
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun sweepLeavesARequestQueuedWhenItsRejectedEntryCannotBeWritten() = runTest {
        val p = parts()
        p.controller.enqueue(Request("paid", track("t-paid", "Bad", "bad"), Requester("V", "v", "u1"), RequestSource.BITS, Payment.Bits(100), 1L))
        runCurrent()
        val events = mutableListOf<RequestEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.controller.events.collect { events += it } }
        breakWrites("rejected.json")

        assertTrue(p.intake.blockArtist("bad", "Bad", null))
        runCurrent()

        assertEquals(listOf("paid"), p.controller.queue.value.items.map { it.id })
        assertTrue(events.filterIsInstance<RequestEvent.Removed>().isEmpty())
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    @Test
    fun paidRequestIsStillPendingWhenPendingJsonCannotBeWritten() = runTest {
        catalog.failure = SpotifyResult.Failed("boom")
        breakWrites("pending.json")
        val p = parts()

        val out = p.intake.submit(incoming("p1", payment = Payment.Bits(500), source = RequestSource.BITS))

        assertEquals(IntakeOutcome.Pending, out)
        assertTrue(p.rejected.entries.value.isEmpty())
        catalog.failure = null
        catalog.tracks[idA] = track(idA)
        advanceTimeBy(5_001); runCurrent() // retried in memory
        assertEquals(listOf("p1"), p.controller.queue.value.items.map { it.id })
    }

    @Test
    fun paidRejectionThatCannotBeSavedIsRetriedUntilSaved() = runTest {
        breakWrites("rejected.json")
        val p = parts()

        val out = p.intake.submit(incoming("p1", input = "https://open.spotify.com/album/$idA", payment = Payment.Bits(500), source = RequestSource.BITS))

        assertEquals(IntakeOutcome.Rejected(RejectReason.NotATrack("album"), null, listed = true), out)
        assertTrue(p.rejected.entries.value.isEmpty())
        advanceTimeBy(5_001); runCurrent()
        assertTrue(p.rejected.entries.value.isEmpty()) // still broken
        healWrites("rejected.json")
        advanceTimeBy(15_001); runCurrent()
        assertEquals(listOf("p1"), p.rejected.entries.value.map { it.id })
    }

    @Test
    fun freeRejectionThatCannotBeSavedIsDroppedAndNotListed() = runTest {
        breakWrites("rejected.json")
        val p = parts()

        val out = p.intake.submit(incoming("r1", input = "https://open.spotify.com/album/$idA"))
        healWrites("rejected.json")
        advanceTimeBy(60_000); runCurrent()

        assertEquals(IntakeOutcome.Rejected(RejectReason.NotATrack("album"), null, listed = false), out)
        assertTrue(p.rejected.entries.value.isEmpty())
    }

    /** Collects every [IntakeRecord] the intake reports, from now on. */
    private fun TestScope.recordsOf(p: Parts): List<IntakeRecord> {
        val seen = mutableListOf<IntakeRecord>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.intake.records.collect { seen += it } }
        return seen
    }

    @Test
    fun everySubmitIsRecorded() = runTest {
        catalog.tracks[idA] = track(idA)
        settings.tryUpdate { it.copy(rules = Rules(maxQueueLength = 1)) }
        val p = parts()
        val records = recordsOf(p)

        val accepted = p.intake.submit(incoming("r1"))
        val rejected = p.intake.submit(incoming("r2", viewer = "u2"))
        val ignored = p.intake.submit(incoming("r3", input = "just a cheer", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS))
        catalog.failure = SpotifyResult.Failed("boom")
        val pending = p.intake.submit(incoming("r4", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS))
        runCurrent()

        assertEquals(listOf("r1", "r2", "r3", "r4"), records.map { it.incoming.id })
        assertEquals(listOf(accepted, rejected, ignored, pending), records.map { it.outcome })
        assertIs<IntakeOutcome.Accepted>(accepted)
        assertIs<IntakeOutcome.Rejected>(rejected)
        assertEquals(IntakeOutcome.Ignored, ignored)
        assertEquals(IntakeOutcome.Pending, pending)
    }

    @Test
    fun aSettledPendingRequestIsRecordedAgain() = runTest {
        catalog.tracks[idA] = track(idA)
        catalog.failure = SpotifyResult.Failed("boom")
        val p = parts()
        val records = recordsOf(p)
        p.intake.submit(incoming("p1", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS))

        catalog.failure = null
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(listOf("p1", "p1"), records.map { it.incoming.id })
        assertEquals(IntakeOutcome.Pending, records[0].outcome)
        assertIs<IntakeOutcome.Accepted>(records[1].outcome)
    }

    @Test
    fun aRejectionBeforeTheRulesIsRecorded() = runTest {
        catalog.tracks[idA] = track(idA)
        val p = parts()
        val records = recordsOf(p)
        val out = p.intake.rejectBeforeRules(
            incoming("b1", linkOnly = true, payment = Payment.Bits(10), source = RequestSource.BITS),
            RejectReason.BelowMinimum(100),
        )
        runCurrent()

        assertEquals(listOf(out), records.map { it.outcome })
    }

    @Test
    fun addAnywayIsRecorded() = runTest {
        catalog.tracks[idA] = track(idA)
        settings.tryUpdate { it.copy(rules = Rules(maxQueueLength = 0)) }
        val p = parts()
        val records = recordsOf(p)
        p.intake.submit(incoming("r1"))

        p.intake.addAnyway("r1")
        runCurrent()

        assertEquals(listOf("r1", "r1"), records.map { it.incoming.id })
        val added = assertIs<IntakeOutcome.Accepted>(records[1].outcome)
        assertEquals("r1", added.request.id)
        assertEquals(1, added.position)
    }

    @Test
    fun isKnownCoversQueueRejectedAndPending() = runTest {
        catalog.tracks[idA] = track(idA)
        settings.tryUpdate { it.copy(rules = Rules(maxQueueLength = 1)) }
        val p = parts()
        p.intake.submit(incoming("queued"))
        p.intake.submit(incoming("rejected", viewer = "u2"))
        catalog.failure = SpotifyResult.Failed("boom")
        p.intake.submit(incoming("pending", linkOnly = true, payment = Payment.Bits(100), source = RequestSource.BITS))
        runCurrent()

        assertTrue(p.intake.isKnown("queued"))
        assertTrue(p.intake.isKnown("rejected"))
        assertTrue(p.intake.isKnown("pending"))
        assertEquals(false, p.intake.isKnown("never-seen"))
    }

    @Test
    fun theStreamersOwnAddIsRecorded() = runTest {
        val p = parts()
        val records = recordsOf(p)
        val own = Request("own1", track(idA), Requester(""), RequestSource.MANUAL, null, createdAtEpochMs = 5L)

        p.intake.addOwn(own)
        runCurrent()

        assertEquals(listOf("own1"), p.controller.queue.value.items.map { it.id })
        val record = records.single()
        assertEquals("own1", record.incoming.id)
        assertEquals(RequestSource.MANUAL, record.incoming.source)
        assertEquals(IntakeOutcome.Accepted(own, position = 1, waitMs = 0), record.outcome)
    }
}
