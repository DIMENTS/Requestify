package nl.diments.requestify

import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.stream.GateStatus

class PlaybackMattersTest {
    private fun status(open: Boolean, twitch: Boolean, live: Boolean) =
        GateStatus(open, twitch, live, manual = false, clearAtEpochMs = null, clearPostponed = false)

    @Test
    fun whatSpotifyPlaysMattersWithTheWindowShownOrAStreamThatTakesRequests() {
        val idle = status(open = false, twitch = true, live = false)
        assertEquals(true, playbackMatters(idle, windowShown = true))
        assertEquals(false, playbackMatters(idle, windowShown = false))
        assertEquals(true, playbackMatters(status(open = false, twitch = true, live = true), windowShown = false), "live, requests closed")
        assertEquals(true, playbackMatters(status(open = true, twitch = true, live = false), windowShown = false), "opened by hand")
        assertEquals(false, playbackMatters(status(open = true, twitch = false, live = false), windowShown = false), "without Twitch they are always open")
    }
}
