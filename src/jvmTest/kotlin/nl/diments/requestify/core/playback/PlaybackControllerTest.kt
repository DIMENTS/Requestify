package nl.diments.requestify.core.playback

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.spotify.CurrentlyPlaying
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class PlaybackControllerTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-playback") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() =
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })

    /** Every track is 200 s long. */
    private fun track(id: String) = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null)

    private fun request(id: String, track: Track = track("t-$id"), payment: Payment? = null) =
        Request(id, track, Requester("Viewer $id"), RequestSource.CHAT, payment, createdAtEpochMs = 1L)

    private val trackA = track("A")
    private val trackB = track("B")
    private val trackC = track("C")
    private val r1 = request("r1")

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

    /** Track A has 15 s left, so the first tick hands R1 over. */
    private fun TestScope.startWithR1HandedOver(player: FakeSpotifyPlayer): PlaybackController {
        player.plays(trackA, progressMs = 185_000)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg })
        return controller
    }

    /** Moves virtual time forward by [ms] and runs everything that is due at the new time. */
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun FakeSpotifyPlayer.pollTimes() = callsTo("currentlyPlaying").map { it.atMs }

    @Test
    fun handsOverHeadWithin15Seconds() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 184_000)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertTrue(player.callsTo("addToQueue").isEmpty())

        player.plays(trackA, progressMs = 185_000)
        advance(1_000)
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg })
        assertTrue(controller.queue.value.items.isEmpty())

        advance(5_000)
        assertEquals(1, player.callsTo("addToQueue").size)
    }

    @Test
    fun noHandoverWhenRequestsClosed() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 195_000)
        val controller = controller(player, requestsOpen = MutableStateFlow(false))
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        advance(3_000)
        assertTrue(player.callsTo("addToQueue").isEmpty())
        assertEquals(r1, controller.queue.value.head)
    }

    @Test
    fun handedOverBecomesNowPlayingWithRequester() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertEquals(r1, controller.nowPlaying.value?.request)
        val saved = store().load()
        assertEquals(r1, saved.playing)
        assertNull(saved.handedOver)
    }

    @Test
    fun finishedRequestGoesToHistory() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        player.plays(trackB, progressMs = 0)
        advance(3_000)
        assertEquals(r1, controller.history.value.first().request)
        assertEquals(trackB, controller.nowPlaying.value?.track)
        assertNull(controller.nowPlaying.value?.request)
        val saved = store().load()
        assertEquals(r1, saved.history.first().request)
        assertNull(saved.playing)
    }

    @Test
    fun noActiveDeviceKeepsRequestAndRetries() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 186_000)
        player.addToQueueResults.add(SpotifyResult.NoActiveDevice)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)
        assertEquals(r1, controller.queue.value.head)

        advance(1_000)
        assertEquals(2, player.callsTo("addToQueue").size)
        assertTrue(controller.queue.value.items.isEmpty())
        assertEquals(r1, store().load().handedOver)
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun handedOverRequestMissingFromSpotifyQueueReturnsToHead() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        player.userQueueResult = SpotifyResult.Ok(emptyList())
        player.plays(trackC, progressMs = 0)
        advance(1_000)
        assertEquals(r1, controller.queue.value.head)
        assertNull(store().load().handedOver)
    }

    @Test
    fun handedOverStillInSpotifyQueueStaysHandedOver() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        player.userQueueResult = SpotifyResult.Ok(listOf(r1.track.id))
        player.plays(trackC, progressMs = 0)
        advance(1_000)
        assertTrue(controller.queue.value.items.isEmpty())
        assertEquals(r1, store().load().handedOver)
    }

    @Test
    fun rateLimitedWaitsRetryAfter() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.RateLimited(7_000)
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.RateLimited(untilEpochMs = 7_000), controller.health.value)

        controller.enqueue(r1) // a wake-up must not cut the wait short
        advance(6_999)
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 7_000L), player.pollTimes())
    }

    @Test
    fun restoresHandedOverRequestAsNowPlaying() = runTest {
        store().save(PersistedPlayback(handedOver = r1))
        val player = fakePlayer()
        player.plays(r1.track, progressMs = 5_000)
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(r1, controller.nowPlaying.value?.request)
        assertEquals(r1, store().load().playing)
    }

    @Test
    fun skipHandsOverHeadThenSkips() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()

        controller.skip()
        assertEquals(
            listOf("addToQueue" to r1.track.uri, "skipNext" to null),
            player.calls.filter { it.name != "currentlyPlaying" }.map { it.name to it.arg },
        )
        assertTrue(controller.queue.value.items.isEmpty())
        assertEquals(r1, store().load().handedOver)
    }

    @Test
    fun sameTrackTwiceOnlyFirstIsPlaying() = runTest {
        val shared = track("S")
        val first = request("r1", shared)
        val second = request("r2", shared)
        val player = fakePlayer()
        player.plays(trackA, progressMs = 185_000)
        val controller = controller(player)
        controller.enqueue(first)
        controller.enqueue(second)
        controller.start()
        runCurrent()

        player.plays(shared, progressMs = 0)
        advance(1_000)
        assertEquals(first, controller.nowPlaying.value?.request)
        assertEquals(listOf(second), controller.queue.value.items)

        // Near its end: handing the same track over now could not be told apart from this play.
        player.plays(shared, progressMs = 190_000)
        advance(3_000)
        assertEquals(listOf(second), controller.queue.value.items)
        assertEquals(first, controller.nowPlaying.value?.request)

        // Once another track plays, the second request goes over as usual.
        player.plays(trackB, progressMs = 190_000)
        advance(1_000)
        assertEquals(listOf(first.track.uri, second.track.uri), player.callsTo("addToQueue").map { it.arg })
        assertEquals(first, controller.history.value.first().request)
    }

    @Test
    fun pollsFasterNearEnd() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 170_000) // 30 s left
        val controller = controller(player)
        controller.start()
        runCurrent()

        player.plays(trackA, progressMs = 181_000) // 19 s left
        advance(2_999)
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 3_000L), player.pollTimes())
        advance(999)
        assertEquals(listOf(0L, 3_000L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 3_000L, 4_000L), player.pollTimes())
    }

    @Test
    fun healthFollowsEverySpotifyResultAndKeepsTheQueue() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.Failed("HTTP 500")
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.Unreachable("HTTP 500"), controller.health.value)

        val steps = listOf<Pair<SpotifyResult<CurrentlyPlaying?>, SpotifyHealth>>(
            SpotifyResult.NoActiveDevice to SpotifyHealth.NoActiveDevice,
            SpotifyResult.LoginNeeded to SpotifyHealth.LoginNeeded,
            SpotifyResult.PremiumRequired to SpotifyHealth.PremiumRequired,
            SpotifyResult.Ok(null) to SpotifyHealth.NothingPlaying,
            SpotifyResult.Ok(CurrentlyPlaying(track = null, progressMs = 0, isPlaying = true)) to SpotifyHealth.Ok,
        )
        for ((result, expected) in steps) {
            player.currentlyPlayingResult = result
            advance(3_000)
            assertEquals(expected, controller.health.value)
        }
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L, 15_000L), player.pollTimes())
        assertEquals(listOf(r1), controller.queue.value.items)
    }

    @Test
    fun loopSurvivesAnException() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingError = IllegalStateException("boom")
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.Unreachable("boom"), controller.health.value)

        player.currentlyPlayingError = null
        player.plays(trackA, progressMs = 0)
        advance(3_000)
        assertEquals(SpotifyHealth.Ok, controller.health.value)
        assertEquals(trackA, controller.nowPlaying.value?.track)
    }

    @Test
    fun failedSpotifyQueueCheckIsRetriedNextTick() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        player.userQueueResult = SpotifyResult.Failed("timeout")
        player.plays(trackC, progressMs = 0)
        advance(1_000)
        assertEquals(SpotifyHealth.Unreachable("timeout"), controller.health.value)
        assertEquals(r1, store().load().handedOver)

        player.userQueueResult = SpotifyResult.Ok(emptyList())
        advance(3_000)
        assertEquals(r1, controller.queue.value.head)
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun enqueueWakesTheLoop() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 190_000)
        val controller = controller(player)
        controller.start()
        runCurrent()

        advance(500)
        controller.enqueue(r1)
        runCurrent()
        assertEquals(listOf(500L), player.callsTo("addToQueue").map { it.atMs })
    }

    @Test
    fun openingRequestsWakesTheLoop() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 190_000)
        val open = MutableStateFlow(false)
        val controller = controller(player, open)
        controller.enqueue(r1)
        controller.start()
        runCurrent()

        advance(500)
        open.value = true
        runCurrent()
        assertEquals(listOf(500L), player.callsTo("addToQueue").map { it.atMs })
    }

    @Test
    fun skipDoesNotHandOverTheTrackAlreadyPlaying() = runTest {
        val player = fakePlayer()
        val again = request("again", trackA)
        player.plays(trackA, progressMs = 10_000)
        val controller = controller(player)
        controller.enqueue(again)
        controller.start()
        runCurrent()

        controller.skip()
        assertEquals(listOf("skipNext"), player.calls.map { it.name }.filter { it != "currentlyPlaying" })
        assertEquals(listOf(again), controller.queue.value.items)
    }

    @Test
    fun togglePausePausesThenResumes() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = controller(player)
        controller.start()
        runCurrent()

        controller.togglePause()
        runCurrent()
        assertEquals(false, controller.nowPlaying.value?.isPlaying)
        controller.togglePause()
        runCurrent()
        assertEquals(true, controller.nowPlaying.value?.isPlaying)
        assertEquals(listOf("pause", "resume"), player.calls.map { it.name }.filter { it == "pause" || it == "resume" })
    }

    @Test
    fun queueEditsArePersistedAndRestored() = runTest {
        val player = fakePlayer()
        val r2 = request("r2")
        val paid = request("paid", payment = Payment.Bits(500))
        val first = controller(player)
        first.enqueue(r1)
        first.enqueue(r2)
        first.enqueue(paid) // paid priority: goes before the free requests
        runCurrent()
        assertEquals(listOf(paid, r1, r2), first.queue.value.items)
        first.move("r2", 0)
        first.remove("r1")
        runCurrent()
        assertEquals(listOf(r2, paid), first.queue.value.items)

        val restarted = controller(player)
        restarted.start()
        runCurrent()
        assertEquals(listOf(r2, paid), restarted.queue.value.items)
    }

    @Test
    fun queueStaysEditableWhileSpotifyIsSlow() = runTest {
        val player = fakePlayer()
        val gate = CompletableDeferred<Unit>()
        player.addToQueueGate = gate
        player.plays(trackA, progressMs = 190_000)
        val r2 = request("r2")
        val r3 = request("r3")
        val controller = controller(player)
        controller.enqueue(r1)
        controller.enqueue(r2)
        controller.start()
        runCurrent()
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg }) // still in flight

        controller.enqueue(r3)
        controller.remove("r1")
        controller.move("r3", 0)
        runCurrent()
        assertEquals(listOf(r3, r2), controller.queue.value.items)
        assertEquals(listOf(r3, r2), store().load().queue.items)

        gate.complete(Unit) // Spotify has R1, so it counts as handed over even though it was removed meanwhile
        runCurrent()
        assertEquals(listOf(r3, r2), controller.queue.value.items)
        val saved = store().load()
        assertEquals(r1, saved.handedOver)
        assertEquals(listOf(r3, r2), saved.queue.items)
    }

    @Test
    fun healthIsPublishedOncePerTick() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 186_000)
        player.addToQueueResults.add(SpotifyResult.NoActiveDevice)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)

        val gate = CompletableDeferred<Unit>()
        player.addToQueueGate = gate
        advance(1_000) // the retry is in flight: no brief "Ok" before Spotify answers
        assertEquals(2, player.callsTo("addToQueue").size)
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun failedSaveIsReportedAndRetriedByTheNextTick() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.Failed("HTTP 503")
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertNull(controller.persistError.value)

        val blocker = dir.resolve("queue.json.tmp").createDirectory() // the store cannot write its temp file
        controller.enqueue(r1)
        runCurrent()
        assertNotNull(controller.persistError.value)
        assertEquals(listOf(r1), controller.queue.value.items)
        assertEquals(PersistedPlayback(), store().load())

        blocker.deleteExisting()
        advance(3_000) // this tick fails at currentlyPlaying, yet still retries the save
        assertNull(controller.persistError.value)
        assertEquals(listOf(r1), store().load().queue.items)
    }

    @Test
    fun rateLimitedWithZeroRetryAfterStillWaits() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.RateLimited(0)
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.RateLimited(untilEpochMs = 1_000), controller.health.value)
        advance(999)
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 1_000L), player.pollTimes())
    }

    @Test
    fun historyKeepsThe50Newest() = runTest {
        val older = (1..50).map { PlayedEntry(request("old$it"), playedAtEpochMs = it.toLong()) }.reversed()
        store().save(PersistedPlayback(playing = r1, history = older))
        val player = fakePlayer()
        player.plays(trackB, progressMs = 0)
        val controller = controller(player)
        advanceTimeBy(1_000) // R1 ends after every older entry
        controller.start()
        runCurrent()
        val history = controller.history.value
        assertEquals(50, history.size)
        assertEquals(r1, history.first().request)
        assertEquals(history, store().load().history)
    }
}
