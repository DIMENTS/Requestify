package nl.diments.requestify.core.playback

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.setLastModifiedTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.LastPlayed
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

/** [PlaybackController.submit], the targeted removals and the request lifecycle events. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class PlaybackControllerSubmitTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-submit") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() =
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })

    /** Like [store], but a read that fails is not tried again (no real sleeps in virtual-time tests). */
    private fun storeWithoutReadRetries() = QueueStore(
        JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer(), retryDelaysMs = emptyList()) {
            PersistedPlayback()
        },
    )

    /** Another program holds queue.json and denies reading it (a Windows sharing violation). */
    private fun lockQueueFile(): SeekableByteChannel =
        Files.newByteChannel(dir.resolve("queue.json"), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)

    /** Every track is 200 s long. */
    private fun track(id: String) = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null)

    private fun request(
        id: String,
        viewer: String = "viewer-$id",
        source: RequestSource = RequestSource.CHAT,
        payment: Payment? = null,
    ) = Request(id, track("t-$id"), Requester("Viewer $viewer", viewer), source, payment, createdAtEpochMs = 1L)

    private val trackA = track("A")
    private val r1 = request("r1")
    private val r2 = request("r2")

    private fun TestScope.fakePlayer() = FakeSpotifyPlayer { testScheduler.currentTime }

    private fun TestScope.controller(player: FakeSpotifyPlayer, store: QueueStore = store()) = PlaybackController(
        player = player,
        store = store,
        requestsOpen = MutableStateFlow(true),
        paidPriority = { true },
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Moves virtual time forward by [ms] and runs everything that is due at the new time. */
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** An event, with what queue.json held at the moment it was emitted. */
    private data class Seen(val event: RequestEvent, val saved: PersistedPlayback)

    /**
     * Collects [PlaybackController.events] from now on. The collector runs inside the emit, so [Seen.saved] is
     * exactly what was saved when the event went out.
     */
    private fun TestScope.eventsOf(controller: PlaybackController): List<Seen> {
        val seen = mutableListOf<Seen>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { seen += Seen(it, store().load()) }
        }
        return seen
    }

    /** Track A has 15 s left, so the first tick hands R1 over. */
    private fun TestScope.startWithR1HandedOver(player: FakeSpotifyPlayer): PlaybackController {
        player.plays(trackA, progressMs = 185_000)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(r1, controller.handedOver.value)
        return controller
    }

    @Test
    fun submitAcceptsAtPaidPriorityPositionWithWait() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 100_000) // 100 s into a 200 s track
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()

        val paid = request("r2", payment = Payment.Bits(500), source = RequestSource.BITS)
        val outcome = controller.submit(paid) { null }

        assertEquals(SubmitOutcome.Accepted(position = 1, waitMs = 100_000), outcome)
        assertEquals(listOf(paid, r1), controller.queue.value.items)
        assertEquals(listOf(paid, r1), store().load().queue.items)
    }

    @Test
    fun waitCountsTheTracksQueuedBefore() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 100_000) // 100 s left, nothing handed over yet
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()

        assertEquals(SubmitOutcome.Accepted(position = 2, waitMs = 100_000 + 200_000), controller.submit(r2) { null })
    }

    @Test
    fun waitCountsTheCurrentTrackOnlyWhileItIsLive() = runTest {
        store().save(PersistedPlayback(lastPlayed = LastPlayed(trackA, progressMs = 60_000, playedAtEpochMs = 0)))
        val controller = controller(fakePlayer()) // Spotify has nothing active

        assertEquals(SubmitOutcome.Accepted(position = 1, waitMs = 0), controller.submit(r1) { null })
        assertEquals(NowPlayingKind.LastPlayed, controller.nowPlaying.value?.kind)
        assertEquals(SubmitOutcome.Accepted(position = 2, waitMs = 200_000), controller.submit(r2) { null })
    }

    @Test
    fun aSubmitCancelledDuringItsSaveIsStillAddedAndSaved() = runTest {
        val controller = controller(fakePlayer())
        var outcome: SubmitOutcome? = null
        lateinit var caller: Job
        caller = launch { outcome = controller.submit(r1) { caller.cancel(); null } } // cancelled before the save
        runCurrent()

        assertTrue(caller.isCancelled)
        assertNull(outcome, "the caller got a CancellationException")
        assertEquals(listOf(r1), controller.queue.value.items)
        assertEquals(listOf(r1), store().load().queue.items)
    }

    @Test
    fun submitRejectsWithTheDecidedReasonAndAddsNothing() = runTest {
        store().save(PersistedPlayback(queue = RequestQueue(listOf(r1))))
        val file = dir.resolve("queue.json")
        file.setLastModifiedTime(FileTime.fromMillis(0)) // a save would replace the file, with a new time
        val controller = controller(fakePlayer())

        val outcome = controller.submit(r2) { snapshot ->
            assertEquals(listOf(r1), snapshot.queue)
            RejectReason.QueueFull(25)
        }

        assertEquals(SubmitOutcome.Rejected(RejectReason.QueueFull(25)), outcome)
        assertEquals(listOf(r1), controller.queue.value.items)
        assertEquals(FileTime.fromMillis(0), file.getLastModifiedTime(), "nothing is saved")
    }

    @Test
    fun concurrentSubmitsFromOneViewerRespectTheLimit() = runTest {
        val controller = controller(fakePlayer())
        val oneEach: (PlaybackSnapshot) -> RejectReason? = { snapshot ->
            val held = snapshot.queue + listOfNotNull(snapshot.handedOver, snapshot.playing)
            if (held.any { it.requester.login == "v1" }) RejectReason.ViewerLimit(1) else null
        }
        val first = async { controller.submit(request("a", viewer = "v1"), oneEach) }
        val second = async { controller.submit(request("b", viewer = "v1"), oneEach) }
        runCurrent()

        val outcomes = listOf(first.getCompleted(), second.getCompleted())
        assertEquals(1, outcomes.count { it is SubmitOutcome.Accepted }, "outcomes: $outcomes")
        assertEquals(
            listOf(SubmitOutcome.Rejected(RejectReason.ViewerLimit(1))),
            outcomes.filterIsInstance<SubmitOutcome.Rejected>(),
        )
        assertEquals(1, controller.queue.value.items.size)
        assertEquals(controller.queue.value.items, store().load().queue.items)
    }

    @Test
    fun submitWaitsForTheSavedState() = runTest {
        val r0 = request("r0")
        store().save(PersistedPlayback(handedOver = r1, history = listOf(PlayedEntry(r0, playedAtEpochMs = 1))))
        val controller = controller(fakePlayer(), store = storeWithoutReadRetries())

        val lock = lockQueueFile()
        val outcome = async { controller.submit(r2) { null } }
        runCurrent()
        assertNotNull(controller.loadError.value)
        assertFalse(outcome.isCompleted, "it waits for the saved state")
        lock.close()

        advance(PlaybackConfig().idlePollMs)
        assertEquals(SubmitOutcome.Accepted(position = 1, waitMs = 200_000), outcome.getCompleted()) // after R1
        val saved = store().load()
        assertEquals(r1, saved.handedOver)
        assertEquals(listOf(PlayedEntry(r0, playedAtEpochMs = 1)), saved.history)
        assertEquals(listOf(r2), saved.queue.items)
    }

    @Test
    fun submitOfAKnownIdIsNotAddedAgainNorDecided() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player) // track A has 15 s left
        controller.enqueue(r2)
        runCurrent()
        val noDecision: (PlaybackSnapshot) -> RejectReason? = { fail("decide is not called for a known id") }

        assertEquals(SubmitOutcome.Accepted(position = 0, waitMs = 15_000), controller.submit(r1, noDecision))
        assertEquals(SubmitOutcome.Accepted(position = 1, waitMs = 215_000), controller.submit(r2, noDecision))

        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertEquals(SubmitOutcome.Accepted(position = 0, waitMs = 0), controller.submit(r1, noDecision))
        assertEquals(listOf(r2), controller.queue.value.items)

        var decided: PlaybackSnapshot? = null
        controller.submit(request("r3")) { decided = it; null }
        assertEquals(r1, decided?.playing, "decide sees the request playing")
        assertEquals(listOf(r2), decided?.queue)
    }

    @Test
    fun snapshotShowsTheQueueTheHandedOverAndThePlayingRequest() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        controller.enqueue(r2)
        runCurrent()
        assertEquals(listOf(r2), controller.snapshot().queue)
        assertEquals(r1, controller.snapshot().handedOver)

        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        val snapshot = controller.snapshot()
        assertEquals(r1, snapshot.playing)
        assertEquals(r1.track, snapshot.nowPlaying?.track)
        assertNull(snapshot.handedOver)
    }

    @Test
    fun startedIsEmittedWhenTheHandedOverRequestPlays() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 185_000)
        val controller = controller(player)
        val seen = eventsOf(controller)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(r1, controller.handedOver.value)
        assertEquals(emptyList(), seen, "a hand-over is neither a start nor a removal")

        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertEquals(listOf<RequestEvent>(RequestEvent.Started(r1)), seen.map { it.event })
        assertEquals(r1, seen.single().saved.playing, "emitted after the save")
    }

    @Test
    fun removeEmitsRemovedWithReason() = runTest {
        val controller = controller(fakePlayer())
        val seen = eventsOf(controller)
        controller.enqueue(r1)
        controller.enqueue(r2)
        runCurrent()

        controller.remove("r1", RemoveReason.WrongSong)
        runCurrent()
        assertEquals(listOf<RequestEvent>(RequestEvent.Removed(r1, RemoveReason.WrongSong)), seen.map { it.event })
        assertEquals(listOf(r2), seen.single().saved.queue.items, "emitted after the save")

        controller.remove("r2") // the streamer's X
        controller.remove("unknown")
        runCurrent()
        assertEquals(
            listOf<RequestEvent>(
                RequestEvent.Removed(r1, RemoveReason.WrongSong),
                RequestEvent.Removed(r2, RemoveReason.Streamer),
            ),
            seen.map { it.event },
        )
    }

    @Test
    fun removeOfTheHandedOverRequestChangesNothing() = runTest {
        val controller = startWithR1HandedOver(fakePlayer())
        val seen = eventsOf(controller)
        controller.remove("r1")
        runCurrent()
        assertEquals(r1, controller.handedOver.value)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun removedWaitsForTheSaveThatSucceeds() = runTest {
        val controller = controller(fakePlayer())
        val seen = eventsOf(controller)
        controller.enqueue(r1)
        controller.enqueue(r2)
        runCurrent()

        val blocker = dir.resolve("queue.json.tmp").createDirectory() // the store cannot write its temp file
        controller.remove("r1")
        runCurrent()
        assertNotNull(controller.persistError.value)
        assertEquals(emptyList(), seen, "not saved yet: a restart would bring R1 back")

        blocker.deleteExisting()
        controller.enqueue(request("r3")) // the next change saves both
        runCurrent()
        assertEquals(listOf<RequestEvent>(RequestEvent.Removed(r1, RemoveReason.Streamer)), seen.map { it.event })
        assertFalse(seen.single().saved.queue.items.any { it.id == "r1" })
    }

    @Test
    fun removeMatchingRemovesAtomicallyAndReturnsRemoved() = runTest {
        val controller = controller(fakePlayer())
        val seen = eventsOf(controller)
        val free1 = request("free1")
        val paid = request("paid", source = RequestSource.BITS, payment = Payment.Bits(100))
        val free2 = request("free2")
        val own = request("own", source = RequestSource.MANUAL)
        listOf(free1, paid, free2, own).forEach(controller::enqueue)
        runCurrent()

        var picked: List<Request>? = null
        val removed = controller.removeMatching(RemoveReason.StreamEnded) { queued ->
            picked = queued
            queued.filter { it.payment == null && it.source == RequestSource.CHAT }
        }

        assertEquals(listOf(paid, free1, free2, own), picked, "pick sees the whole queue")
        assertEquals(listOf(free1, free2), removed)
        assertEquals(listOf(paid, own), controller.queue.value.items)
        assertEquals(listOf(paid, own), store().load().queue.items)
        assertEquals(
            listOf<RequestEvent>(
                RequestEvent.Removed(free1, RemoveReason.StreamEnded),
                RequestEvent.Removed(free2, RemoveReason.StreamEnded),
            ),
            seen.map { it.event },
        )
        assertTrue(seen.all { it.saved.queue.items == listOf(paid, own) }, "both emitted after the one save")
    }

    /**
     * Queues R1, then [behind]. Track A has 15 s left, so the first tick picks R1, and Spotify holds the hand-over
     * open until the returned gate completes.
     */
    private fun TestScope.startHandingOverR1(
        player: FakeSpotifyPlayer,
        controller: PlaybackController,
        behind: List<Request> = emptyList(),
    ): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        player.addToQueueGate = gate
        player.plays(trackA, progressMs = 185_000)
        controller.enqueue(r1)
        behind.forEach(controller::enqueue)
        controller.start()
        runCurrent()
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg })
        return gate
    }

    @Test
    fun removeWhileHandingOverIsDroppedWhenSpotifyTakesIt() = runTest {
        val player = fakePlayer()
        val controller = controller(player)
        val seen = eventsOf(controller)
        val gate = startHandingOverR1(player, controller)

        controller.remove("r1")
        runCurrent()
        assertEquals(listOf(r1), controller.queue.value.items, "the removal waits for Spotify's answer")
        assertEquals(emptyList(), seen)

        gate.complete(Unit)
        runCurrent()
        assertEquals(r1, controller.handedOver.value, "Spotify has it: nothing can be pulled back")
        assertTrue(controller.queue.value.items.isEmpty())
        assertEquals(emptyList(), seen, "not removed")

        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertEquals(listOf<RequestEvent>(RequestEvent.Started(r1)), seen.map { it.event }, "only started")
    }

    @Test
    fun removeWhileHandingOverAppliesWhenSpotifyRefuses() = runTest {
        val player = fakePlayer()
        player.addToQueueResults.add(SpotifyResult.Failed("HTTP 503"))
        val controller = controller(player)
        val seen = eventsOf(controller)
        val gate = startHandingOverR1(player, controller)

        controller.remove("r1")
        runCurrent()
        assertEquals(listOf(r1), controller.queue.value.items)

        gate.complete(Unit)
        runCurrent()
        assertTrue(controller.queue.value.items.isEmpty())
        assertNull(controller.handedOver.value)
        assertEquals(listOf<RequestEvent>(RequestEvent.Removed(r1, RemoveReason.Streamer)), seen.map { it.event })
        assertTrue(seen.single().saved.queue.items.isEmpty(), "emitted after the save")

        advance(3_000)
        assertEquals(1, player.callsTo("addToQueue").size, "not sent again")
        assertEquals(1, seen.size, "removed once")
    }

    @Test
    fun removeMatchingDuringTheHandOverDoesNotPickIt() = runTest {
        val player = fakePlayer()
        val controller = controller(player)
        val seen = eventsOf(controller)
        val gate = startHandingOverR1(player, controller, behind = listOf(r2))

        var offered: List<Request>? = null
        val removed = controller.removeMatching(RemoveReason.StreamEnded) { offered = it; it + r1 } // R1 named anyway
        assertEquals(listOf(r2), offered, "the request being handed over is not offered")
        assertEquals(listOf(r2), removed)
        assertEquals(listOf(r1), controller.queue.value.items)

        gate.complete(Unit)
        runCurrent()
        assertEquals(r1, controller.handedOver.value)
        assertEquals(listOf<RequestEvent>(RequestEvent.Removed(r2, RemoveReason.StreamEnded)), seen.map { it.event })
    }

    @Test
    fun removeDuringTheHandOverOfASkipWaitsToo() = runTest {
        val player = fakePlayer()
        val controller = controller(player) // not started: only the skip talks to Spotify
        val seen = eventsOf(controller)
        controller.enqueue(r1)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        player.addToQueueGate = gate
        launch { controller.skip() }
        runCurrent()
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg })

        controller.remove("r1", RemoveReason.WrongSong)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(r1, controller.handedOver.value)
        assertEquals(listOf("addToQueue", "skipNext"), player.calls.map { it.name })
        assertEquals(emptyList(), seen)
    }

    @Test
    fun stoppingDuringTheHandOverEndsItAsRefused() = runTest {
        val player = fakePlayer()
        val controller = controller(player)
        val seen = eventsOf(controller)
        startHandingOverR1(player, controller)
        controller.remove("r1", RemoveReason.ModRemove)
        runCurrent()
        assertEquals(listOf(r1), controller.queue.value.items, "the removal waits for Spotify's answer")
        assertEquals(emptyList(), seen)

        controller.stop() // cancels the tick while Spotify has not answered
        runCurrent()
        assertTrue(controller.queue.value.items.isEmpty())
        assertNull(controller.handedOver.value)
        assertEquals(listOf<RequestEvent>(RequestEvent.Removed(r1, RemoveReason.ModRemove)), seen.map { it.event })
    }

    @Test
    fun removeMatchingIgnoresPicksThatAreNotQueued() = runTest {
        val controller = startWithR1HandedOver(fakePlayer())
        controller.enqueue(r2)
        runCurrent()
        val removed = controller.removeMatching(RemoveReason.ModRemove) { listOf(r1, request("elsewhere")) }
        assertEquals(emptyList(), removed)
        assertEquals(r1, controller.handedOver.value)
        assertEquals(listOf(r2), controller.queue.value.items)
    }
}
