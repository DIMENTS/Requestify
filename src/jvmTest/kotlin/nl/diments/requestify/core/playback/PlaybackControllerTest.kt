package nl.diments.requestify.core.playback

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.LastPlayed
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.spotify.CurrentlyPlaying
import nl.diments.requestify.core.spotify.RecentTrack
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

    /** Like [store], but a read that fails is not tried again (no real sleeps in virtual-time tests). */
    private fun storeWithoutReadRetries() = QueueStore(
        JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer(), retryDelaysMs = emptyList()) { PersistedPlayback() },
    )

    /** Another program holds queue.json and denies reading it (a Windows sharing violation). */
    private fun lockQueueFile(): SeekableByteChannel =
        Files.newByteChannel(dir.resolve("queue.json"), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)

    private fun TestScope.controller(
        player: FakeSpotifyPlayer,
        requestsOpen: MutableStateFlow<Boolean> = MutableStateFlow(true),
        store: QueueStore = store(),
    ) = PlaybackController(
        player = player,
        store = store,
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

    private fun FakeSpotifyPlayer.playCommands() = calls.map { it.name }.filter { it == "pause" || it == "resume" }

    /** Polls, queue checks and refreshes, recently played. */
    private val reads =
        setOf("currentlyPlaying", "userQueueTrackIds", "userQueue", "recentlyPlayed", "recentlyPlayedSince")

    /** What was sent to Spotify, without the [reads]. */
    private fun FakeSpotifyPlayer.commands() = calls.filter { it.name !in reads }

    /** Spotify plays (or has paused) track A at 60 s, and the first poll has shown it. */
    private fun TestScope.startAt60s(player: FakeSpotifyPlayer, isPlaying: Boolean = true): PlaybackController {
        player.plays(trackA, progressMs = 60_000, isPlaying = isPlaying)
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(isPlaying, controller.nowPlaying.value?.isPlaying)
        return controller
    }

    /** Presses play/pause the way Home does, on a coroutine of its own, so a slow Spotify does not hold the test. */
    private fun TestScope.click(controller: PlaybackController) {
        launch { controller.togglePause() }
        runCurrent()
    }

    private fun PlaybackController.shownPlaying() = nowPlaying.value?.isPlaying

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
    fun handedOverRequestIsExposedUntilItPlays() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        assertEquals(r1, controller.handedOver.value)
        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertNull(controller.handedOver.value)
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
            player.commands().map { it.name to it.arg },
        )
        assertTrue(controller.queue.value.items.isEmpty())
        assertEquals(r1, store().load().handedOver)
    }

    @Test
    fun skipIfPlayingSkipsOnlyTheTrackThatStillPlays() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = controller(player)
        controller.start()
        runCurrent()

        assertFalse(controller.skipIfPlaying("another-track"), "the song moved on meanwhile: nothing is skipped")
        assertTrue(player.callsTo("skipNext").isEmpty())

        assertTrue(controller.skipIfPlaying(trackA.id))
        assertEquals(1, player.callsTo("skipNext").size)
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
            SpotifyResult.LoggedOut to SpotifyHealth.LoggedOut,
            SpotifyResult.PremiumRequired to SpotifyHealth.PremiumRequired,
            SpotifyResult.Ok(null) to SpotifyHealth.NothingPlaying,
            SpotifyResult.Ok(CurrentlyPlaying(track = null, progressMs = 0, isPlaying = true)) to SpotifyHealth.Ok,
        )
        for ((result, expected) in steps) {
            player.currentlyPlayingResult = result
            advance(3_000)
            assertEquals(expected, controller.health.value)
        }
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L, 15_000L, 18_000L), player.pollTimes())
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
        assertEquals(listOf("skipNext"), player.commands().map { it.name })
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
    fun pausePublishesPausedBeforeSpotifyAnswers() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        val gate = CompletableDeferred<Unit>()
        player.playCommandGate = gate

        click(controller)
        assertEquals(listOf("pause"), player.playCommands()) // still in flight
        assertEquals(false, controller.shownPlaying())

        gate.complete(Unit)
        runCurrent()
        assertEquals(false, controller.shownPlaying())
    }

    @Test
    fun pauseIsShownImmediatelyEvenWhileATickIsInFlight() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        val gate = CompletableDeferred<Unit>()
        player.currentlyPlayingGate = gate
        advance(3_000)
        assertEquals(listOf(0L, 3_000L), player.pollTimes()) // the second poll is still in flight

        click(controller)
        assertEquals(false, controller.shownPlaying())
        assertTrue(player.playCommands().isEmpty(), "the pause waits for the poll")

        gate.complete(Unit) // the poll answers from before the pause: Spotify still plays
        runCurrent()
        assertEquals(listOf("pause"), player.playCommands())
        assertEquals(false, controller.shownPlaying())
    }

    @Test
    fun staleSpotifyStateAfterPauseDoesNotResumeTheBar() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        player.stalePollsAfterPlayCommand = 2

        controller.togglePause()
        runCurrent() // polled again at once: Spotify still says playing
        assertEquals(false, controller.shownPlaying())
        advance(1_000) // still says playing
        assertEquals(false, controller.shownPlaying())
        advance(1_000) // says paused now
        assertEquals(false, controller.shownPlaying())
        assertEquals(listOf(0L, 0L, 1_000L, 2_000L), player.pollTimes())
    }

    @Test
    fun intentExpiresWhenSpotifyNeverConfirms() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        player.stalePollsAfterPlayCommand = Int.MAX_VALUE // Spotify keeps playing whatever it answered
        val settleMs = PlaybackConfig().playIntentSettleMs

        controller.togglePause()
        runCurrent()
        advance(settleMs - 1_000)
        assertEquals(false, controller.shownPlaying())
        advance(1_000) // the intent has expired: Spotify is trusted again
        assertEquals(true, controller.shownPlaying())
    }

    @Test
    fun failedPauseRevertsAndReportsHealth() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        player.playCommandResults.add(SpotifyResult.NoActiveDevice)
        val gate = CompletableDeferred<Unit>()
        player.playCommandGate = gate
        click(controller)
        assertEquals(false, controller.shownPlaying())

        player.currentlyPlayingGate = CompletableDeferred() // holds the poll that follows, so only the pause answers
        gate.complete(Unit)
        runCurrent()
        assertEquals(true, controller.shownPlaying())
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)
    }

    @Test
    fun pauseFreezesPositionWhereItWas() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        player.plays(trackA, progressMs = 63_000)
        advance(3_000) // Spotify samples 63 s at 3 s
        player.stalePollsAfterPlayCommand = 2
        advance(2_000)

        controller.togglePause() // at 5 s, so at 65 s into the track
        fun assertFrozenAt65s() {
            val shown = assertNotNull(controller.nowPlaying.value)
            assertEquals(false, shown.isPlaying)
            assertEquals(65_000, shown.progressMs)
            assertEquals(65_000, shown.positionAt(testScheduler.currentTime))
        }
        assertFrozenAt65s()
        runCurrent() // Spotify still says playing at 63 s
        assertFrozenAt65s()
        advance(1_000) // and again
        assertFrozenAt65s()
        player.plays(trackA, progressMs = 65_000, isPlaying = false)
        advance(1_000) // Spotify confirms
        assertFrozenAt65s()
    }

    @Test
    fun resumeIsShownImmediately() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player, isPlaying = false)
        player.stalePollsAfterPlayCommand = 2
        val gate = CompletableDeferred<Unit>()
        player.playCommandGate = gate

        click(controller)
        assertEquals(listOf("resume"), player.playCommands()) // still in flight
        assertEquals(true, controller.shownPlaying())

        gate.complete(Unit)
        runCurrent() // polled again at once: Spotify still says paused at 60 s
        assertEquals(true, controller.shownPlaying())
        advance(1_000) // and again: the bar runs on from where it resumed
        val shown = assertNotNull(controller.nowPlaying.value)
        assertEquals(true, shown.isPlaying)
        assertEquals(61_000, shown.positionAt(testScheduler.currentTime))
    }

    @Test
    fun quickClicksToggleWhatIsShownAndReachSpotifyInOrder() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        val gate = CompletableDeferred<Unit>()
        player.playCommandGate = gate

        click(controller)
        assertEquals(false, controller.shownPlaying())
        click(controller) // before Spotify answered the pause
        assertEquals(true, controller.shownPlaying())
        assertEquals(listOf("pause"), player.playCommands())

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("pause", "resume"), player.playCommands())
        assertEquals(true, controller.shownPlaying())
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
        controller.remove("r1") // being handed over: the removal waits for Spotify's answer
        controller.move("r3", 0)
        runCurrent()
        assertEquals(listOf(r3, r1, r2), controller.queue.value.items)
        assertEquals(listOf(r3, r1, r2), store().load().queue.items)

        gate.complete(Unit) // Spotify has R1: it is handed over, and the removal asked for meanwhile is dropped
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
    fun revertingToTheSavedQueueClearsTheSaveError() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.Failed("HTTP 503")
        val controller = controller(player)
        controller.start()
        runCurrent()

        dir.resolve("queue.json.tmp").createDirectory() // the store cannot write its temp file
        controller.enqueue(r1)
        runCurrent()
        assertNotNull(controller.persistError.value)
        controller.remove("r1") // back to exactly what is saved: nothing is unsaved any more
        runCurrent()
        assertNull(controller.persistError.value)
    }

    @Test
    fun aRequestIdThatIsQueuedHandedOverOrPlayingIsNotAddedAgain() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        val r2 = request("r2")
        controller.enqueue(r1) // handed over
        controller.enqueue(r2)
        controller.enqueue(r2) // queued
        runCurrent()
        assertEquals(listOf(r2), controller.queue.value.items)
        assertEquals(r1, controller.handedOver.value)

        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertEquals(r1, controller.nowPlaying.value?.request)
        controller.enqueue(r1) // playing
        runCurrent()
        assertEquals(listOf(r2), controller.queue.value.items)
        assertEquals(listOf(r2), store().load().queue.items)
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
    fun unreadableQueueIsNeitherOverwrittenNorHandedOverAndLoadsOnceReadable() = runTest {
        val r2 = request("r2")
        store().save(PersistedPlayback(queue = RequestQueue(listOf(r1))))
        val text = dir.resolve("queue.json").readText()
        val player = fakePlayer()
        player.plays(trackA, progressMs = 190_000) // a hand-over would be due at once
        val controller = controller(player, store = storeWithoutReadRetries())

        val lock = lockQueueFile()
        controller.start()
        controller.enqueue(r2)
        runCurrent()
        assertNotNull(controller.loadError.value)
        assertTrue(controller.queue.value.items.isEmpty())
        advance(3_000) // tried again on the next tick: still held
        assertNotNull(controller.loadError.value)
        assertTrue(player.calls.isEmpty(), "nothing is handed over without the saved queue")
        lock.close()
        assertEquals(text, dir.resolve("queue.json").readText(), "the saved queue is untouched")

        advance(3_000)
        assertNull(controller.loadError.value)
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg })
        assertEquals(listOf(r2), controller.queue.value.items, "the edit made meanwhile is kept")
        val saved = store().load()
        assertEquals(r1, saved.handedOver)
        assertEquals(listOf(r2), saved.queue.items)
    }

    @Test
    fun skipStillSkipsWhileTheQueueCannotBeRead() = runTest {
        store().save(PersistedPlayback(queue = RequestQueue(listOf(r1))))
        val player = fakePlayer()
        val controller = controller(player, store = storeWithoutReadRetries())
        lockQueueFile().use {
            controller.skip()
            controller.togglePause()
        }
        assertEquals(listOf("skipNext", "resume"), player.calls.map { it.name })
        assertNotNull(controller.loadError.value)
        assertEquals(listOf(r1), store().load().queue.items)
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

    private val lastPlayedA = LastPlayed(trackA, progressMs = 42_000, playedAtEpochMs = 1_000)

    /** Spotify has nothing active, and the saved state remembers [saved] (by default track A at 42 s). */
    private fun TestScope.startIdleWithLastPlayedA(
        player: FakeSpotifyPlayer,
        saved: LastPlayed = lastPlayedA,
    ): PlaybackController {
        store().save(PersistedPlayback(lastPlayed = saved))
        val controller = controller(player)
        controller.start()
        runCurrent()
        return controller
    }

    /**
     * The last played card shows [track] paused at [progressMs], asked for by [request] (null: the streamer's own
     * music), or with [requesterKnown] false for Spotify's own recently played song, of which that is unknown.
     */
    private fun PlaybackController.assertShowsLastPlayed(
        track: Track,
        progressMs: Long,
        request: Request? = null,
        requesterKnown: Boolean = true,
    ) {
        val shown = assertNotNull(nowPlaying.value)
        assertEquals(NowPlayingKind.LastPlayed, shown.kind)
        assertEquals(track, shown.track)
        assertEquals(progressMs, shown.progressMs)
        assertFalse(shown.isPlaying)
        assertEquals(request, shown.request)
        assertEquals(requesterKnown, shown.requesterKnown)
    }

    @Test
    fun previousCallsSpotifyAndRefreshesUpcoming() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        assertEquals(listOf(0L), player.callsTo("userQueue").map { it.atMs })

        advance(1_000)
        controller.previous()
        runCurrent()
        assertEquals(listOf(1_000L), player.callsTo("skipPrevious").map { it.atMs })
        assertEquals(listOf(0L, 1_000L), player.callsTo("userQueue").map { it.atMs })
        val names = player.calls.map { it.name }
        assertTrue(names.lastIndexOf("userQueue") > names.indexOf("skipPrevious"), "refreshed after the previous")
    }

    @Test
    fun skipRefreshesUpcoming() = runTest {
        val player = fakePlayer()
        val controller = startAt60s(player)
        advance(1_000)
        controller.skip()
        runCurrent() // Spotify still reports the same track, yet its queue has moved on
        assertEquals(listOf(0L, 1_000L), player.callsTo("userQueue").map { it.atMs })
    }

    @Test
    fun previousWhileARequestPlaysMovesItToHistory() = runTest {
        val player = fakePlayer()
        val r2 = request("r2")
        val r3 = request("r3")
        val controller = startWithR1HandedOver(player)
        controller.enqueue(r2)
        controller.enqueue(r3)
        player.plays(r1.track, progressMs = 190_000) // R1 plays, near its end: R2 goes over
        advance(1_000)
        assertEquals(r1, controller.nowPlaying.value?.request)
        assertEquals(r2, controller.handedOver.value)

        player.plays(trackB, progressMs = 0) // Spotify went back to the song before R1
        player.userQueueResult = SpotifyResult.Ok(listOf(r2.track.id)) // and still has R2 next
        controller.previous()
        runCurrent()
        assertEquals(trackB, controller.nowPlaying.value?.track)
        assertNull(controller.nowPlaying.value?.request)
        assertEquals(r1, controller.history.value.first().request)
        assertEquals(listOf(r3), controller.queue.value.items)
        assertEquals(r2, controller.handedOver.value)
        assertEquals(
            listOf(r1.track.uri, r2.track.uri),
            player.callsTo("addToQueue").map { it.arg },
            "nothing is queued again",
        )
        val saved = store().load()
        assertEquals(r1, saved.history.first().request)
        assertEquals(listOf(r3), saved.queue.items)
        assertEquals(r2, saved.handedOver)
    }

    @Test
    fun upcomingIsDistinctCappedAndSkipsHandedOver() = runTest {
        val player = fakePlayer()
        val r2 = request("r2")
        val tracks = (1..20).map { track("T$it") }
        player.queueTracks = SpotifyResult.Ok(listOf(r2.track, tracks[0], tracks[1], tracks[0]) + tracks.drop(2))
        player.plays(trackA, progressMs = 185_000)
        val controller = controller(player)
        controller.enqueue(r2)
        controller.start()
        runCurrent()
        assertEquals(r2, controller.handedOver.value)
        assertEquals((1..15).map { "T$it" }, controller.upcoming.value.map { it.id })
    }

    @Test
    fun upcomingRefreshesOnTrackChangeAndEvery30s() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = controller(player)
        controller.start()
        runCurrent()
        advance(8_000) // polls at 3 and 6 s: the same track
        player.plays(trackB, progressMs = 0)
        advance(1_000) // the poll at 9 s sees another track
        advance(29_999) // ordinary polls, every 3 s
        assertEquals(listOf(0L, 9_000L), player.callsTo("userQueue").map { it.atMs })
        advance(1)
        assertEquals(listOf(0L, 9_000L, 39_000L), player.callsTo("userQueue").map { it.atMs })
        assertEquals((0L..39_000L step 3_000).toList(), player.pollTimes())
        assertEquals(trackB, controller.nowPlaying.value?.track)
    }

    @Test
    fun failedUpcomingRefreshKeepsTheLastList() = runTest {
        val player = fakePlayer()
        player.queueTracks = SpotifyResult.Ok(listOf(trackB, trackC))
        val controller = startAt60s(player)
        assertEquals(listOf(trackB, trackC), controller.upcoming.value)

        player.queueTracks = SpotifyResult.Failed("HTTP 500")
        advance(PlaybackConfig().upcomingRefreshMs)
        assertEquals(2, player.callsTo("userQueue").size)
        assertEquals(listOf(trackB, trackC), controller.upcoming.value)
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun nothingActiveClearsUpcoming() = runTest {
        val player = fakePlayer()
        player.queueTracks = SpotifyResult.Ok(listOf(trackB))
        val controller = startAt60s(player)
        assertEquals(listOf(trackB), controller.upcoming.value)

        player.currentlyPlayingResult = SpotifyResult.Ok(null)
        advance(3_000)
        assertEquals(emptyList(), controller.upcoming.value)
        assertEquals(1, player.callsTo("userQueue").size, "Spotify's queue is not asked while nothing is active")
    }

    @Test
    fun startupShowsLocalLastPlayedBeforeAnyPoll() = runTest {
        store().save(PersistedPlayback(lastPlayed = lastPlayedA))
        val player = fakePlayer()
        player.plays(trackB, progressMs = 5_000)
        val gate = CompletableDeferred<Unit>()
        player.currentlyPlayingGate = gate
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(listOf(0L), player.pollTimes()) // Spotify has not answered the first poll yet
        controller.assertShowsLastPlayed(trackA, progressMs = 42_000)

        gate.complete(Unit) // a live poll replaces it
        runCurrent()
        val shown = assertNotNull(controller.nowPlaying.value)
        assertEquals(NowPlayingKind.Live, shown.kind)
        assertEquals(trackB, shown.track)
    }

    @Test
    fun lastPlayedIsPersistedOnChangesAndEvery30sWhilePlaying() = runTest {
        val serializer = CountingSerializer(PersistedPlayback.serializer())
        val counted = QueueStore(JsonFileStore(dir.resolve("queue.json"), serializer) { PersistedPlayback() })
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = controller(player, store = counted)
        controller.start()
        runCurrent()
        assertEquals(1, serializer.writes, "the first poll saves what plays")
        assertEquals(LastPlayed(trackA, 10_000, playedAtEpochMs = 0), store().load().lastPlayed)

        for (poll in 1..9) {
            player.plays(trackA, progressMs = 10_000L + poll * 3_000)
            advance(3_000)
        }
        assertEquals(1, serializer.writes, "no save on every poll while the same track plays on")

        player.plays(trackA, progressMs = 40_000)
        advance(3_000) // 30 s since the last save: where it is now is saved, once
        assertEquals(2, serializer.writes)
        assertEquals(LastPlayed(trackA, 40_000, playedAtEpochMs = 30_000), store().load().lastPlayed)

        player.plays(trackA, progressMs = 41_000, isPlaying = false)
        advance(3_000) // 33 s: paused
        assertEquals(3, serializer.writes)
        assertEquals(LastPlayed(trackA, 41_000, playedAtEpochMs = 33_000), store().load().lastPlayed)
        advance(36_000) // paused for longer than 30 s: the position does not move, so nothing is saved
        assertEquals(3, serializer.writes)

        player.plays(trackB, progressMs = 0)
        advance(3_000) // 72 s: another track
        assertEquals(4, serializer.writes)
        assertEquals(LastPlayed(trackB, 0, playedAtEpochMs = 72_000), store().load().lastPlayed)

        player.currentlyPlayingResult = SpotifyResult.Ok(null)
        advance(3_000) // 75 s: nothing is active any more, after B ran on for 3 s
        assertEquals(5, serializer.writes)
        assertEquals(LastPlayed(trackB, 3_000, playedAtEpochMs = 75_000), store().load().lastPlayed)
        advance(39_000)
        assertEquals(5, serializer.writes)
        controller.assertShowsLastPlayed(trackB, progressMs = 3_000)
    }

    @Test
    fun aRequestPlayingWhenSpotifyGoesIdleIsShownAndSavedWithItsRequester() = runTest {
        val player = fakePlayer()
        val controller = startWithR1HandedOver(player)
        player.plays(r1.track, progressMs = 1_000)
        advance(1_000)
        assertEquals(r1, controller.nowPlaying.value?.request)

        player.currentlyPlayingResult = SpotifyResult.Ok(null)
        advance(3_000) // 4 s: nothing is active any more, after R1 ran on for 3 s
        controller.assertShowsLastPlayed(r1.track, progressMs = 4_000, request = r1)
        assertEquals(LastPlayed(r1.track, 4_000, playedAtEpochMs = 4_000, request = r1), store().load().lastPlayed)

        controller.stop()
        val restarted = controller(player)
        restarted.start()
        runCurrent()
        restarted.assertShowsLastPlayed(r1.track, progressMs = 4_000, request = r1)
    }

    @Test
    fun newerSpotifyRecentWinsOverLocal() = runTest {
        val player = fakePlayer()
        player.recentResult = SpotifyResult.Ok(RecentTrack(trackB, playedAtEpochMs = 2_000))
        val controller = startIdleWithLastPlayedA(player)
        controller.assertShowsLastPlayed(trackB, progressMs = 0, requesterKnown = false)
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)
    }

    @Test
    fun spotifysRecentSongNeitherTakesTheSavedRequesterNorClaimsOwnMusic() = runTest {
        val player = fakePlayer()
        player.recentResult = SpotifyResult.Ok(RecentTrack(trackB, playedAtEpochMs = 2_000))
        val forA = request("forA", trackA)
        val controller = startIdleWithLastPlayedA(player, saved = lastPlayedA.copy(request = forA))
        controller.assertShowsLastPlayed(trackB, progressMs = 0, request = null, requesterKnown = false)
    }

    @Test
    fun spotifysCopyOfTheSavedSongKeepsItsPositionAndRequester() = runTest {
        val player = fakePlayer()
        // The same play of A, as Spotify logged it once it ended.
        player.recentResult = SpotifyResult.Ok(RecentTrack(trackA, playedAtEpochMs = 2_000))
        val forA = request("forA", trackA)
        val controller = startIdleWithLastPlayedA(player, saved = lastPlayedA.copy(request = forA))
        assertEquals(listOf(0L), player.callsTo("recentlyPlayed").map { it.atMs })
        controller.assertShowsLastPlayed(trackA, progressMs = 42_000, request = forA)
    }

    @Test
    fun olderSpotifyRecentLosesToLocal() = runTest {
        val player = fakePlayer()
        player.recentResult = SpotifyResult.Ok(RecentTrack(trackB, playedAtEpochMs = 500))
        val controller = startIdleWithLastPlayedA(player)
        assertEquals(listOf(0L), player.callsTo("recentlyPlayed").map { it.atMs })
        controller.assertShowsLastPlayed(trackA, progressMs = 42_000)
    }

    @Test
    fun recentlyPlayedIsAskedAtMostOnceAMinuteAndOnlyWhileNothingIsActive() = runTest {
        val player = fakePlayer()
        startIdleWithLastPlayedA(player)
        advance(150_000)
        assertEquals(listOf(0L, 60_000L, 120_000L), player.callsTo("recentlyPlayed").map { it.atMs })

        player.plays(trackB)
        advance(120_000)
        assertEquals(3, player.callsTo("recentlyPlayed").size)
    }

    @Test
    fun missingPermissionFallsBackToLocalAndStopsAsking() = runTest {
        val player = fakePlayer()
        player.recentResult = SpotifyResult.MissingPermission
        val controller = startIdleWithLastPlayedA(player)
        assertTrue(controller.needsExtraPermission.value)
        controller.assertShowsLastPlayed(trackA, progressMs = 42_000)
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)

        advance(5 * 60_000)
        assertEquals(listOf(0L), player.callsTo("recentlyPlayed").map { it.atMs })

        player.recentResult = SpotifyResult.Ok(RecentTrack(trackB, playedAtEpochMs = 2_000))
        controller.onLoginChanged()
        runCurrent()
        assertFalse(controller.needsExtraPermission.value)
        advance(3_000) // the next idle tick asks again
        assertEquals(listOf(0L, 303_000L), player.callsTo("recentlyPlayed").map { it.atMs })
        controller.assertShowsLastPlayed(trackB, progressMs = 0, requesterKnown = false)
    }

    @Test
    fun aMissingPermissionAnsweredForTheEarlierLoginIsIgnored() = runTest {
        val player = fakePlayer()
        player.recentResult = SpotifyResult.MissingPermission
        val gate = CompletableDeferred<Unit>()
        player.recentGate = gate
        val controller = startIdleWithLastPlayedA(player) // asked under the old login, still in flight

        controller.onLoginChanged()
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.needsExtraPermission.value)

        player.recentResult = SpotifyResult.Ok(null)
        advance(3_000) // asked again under the new login
        assertEquals(listOf(0L, 3_000L), player.callsTo("recentlyPlayed").map { it.atMs })
        assertFalse(controller.needsExtraPermission.value)
    }

    @Test
    fun aFailedHandOverDoesNotReadSpotifysQueueOnEveryTry() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 186_000)
        repeat(3) { player.addToQueueResults.add(SpotifyResult.NoActiveDevice) }
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        advance(2_000) // two more tries, a second apart
        assertEquals(3, player.callsTo("addToQueue").size)
        assertEquals(listOf(0L), player.callsTo("userQueue").map { it.atMs })

        advance(1_000) // the hand-over works: Spotify's queue has changed
        assertEquals(r1, controller.handedOver.value)
        assertEquals(listOf(0L, 3_000L), player.callsTo("userQueue").map { it.atMs })
    }

    @Test
    fun playOnTheLastPlayedSongResumesAndARefusalShowsWhy() = runTest {
        val player = fakePlayer()
        val controller = startIdleWithLastPlayedA(player)
        player.playCommandResults.add(SpotifyResult.NoActiveDevice)
        val gate = CompletableDeferred<Unit>()
        player.playCommandGate = gate
        click(controller)
        assertEquals(listOf("resume"), player.playCommands())
        assertEquals(true, controller.shownPlaying())

        gate.complete(Unit)
        runCurrent() // refused, and the poll that follows still finds nothing active
        assertEquals(listOf(0L, 0L), player.pollTimes())
        controller.assertShowsLastPlayed(trackA, progressMs = 42_000)
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)
        advance(9_000) // and so do the polls after it
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)

        player.plays(trackA, progressMs = 42_000) // the streamer opened Spotify on a device
        advance(3_000)
        assertEquals(SpotifyHealth.Ok, controller.health.value)
        player.currentlyPlayingResult = SpotifyResult.Ok(null)
        advance(3_000)
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)
    }

    @Test
    fun aRefusedSkipOrPreviousShowsNoActiveDeviceUntilSpotifyTakesACommand() = runTest {
        val player = fakePlayer()
        val controller = startIdleWithLastPlayedA(player)
        player.previousResult = SpotifyResult.NoActiveDevice
        controller.previous()
        advance(6_000) // the poll that follows and the next ones find nothing active
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)

        controller.skip() // taken: the polls tell again
        advance(3_000)
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)

        player.skipResult = SpotifyResult.NoActiveDevice
        controller.skip()
        advance(6_000)
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)
    }

    @Test
    fun aHandOverIsSavedBeforeSpotifysQueueIsRead() = runTest {
        val player = fakePlayer()
        player.queueGate = CompletableDeferred() // Spotify is slow to list its queue
        player.plays(trackA, progressMs = 185_000)
        val controller = controller(player)
        controller.enqueue(r1)
        controller.start()
        runCurrent()
        assertEquals(listOf(r1.track.uri), player.callsTo("addToQueue").map { it.arg })
        assertEquals(listOf(0L), player.callsTo("userQueue").map { it.atMs }) // still in flight
        assertEquals(r1, controller.handedOver.value)
        assertTrue(controller.queue.value.items.isEmpty())
        assertEquals(r1, store().load().handedOver)
        assertTrue(store().load().queue.items.isEmpty())
    }

    @Test
    fun skipOnTheLastPlayedSongStillHandsOverARequestForThatSong() = runTest {
        val player = fakePlayer()
        val again = request("again", trackA)
        val controller = startIdleWithLastPlayedA(player) // shows track A, but nothing plays
        controller.enqueue(again)
        runCurrent()
        controller.skip()
        assertEquals(
            listOf("addToQueue" to trackA.uri, "skipNext" to null),
            player.commands().map { it.name to it.arg },
        )
        assertEquals(again, controller.handedOver.value)
    }

    @Test
    fun resumedLastPlayedSongShowsPlayingUntilSpotifyReportsIt() = runTest {
        val player = fakePlayer()
        val controller = startIdleWithLastPlayedA(player)
        click(controller) // Spotify takes the resume, yet reports nothing active for a moment
        assertEquals(listOf("resume"), player.playCommands())
        assertEquals(listOf(0L, 0L), player.pollTimes(), "polled again after the resume")
        assertEquals(true, controller.shownPlaying())

        player.plays(trackA, progressMs = 42_500)
        advance(1_000)
        val shown = assertNotNull(controller.nowPlaying.value)
        assertEquals(NowPlayingKind.Live, shown.kind)
        assertTrue(shown.isPlaying)
    }

    @Test
    fun aRateLimitedReadOfSpotifysQueueWaitsAsAsked() = runTest {
        val player = fakePlayer()
        player.queueTracks = SpotifyResult.RateLimited(10_000)
        val controller = startAt60s(player)
        assertEquals(listOf(0L), player.callsTo("userQueue").map { it.atMs })

        controller.enqueue(r1) // a wake-up must not cut the wait short
        advance(9_999)
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 10_000L), player.pollTimes())
        assertEquals(SpotifyHealth.Ok, controller.health.value, "an extra read never changes health")
    }

    @Test
    fun aRateLimitedReadOfTheRecentlyPlayedSongWaitsAsAsked() = runTest {
        val player = fakePlayer()
        player.recentResult = SpotifyResult.RateLimited(10_000)
        val controller = startIdleWithLastPlayedA(player)
        advance(9_999)
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 10_000L), player.pollTimes())
        controller.assertShowsLastPlayed(trackA, progressMs = 42_000)
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value, "an extra read never changes health")
        assertFalse(controller.needsExtraPermission.value)
    }

    @Test
    fun aRateLimitedExtraReadNeverPollsSoonerThanUsual() = runTest {
        val player = fakePlayer()
        player.queueTracks = SpotifyResult.RateLimited(0)
        startAt60s(player)
        advance(2_999)
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        assertEquals(listOf(0L, 3_000L), player.pollTimes())
    }
}

/** Counts the values written through it: one per store write. */
private class CountingSerializer<T>(private val inner: KSerializer<T>) : KSerializer<T> by inner {
    var writes = 0
        private set

    override fun serialize(encoder: Encoder, value: T) {
        writes++
        inner.serialize(encoder, value)
    }
}
