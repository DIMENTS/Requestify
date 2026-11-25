package nl.diments.requestify.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PollPaceTest {
    private var now = 0L
    private val pace = PollPace(PlaybackConfig()) { now }

    private fun playing(
        remainingMs: Long? = 100_000,
        trackId: String? = "A",
        isPlaying: Boolean = true,
        handOverWaits: Boolean = false,
        handOverRefused: Boolean = false,
        settling: Boolean = false,
        quiet: Boolean = false,
    ) = Polled(true, trackId, isPlaying, remainingMs, handOverWaits, handOverRefused, settling, quiet)

    private fun nothing(quiet: Boolean = false, settling: Boolean = false) =
        Polled(false, null, isPlaying = false, remainingMs = null, handOverWaits = false, handOverRefused = false, settling, quiet)

    @Test
    fun aSongIsFollowedEvery15SecondsAndCloserToItsEnd() {
        assertEquals(15_000, pace.next(playing(remainingMs = 185_000)))
        assertEquals(15_000, pace.next(playing(remainingMs = 30_000)), "at 15 s before its end")
        assertEquals(4_000, pace.next(playing(remainingMs = 19_000)), "the moment a request goes to Spotify is never missed")
        assertEquals(5_000, pace.next(playing(remainingMs = 15_000)))
        assertEquals(5_000, pace.next(playing(remainingMs = 4_500)))
        assertEquals(3_000, pace.next(playing(remainingMs = 2_000)), "a second after its end")
    }

    @Test
    fun neverSoonerThanASecond() {
        assertEquals(1_000, pace.next(playing(remainingMs = 15_010)))
    }

    @Test
    fun aSongOfUnknownLengthIsFollowedEvery15Seconds() {
        assertEquals(15_000, pace.next(playing(remainingMs = null, trackId = null)), "an ad or an episode")
    }

    @Test
    fun aSongThatStaysAtItsEndIsPolledEverSlower() {
        val waits = List(7) { pace.next(playing(remainingMs = -5_000L * it)) }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 15_000, 15_000, 15_000), waits)
        pace.next(playing(remainingMs = 180_000, trackId = "B"))
        assertEquals(1_000, pace.next(playing(remainingMs = 0, trackId = "B")), "the next song starts afresh")
    }

    @Test
    fun aPausedSongIsPolledEvery30Seconds() {
        assertEquals(30_000, pace.next(playing(isPlaying = false, remainingMs = 4_000)))
        assertEquals(30_000, pace.next(playing(isPlaying = false, remainingMs = 100_000, handOverWaits = true)))
    }

    @Test
    fun aSongPausedNearItsEndIsWatchedWhileARequestWaits() {
        assertEquals(20_000, pace.next(playing(isPlaying = false, remainingMs = 35_000, handOverWaits = true)))
        assertEquals(10_000, pace.next(playing(isPlaying = false, remainingMs = 16_000, handOverWaits = true)), "resumed, it still has 6 s then")
    }

    @Test
    fun whileNothingIsActiveItAsksEverLessOften() {
        assertEquals(listOf(5_000L, 10_000, 20_000, 40_000, 60_000, 60_000), List(6) { pace.next(nothing()) })
        pace.next(playing())
        assertEquals(5_000, pace.next(nothing()), "from the start again")
    }

    @Test
    fun lookingAgainStartsFromTheBeginning() {
        repeat(5) { pace.next(nothing()) }
        pace.lookAgain()
        assertEquals(5_000, pace.next(nothing()))
    }

    @Test
    fun whileNobodyNeedsItItHardlyAsks() {
        assertEquals(listOf(5_000L, 10_000, 20_000, 40_000, 80_000, 160_000, 300_000, 300_000), List(8) { pace.next(nothing(quiet = true)) })
        assertEquals(300_000, pace.next(playing(isPlaying = false, quiet = true)))
        assertEquals(60_000, pace.next(playing(remainingMs = 3_000, quiet = true)))
    }

    @Test
    fun aButtonOrADeviceIsConfirmedSoon() {
        assertEquals(1_000, pace.next(playing(settling = true)))
        assertEquals(1_000, pace.next(nothing(settling = true, quiet = true)))
    }

    @Test
    fun afterASkipItPollsSoonUntilAnotherSongPlays() {
        pace.commandTaken("A")
        assertEquals(1_000, pace.next(playing(trackId = "A")))
        assertEquals(15_000, pace.next(playing(trackId = "B")))
        assertEquals(15_000, pace.next(playing(trackId = "A")), "over: A plays again by itself")
    }

    @Test
    fun aSkipThatChangesNothingIsFollowedForFourSeconds() {
        pace.commandTaken("A")
        now = 3_999
        assertEquals(1_000, pace.next(playing(trackId = "A")))
        now = 4_000
        assertEquals(15_000, pace.next(playing(trackId = "A")))
    }

    @Test
    fun aRefusedHandOverIsTriedAgainSoonAndThenSlower() {
        val waits = List(4) { pace.next(playing(remainingMs = 14_000, handOverWaits = true, handOverRefused = true)) }
        assertEquals(listOf(1_000L, 2_000, 4_000, 5_000), waits)
        pace.next(playing(remainingMs = 14_000))
        assertEquals(1_000, pace.next(playing(remainingMs = 14_000, handOverWaits = true, handOverRefused = true)))
    }

    @Test
    fun aFailingSpotifyIsAskedEverLessOften() {
        assertEquals(listOf(3_000L, 6_000, 12_000, 24_000, 30_000, 30_000), List(6) { pace.failed(quiet = false) })
        pace.next(playing())
        assertEquals(3_000, pace.failed(quiet = false))
        assertEquals(listOf(6_000L, 12_000, 24_000, 48_000, 96_000, 192_000, 300_000), List(7) { pace.failed(quiet = true) })
    }

    @Test
    fun whileARequestWaitsForSomethingToPlayItLooksEvery15SecondsForTenMinutes() {
        val waiting = Polled(false, null, isPlaying = false, remainingMs = null, handOverWaits = true, handOverRefused = false, settling = false, quiet = false)
        val waits = List(42) { pace.next(waiting) }
        assertEquals(listOf(5_000L, 10_000, 15_000), waits.take(3))
        assertEquals(setOf(15_000L), waits.subList(2, 40).toSet())
        assertEquals(listOf(60_000L, 60_000), waits.takeLast(2))
    }
}
