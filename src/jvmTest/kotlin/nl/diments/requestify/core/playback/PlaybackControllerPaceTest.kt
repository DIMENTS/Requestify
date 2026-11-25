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
import kotlin.test.assertIs
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
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.spotify.CurrentlyPlaying
import nl.diments.requestify.core.spotify.SpotifyPlayer
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

/** How often Spotify is called with the real pace, and that no request misses its turn because of it. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class PlaybackControllerPaceTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-pace") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun track(id: String, durationMs: Long = 200_000) =
        Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), durationMs, false, null)

    private fun request(id: String) =
        Request(id, track("t-$id"), Requester("Viewer $id"), RequestSource.CHAT, null, createdAtEpochMs = 1L)

    private val trackA = track("A")
    private val trackB = track("B")

    /** Wall time runs ahead of virtual time by this much: the PC slept (or its clock was set back). */
    private var wallAheadMs = 0L

    private fun TestScope.now() = testScheduler.currentTime + wallAheadMs

    private fun TestScope.fakePlayer() = FakeSpotifyPlayer { testScheduler.currentTime }

    private fun TestScope.controller(
        player: SpotifyPlayer,
        engaged: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = PlaybackController(
        player = player,
        store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
        requestsOpen = MutableStateFlow(true),
        paidPriority = { true },
        scope = backgroundScope,
        clock = { now() },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        engaged = engaged,
    )

    private fun TestScope.started(player: SpotifyPlayer, engaged: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        controller(player, engaged).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun FakeSpotifyPlayer.pollTimes() = callsTo("currentlyPlaying").map { it.atMs }

    /** A Spotify that really plays: each song for as long as it lasts, then what was queued, or else its own music. */
    private class PlayingSpotify(val calls: FakeSpotifyPlayer, private val now: () -> Long) : SpotifyPlayer by calls {
        private var own = 0
        private var song = ownSong()
        private var startedAt = 0L
        private val queued = ArrayDeque<String>()

        /** When each song really started. */
        val starts = linkedMapOf(song.id to 0L)

        private fun ownSong() = Track("own-${own}", "spotify:track:own-${own++}", "Own", listOf("Artist"), 200_000, false, null)

        /** The quota for adding to the queue is used up until then. */
        var queueHeldUntil = 0L

        override suspend fun addToQueue(trackUri: String): SpotifyResult<Unit> {
            if (now() < queueHeldUntil) return SpotifyResult.RateLimited(queueHeldUntil - now(), quota = true, detail = "quota")
            queued += trackUri.removePrefix("spotify:track:")
            return calls.addToQueue(trackUri)
        }

        override suspend fun currentlyPlaying(): SpotifyResult<CurrentlyPlaying?> {
            while (now() - startedAt >= song.durationMs) {
                startedAt += song.durationMs
                song = queued.removeFirstOrNull()?.let { Track(it, "spotify:track:$it", "Song", listOf("Artist"), 200_000, false, null) } ?: ownSong()
                starts[song.id] = startedAt
            }
            calls.plays(song, progressMs = now() - startedAt)
            return calls.currentlyPlaying()
        }
    }

    @Test
    fun anHourOfMusicTakesAtMost400Calls() = runTest {
        val spotify = PlayingSpotify(fakePlayer()) { testScheduler.currentTime }
        started(spotify)
        advance(3_600_000)
        assertTrue(spotify.calls.calls.size in 250..400, "${spotify.calls.calls.size} calls: ${spotify.calls.calls.groupingBy { it.name }.eachCount()}")
    }

    @Test
    fun anHourOfMusicFullOfRequestsMissesNoneAndTakesAtMost450Calls() = runTest {
        val spotify = PlayingSpotify(fakePlayer()) { testScheduler.currentTime }
        val controller = started(spotify)
        val handedOver = mutableMapOf<String, Long>()
        val seen = mutableMapOf<String, Long>()
        repeat(17) { controller.enqueue(request("r$it")) }
        repeat(3_600) {
            advance(1_000)
            controller.handedOver.value?.let { handedOver.putIfAbsent(it.track.id, testScheduler.currentTime) }
            controller.nowPlaying.value?.let { seen.putIfAbsent(it.track.id, testScheduler.currentTime) }
        }
        val requested = spotify.starts.filterKeys { it.startsWith("t-") }
        assertEquals((0..16).map { "t-r$it" }, requested.keys.toList(), "one after the other, no song of its own between")
        for ((id, start) in requested) {
            val before = start - handedOver.getValue(id)
            assertTrue(before in 1_000..15_000, "$id went to Spotify $before ms before its turn")
            assertTrue(seen.getValue(id) - start in 0..2_000, "$id showed ${seen.getValue(id) - start} ms after it started")
        }
        assertTrue(spotify.calls.calls.size <= 450, "${spotify.calls.calls.size} calls: ${spotify.calls.calls.groupingBy { it.name }.eachCount()}")
    }

    @Test
    fun anHourPausedTakesAtMost125Calls() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 60_000, isPlaying = false)
        started(player)
        advance(3_600_000)
        assertTrue(player.calls.size <= 125, "${player.calls.size} calls")
    }

    @Test
    fun anHourWithNothingActiveTakesAtMost110Calls() = runTest {
        val player = fakePlayer()
        started(player)
        advance(3_600_000)
        assertTrue(player.calls.size <= 110, "${player.calls.size} calls: ${player.calls.groupingBy { it.name }.eachCount()}")
    }

    @Test
    fun aDayInTheTrayTakesAtMost400Calls() = runTest {
        val player = fakePlayer()
        started(player, engaged = MutableStateFlow(false))
        advance(24 * 3_600_000L)
        assertTrue(player.calls.size <= 400, "${player.calls.size} calls: ${player.calls.groupingBy { it.name }.eachCount()}")
    }

    @Test
    fun anHourOfMusicNobodyFollowsTakesAtMost70Calls() = runTest {
        val spotify = PlayingSpotify(fakePlayer()) { testScheduler.currentTime }
        started(spotify, engaged = MutableStateFlow(false))
        advance(3_600_000)
        assertTrue(spotify.calls.calls.size <= 70, "${spotify.calls.calls.size} calls")
    }

    @Test
    fun aRequestInTheTrayIsTakenUpAtOnceAndFollowedFromThere() = runTest {
        val spotify = PlayingSpotify(fakePlayer()) { testScheduler.currentTime }
        val controller = started(spotify, engaged = MutableStateFlow(false))
        advance(3_600_000 - 50_000) // 150 s into a song
        controller.enqueue(request("r1"))
        runCurrent()
        assertEquals(testScheduler.currentTime, spotify.calls.pollTimes().last())
        advance(52_000)
        assertEquals("t-r1", controller.nowPlaying.value?.track?.id)
        assertEquals(request("r1"), controller.nowPlaying.value?.request)
    }

    @Test
    fun shownAgainOrLiveItAsksAtOnceAlsoForDevices() = runTest {
        val player = fakePlayer()
        val engaged = MutableStateFlow(false)
        started(player, engaged)
        advance(2 * 3_600_000)
        val before = player.calls.size
        engaged.value = true
        runCurrent()
        assertEquals(listOf("currentlyPlaying", "devices"), player.calls.drop(before).map { it.name }.filter { it != "recentlyPlayed" })
        advance(5_000)
        assertEquals(testScheduler.currentTime, player.pollTimes().last(), "and as often as at first")
    }

    @Test
    fun becomingQuietWakesNothing() = runTest {
        val player = fakePlayer()
        val engaged = MutableStateFlow(true)
        started(player, engaged)
        advance(1_000)
        engaged.value = false
        runCurrent()
        assertEquals(listOf(0L), player.pollTimes())
    }

    @Test
    fun focusAsksAtOnceButNotRightAfterAPoll() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = started(player)
        advance(2_999)
        controller.lookAgain()
        runCurrent()
        assertEquals(listOf(0L), player.pollTimes())
        advance(1)
        controller.lookAgain()
        runCurrent()
        assertEquals(listOf(0L, 3_000L), player.pollTimes())
    }

    @Test
    fun aSkipShowsTheNextSongWithinASecond() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = started(player)
        controller.skip()
        runCurrent() // Spotify still reports A
        player.plays(trackB)
        advance(1_000)
        assertEquals(trackB, controller.nowPlaying.value?.track)
        advance(14_000)
        assertEquals(listOf(0L, 0L, 1_000L), player.pollTimes(), "and then the usual pace")
    }

    @Test
    fun aFailingSpotifyIsAskedEverLessOften() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.Failed("HTTP 503")
        val controller = started(player)
        advance(80_000)
        assertEquals(listOf(0L, 3_000L, 9_000L, 21_000L, 45_000L, 75_000L), player.pollTimes())
        player.plays(trackA)
        advance(25_000)
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun aWaitOfHoursIsKeptAndShown() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.RateLimited(61_887_000, quota = true, detail = "QUOTA_EXCEEDED")
        val controller = started(player)
        assertEquals(SpotifyHealth.RateLimited(61_887_000, quota = true, detail = "QUOTA_EXCEEDED"), controller.health.value)
        controller.enqueue(request("r1"))
        controller.lookAgain()
        advance(61_886_999)
        assertEquals(listOf(0L), player.pollTimes())
        player.plays(trackA)
        advance(1)
        assertEquals(listOf(0L, 61_887_000L), player.pollTimes())
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun aNewLoginEndsTheWait() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.RateLimited(61_887_000, quota = true)
        val controller = started(player)
        advance(10_000)
        player.plays(trackA)
        controller.onLoginChanged()
        runCurrent()
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun aWaitPassesWhileThePcSleeps() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.RateLimited(3_600_000, quota = true)
        started(player)
        advance(60_000)
        player.plays(trackA)
        wallAheadMs = 2 * 3_600_000L
        advance(60_000)
        assertEquals(2, player.pollTimes().size)
    }

    @Test
    fun aClockSetBackMakesNoWaitLonger() = runTest {
        val player = fakePlayer()
        started(player, engaged = MutableStateFlow(false))
        advance(3_600_000)
        val polls = player.pollTimes().size
        wallAheadMs = -24 * 3_600_000L
        advance(900_000)
        assertTrue(player.pollTimes().size - polls in 2..4, "still every 5 minutes")
    }

    @Test
    fun aFailedSaveIsTriedAgainWhileSpotifyIsNotAsked() = runTest {
        val player = fakePlayer()
        player.currentlyPlayingResult = SpotifyResult.RateLimited(3_600_000, quota = true)
        val controller = started(player)
        val blocker = dir.resolve("queue.json.tmp").createDirectory() // the store cannot write its temp file
        controller.enqueue(request("r1"))
        runCurrent()
        assertNotNull(controller.persistError.value)
        advance(60_000)
        assertNotNull(controller.persistError.value)
        blocker.deleteExisting()
        advance(60_000)
        assertNull(controller.persistError.value)
        assertEquals(listOf(0L), player.pollTimes())
    }

    @Test
    fun aQuotaUsedUpForAnExtraReadStopsNoPolls() = runTest {
        val player = fakePlayer()
        player.queueTracks = SpotifyResult.RateLimited(61_887_000, quota = true)
        player.plays(trackA, progressMs = 10_000)
        val controller = started(player)
        advance(30_000)
        assertEquals(listOf(0L, 15_000L, 30_000L), player.pollTimes())
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun aQuotaUsedUpForTheQueueCheckStillShowsTheSong() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 186_000)
        val controller = controller(player)
        controller.enqueue(request("r1"))
        controller.start()
        runCurrent()
        assertNotNull(controller.handedOver.value)
        player.userQueueResult = SpotifyResult.RateLimited(61_887_000, quota = true)
        player.plays(trackB)
        advance(15_000)
        assertEquals(trackB, controller.nowPlaying.value?.track)
        assertIs<SpotifyHealth.RateLimited>(controller.health.value)
        assertNotNull(controller.handedOver.value, "not settled yet")

        player.plays(track("t-r1"))
        advance(15_000)
        assertEquals(request("r1"), controller.nowPlaying.value?.request, "it plays: that settles it without the check")
        assertEquals(SpotifyHealth.Ok, controller.health.value)
    }

    @Test
    fun aHandOverWithItsQuotaUsedUpHurriesNothing() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 186_000)
        repeat(20) { player.addToQueueResults.add(SpotifyResult.RateLimited(61_887_000, quota = true)) }
        val controller = controller(player)
        controller.enqueue(request("r1"))
        controller.start()
        runCurrent()
        assertEquals(true, (controller.health.value as? SpotifyHealth.RateLimited)?.quota)
        advance(10_000)
        assertEquals(listOf(0L, 5_000L, 10_000L), player.pollTimes())
        assertEquals(request("r1"), controller.queue.value.head)
    }

    @Test
    fun anExtraReadThatMustWaitMakesItDeafOnlyThatLong() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        player.queueTracks = SpotifyResult.RateLimited(3_000)
        val controller = started(player)
        advance(2_000)
        controller.enqueue(request("r1"))
        runCurrent()
        assertEquals(listOf(0L), player.pollTimes(), "Spotify asked for 3 s")
        advance(1_000)
        assertEquals(listOf(0L, 3_000L), player.pollTimes(), "and not for the 15 s of the usual wait")
    }

    @Test
    fun shownWhileAPollIsUnderWayItIsAskedAgainSoon() = runTest {
        val player = fakePlayer()
        val engaged = MutableStateFlow(false)
        started(player, engaged)
        advance(2 * 3_600_000)
        val gate = CompletableDeferred<Unit>()
        player.currentlyPlayingGate = gate
        advance(300_000) // a poll is under way
        engaged.value = true
        runCurrent()
        val polls = player.pollTimes().size
        gate.complete(Unit)
        advance(1_000)
        assertTrue(player.pollTimes().size > polls)
    }

    @Test
    fun whileARequestPlaysAFailingSpotifyIsAskedAgainSoonAlsoFromTheTray() = runTest {
        val player = fakePlayer()
        player.plays(request("r1").track, progressMs = 10_000)
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })
            .save(PersistedPlayback(playing = request("r1")))
        started(player, engaged = MutableStateFlow(false))
        player.currentlyPlayingResult = SpotifyResult.Failed("HTTP 503")
        advance(600_000)
        val times = player.pollTimes().takeLast(3)
        assertEquals(listOf(30_000L, 30_000L), times.zipWithNext { a, b -> b - a })
    }

    @Test
    fun aRequestLeftPlayingFromLongAgoKeepsTheTrayQuiet() = runTest {
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })
            .save(PersistedPlayback(handedOver = request("r2"), playing = request("r1")))
        val player = fakePlayer()
        started(player, engaged = MutableStateFlow(false))
        advance(24 * 3_600_000L)
        assertTrue(player.calls.size <= 400, "${player.calls.size} calls: ${player.calls.groupingBy { it.name }.eachCount()}")
    }

    @Test
    fun aSongResumedNearItsEndIsSeenWhileARequestWaits() = runTest {
        val player = fakePlayer()
        val controller = started(player)
        advance(5 * 60_000)
        controller.enqueue(request("r1"))
        advance(5 * 60_000)
        player.plays(trackA, progressMs = 170_000) // 30 s left
        advance(15_000)
        assertEquals(trackA, controller.nowPlaying.value?.track)
    }

    @Test
    fun aRequestLeftInTheQueueOvernightIsNotWaitedForAllNight() = runTest {
        val player = fakePlayer()
        val controller = started(player, engaged = MutableStateFlow(false))
        controller.enqueue(request("r1"))
        advance(10 * 3_600_000L)
        assertTrue(player.calls.size <= 700, "${player.calls.size} calls: ${player.calls.groupingBy { it.name }.eachCount()}")
    }

    @Test
    fun aQuotaUsedUpForTheHandOverStaysShownUntilItsWaitIsOver() = runTest {
        val spotify = PlayingSpotify(fakePlayer()) { testScheduler.currentTime }
        spotify.queueHeldUntil = 785_000
        val controller = started(spotify)
        controller.enqueue(request("r1"))
        advance(190_000)
        val limited = SpotifyHealth.RateLimited(785_000, quota = true, detail = "quota")
        assertEquals(limited, controller.health.value)
        for (minute in 1..9) {
            advance(60_000) // also in the middle of a song, where no request would go
            assertEquals(limited, controller.health.value, "$minute min later")
        }
        advance(60_000) // 790 s: over, and the request goes at the end of this song
        assertEquals(SpotifyHealth.Ok, controller.health.value)
        advance(15_000)
        assertEquals(request("r1"), controller.nowPlaying.value?.request)
    }

    @Test
    fun aSlowExtraReadDelaysNoHandOver() = runTest {
        val player = fakePlayer()
        val slow = CompletableDeferred<Unit>()
        player.queueGate = slow
        player.plays(trackA, progressMs = 183_000) // 17 s left: in 2 s the request goes
        val controller = controller(player)
        controller.enqueue(request("r1"))
        controller.start()
        runCurrent()
        advance(14_000)
        slow.complete(Unit)
        player.plays(trackA, progressMs = 198_000)
        advance(1_000)
        assertEquals(1, player.callsTo("addToQueue").size)
    }

    @Test
    fun shownDuringAFailingPollItIsAskedAgainSoon() = runTest {
        val player = fakePlayer()
        val engaged = MutableStateFlow(false)
        player.currentlyPlayingResult = SpotifyResult.Failed("HTTP 503")
        started(player, engaged)
        advance(2 * 3_600_000)
        val gate = CompletableDeferred<Unit>()
        player.currentlyPlayingGate = gate
        advance(300_000) // a poll is under way
        engaged.value = true
        runCurrent()
        val polls = player.pollTimes().size
        gate.complete(Unit)
        advance(3_000)
        assertTrue(player.pollTimes().size > polls)
    }

    @Test
    fun aRequestThatLeftSpotifysQueueUnwatchedIsNeverDropped() = runTest {
        val saved = PersistedPlayback(handedOver = request("r1"), handedOverAtEpochMs = -400_000)
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }).save(saved)
        val player = fakePlayer()
        player.plays(trackB, progressMs = 10_000)
        player.recentSinceResult = SpotifyResult.RateLimited(61_887_000, quota = true)
        val controller = started(player)
        assertNull(controller.handedOver.value)
        assertEquals(request("r1"), controller.queue.value.head, "nobody can tell whether it played")
    }

    @Test
    fun whileTheRecentlyPlayedMustWaitForAMomentNothingIsDecided() = runTest {
        val saved = PersistedPlayback(handedOver = request("r1"), handedOverAtEpochMs = 0)
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }).save(saved)
        val player = fakePlayer()
        player.plays(trackB, progressMs = 10_000)
        player.recentSinceResult = SpotifyResult.RateLimited(7_000)
        val controller = started(player)
        assertEquals(request("r1"), controller.handedOver.value)
        player.recentSinceResult = SpotifyResult.Ok(emptyList())
        advance(15_000)
        assertNull(controller.handedOver.value)
        assertEquals(request("r1"), controller.queue.value.head)
    }

    @Test
    fun aSkipOfASongThatIsOverSinceTheLastPollSkipsNothing() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 10_000)
        val controller = started(player)
        advance(5_000)
        player.plays(trackB) // the streamer skipped in Spotify itself
        assertEquals(false, controller.skipIfPlaying(trackA.id))
        assertTrue(player.callsTo("skipNext").isEmpty())
        runCurrent()
        assertEquals(trackB, controller.nowPlaying.value?.track)
    }
}
