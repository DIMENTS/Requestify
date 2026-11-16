package nl.diments.requestify.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.model.Track

class NowPlayingTest {
    /** 200 s long. */
    private val track = Track("t1", "spotify:track:t1", "Song", listOf("Artist"), 200_000, false, null)

    @Test
    fun whilePlayingThePositionRunsOnWithWallTimeUpToTheEnd() {
        val playing = NowPlaying(track, progressMs = 60_000, isPlaying = true, request = null, sampledAtEpochMs = 1_000_000)
        assertEquals(60_000, playing.positionAt(1_000_000))
        assertEquals(65_000, playing.positionAt(1_005_000))
        assertEquals(200_000, playing.positionAt(1_500_000))
    }

    @Test
    fun whilePausedThePositionStaysWhereItIs() {
        val paused = NowPlaying(track, progressMs = 60_000, isPlaying = false, request = null, sampledAtEpochMs = 1_000_000)
        assertEquals(60_000, paused.positionAt(1_000_000))
        assertEquals(60_000, paused.positionAt(1_005_000))
    }
}
