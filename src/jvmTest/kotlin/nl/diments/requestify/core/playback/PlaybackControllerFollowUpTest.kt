package nl.diments.requestify.core.playback

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.spotify.RecentTrack
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

/** Near-end polling, a handed-over request that played while the app was away, and reopening requests mid-song. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class PlaybackControllerFollowUpTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-follow-up") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() =
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })

    /** Every track is 200 s long. */
    private fun track(id: String) = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null)

    private fun request(id: String) =
        Request(id, track("t-$id"), Requester("Viewer $id"), RequestSource.CHAT, null, createdAtEpochMs = 1L)

    private val trackA = track("A")
    private val trackB = track("B")
    private val trackC = track("C")
    private val r0 = request("r0")
    private val r1 = request("r1")
    private val r2 = request("r2")

    private fun TestScope.fakePlayer() = FakeSpotifyPlayer { testScheduler.currentTime }

    private fun TestScope.controller(
        player: FakeSpotifyPlayer,
        requestsOpen: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = PlaybackController(
        player = player,
        store = store(),
        requestsOpen = requestsOpen,
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

    private fun FakeSpotifyPlayer.pollTimes() = callsTo("currentlyPlaying").map { it.atMs }

    /** An event, with what queue.json held at the moment it was emitted. */
    private data class Seen(val event: RequestEvent, val saved: PersistedPlayback)

    /** Collects [PlaybackController.events] from now on; the collector runs inside the emit. */
    private fun TestScope.eventsOf(controller: PlaybackController): List<Seen> {
        val seen = mutableListOf<Seen>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { seen += Seen(it, store().load()) }
        }
        return seen
    }

    /** When R1 went to Spotify in the saved state, before the app was closed. */
    private val handedOverAt = 1_000L

    /**
     * The app was closed for 10 minutes with R1 handed over (at [handedOverAt]) and R0 playing. Now Spotify plays
     * track C, and its queue no longer has R1.
     */
    private fun TestScope.reopenAfterAway(player: FakeSpotifyPlayer) {
        store().save(PersistedPlayback(handedOver = r1, playing = r0, handedOverAtEpochMs = handedOverAt))
        advanceTimeBy(600_000)
        player.plays(trackC, progressMs = 30_000)
        player.userQueueResult = SpotifyResult.Ok(listOf(trackB.id))
    }

    @Test
    fun nearEndPollsFastOnlyWhilePlaying() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 190_000, isPlaying = false) // paused with 10 s left
        val controller = controller(player)
        controller.start()
        runCurrent()

        advance(2_999)
        assertEquals(listOf(0L), player.pollTimes(), "paused: no near-end polling")
        advance(1)
        assertEquals(listOf(0L, 3_000L), player.pollTimes())

        player.plays(trackA, progressMs = 190_000) // playing again
        advance(3_000)
        advance(1_000)
        assertEquals(listOf(0L, 3_000L, 6_000L, 7_000L), player.pollTimes(), "playing: every second near the end")
    }

    @Test
    fun handedOverThatPlayedWhileAwayGoesToHistoryNotBackToHead() = runTest {
        val player = fakePlayer()
        reopenAfterAway(player)
        player.recentSinceResult = SpotifyResult.Ok(
            listOf(RecentTrack(trackB, playedAtEpochMs = 500_000), RecentTrack(r1.track, playedAtEpochMs = 300_000)),
        )
        val controller = controller(player)
        val seen = eventsOf(controller)
        controller.start()
        runCurrent()

        assertEquals(listOf(handedOverAt.toString()), player.callsTo("recentlyPlayedSince").map { it.arg })
        assertEquals(listOf(r1, r0), controller.history.value.map { it.request }, "R1 played after R0")
        assertTrue(controller.queue.value.items.isEmpty())
        assertNull(controller.handedOver.value)
        assertEquals(listOf<RequestEvent>(RequestEvent.Started(r1)), seen.map { it.event })
        val saved = seen.single().saved // emitted after the save
        assertEquals(listOf(r1, r0), saved.history.map { it.request })
        assertNull(saved.handedOver)
        assertNull(saved.handedOverAtEpochMs)
        assertTrue(saved.queue.items.isEmpty())

        player.plays(trackC, progressMs = 190_000)
        advance(3_000)
        assertTrue(player.callsTo("addToQueue").isEmpty(), "not played twice")
    }

    @Test
    fun handedOverMissingAndNotInRecentlyPlayedReturnsToHead() = runTest {
        val player = fakePlayer()
        reopenAfterAway(player)
        player.recentSinceResult = SpotifyResult.Ok(listOf(RecentTrack(trackB, playedAtEpochMs = 500_000)))
        val controller = controller(player)
        val seen = eventsOf(controller)
        controller.start()
        runCurrent()

        assertEquals(1, player.callsTo("recentlyPlayedSince").size)
        assertEquals(listOf(r1), controller.queue.value.items)
        assertNull(controller.handedOver.value)
        assertEquals(listOf(r0), controller.history.value.map { it.request })
        assertEquals(emptyList(), seen, "neither started nor removed")
        val saved = store().load()
        assertEquals(listOf(r1), saved.queue.items)
        assertNull(saved.handedOver)
        assertNull(saved.handedOverAtEpochMs)
    }

    @Test
    fun aFailedRecentlyPlayedReadReturnsItToTheHead() = runTest {
        val player = fakePlayer()
        reopenAfterAway(player)
        player.recentSinceResult = SpotifyResult.Failed("HTTP 500")
        val controller = controller(player)
        controller.start()
        runCurrent()

        assertEquals(listOf(r1), controller.queue.value.items, "as before")
        assertNull(controller.handedOver.value)
        assertEquals(SpotifyHealth.Ok, controller.health.value, "an extra read never changes health")
    }

    @Test
    fun aRateLimitedRecentlyPlayedReadWaitsAsAsked() = runTest {
        val player = fakePlayer()
        reopenAfterAway(player)
        player.recentSinceResult = SpotifyResult.RateLimited(7_000)
        val controller = controller(player)
        controller.start()
        runCurrent()
        val first = player.pollTimes().single()

        assertEquals(listOf(r1), controller.queue.value.items)
        controller.enqueue(r2) // a wake-up must not cut the wait short
        advance(6_999)
        assertEquals(listOf(first), player.pollTimes())
        advance(1)
        assertEquals(listOf(first, first + 7_000), player.pollTimes())
    }

    @Test
    fun aSavedHandOverWithoutItsTimeReturnsToTheHeadUnasked() = runTest {
        val player = fakePlayer()
        store().save(PersistedPlayback(handedOver = r1)) // written before the time was kept
        player.plays(trackC, progressMs = 30_000)
        val controller = controller(player)
        controller.start()
        runCurrent()

        assertTrue(player.callsTo("recentlyPlayedSince").isEmpty())
        assertEquals(listOf(r1), controller.queue.value.items)
    }

    @Test
    fun handOverEmitsHandedOverAfterTheSave() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 185_000) // 15 s left: the first tick hands R1 over
        val controller = controller(player)
        val seen = eventsOf(controller)
        controller.enqueue(r1)
        controller.start()
        runCurrent()

        val handedOver = seen.single { it.event is RequestEvent.HandedOver }
        assertEquals(RequestEvent.HandedOver(r1), handedOver.event)
        assertEquals(r1, handedOver.saved.handedOver) // emitted once queue.json shows it
    }

    @Test
    fun theHandOverTimeIsSavedAndClearedWithTheHandedOverRequest() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 185_000) // 15 s left: the first tick hands R1 over
        val controller = controller(player)
        controller.enqueue(r1)
        advanceTimeBy(5_000)
        controller.start()
        runCurrent()
        assertEquals(r1, controller.handedOver.value)
        assertEquals(5_000L, store().load().handedOverAtEpochMs)

        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        val saved = store().load()
        assertEquals(r1, saved.playing)
        assertNull(saved.handedOver)
        assertNull(saved.handedOverAtEpochMs)
    }

    @Test
    fun editsShowAtOnceWhileRecentlyPlayedIsRead() = runTest {
        val player = fakePlayer()
        reopenAfterAway(player)
        player.recentSinceResult = SpotifyResult.Ok(listOf(RecentTrack(r1.track, playedAtEpochMs = 300_000)))
        val gate = CompletableDeferred<Unit>()
        player.recentSinceGate = gate
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(1, player.callsTo("recentlyPlayedSince").size)

        controller.enqueue(r2)
        runCurrent()
        assertEquals(listOf(r2), controller.queue.value.items, "the read holds no state lock")
        assertEquals(listOf(r2), store().load().queue.items)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(r1, r0), controller.history.value.map { it.request })
        assertEquals(listOf(r2), controller.queue.value.items)
    }

    @Test
    fun reopeningMidSongHandsOverBeforeSpotifysOwnNext() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 188_000) // 12 s left
        val open = MutableStateFlow(false)
        val controller = controller(player, open)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertTrue(player.callsTo("addToQueue").isEmpty(), "closed: nothing goes over")

        advance(500)
        open.value = true
        runCurrent()
        assertEquals(listOf(r1.track.uri to 500L), player.callsTo("addToQueue").map { it.arg to it.atMs })
        assertEquals(r1, controller.handedOver.value)
    }
}
