package nl.diments.requestify.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.playback.SpotifyHealth

class SpotifyConnectionTest {
    @Test
    fun anUnreachableSpotifyIsDownAndSaysWhy() {
        assertEquals(
            ConnectionReport(LogService.Spotify, ConnectionSnapshot.Down, "token unavailable: HTTP 429"),
            spotifyConnection(SpotifyHealth.Unreachable("token unavailable: HTTP 429")),
        )
    }

    @Test
    fun aDeadLoginIsAnExpiredLogin() {
        assertEquals(
            ConnectionReport(LogService.Spotify, ConnectionSnapshot.LoginExpired),
            spotifyConnection(SpotifyHealth.LoginNeeded),
        )
    }

    @Test
    fun aLimitSpotifyHoldsSaysWhich() {
        val detail = "QUOTA_EXCEEDED, GET me/player/currently-playing, no Retry-After, 4 calls in 30 s, 812 in 24 h"
        assertEquals(
            ConnectionReport(LogService.Spotify, ConnectionSnapshot.LimitReached, detail),
            spotifyConnection(SpotifyHealth.RateLimited(untilEpochMs = 1L, quota = true, detail = detail)),
        )
    }

    @Test
    fun aPlaybackStateIsNoConnectionNews() {
        val up = listOf(
            SpotifyHealth.Ok,
            SpotifyHealth.NothingPlaying,
            SpotifyHealth.NoActiveDevice,
            SpotifyHealth.LoggedOut,
            SpotifyHealth.PremiumRequired,
        )
        for (health in up) {
            assertEquals(ConnectionReport(LogService.Spotify, ConnectionSnapshot.Up), spotifyConnection(health), "$health")
        }
    }

    @Test
    fun aLimitOfAnotherCallThanThePollsIsNewsToo() {
        val limit = "QUOTA_EXCEEDED, GET search"
        assertEquals(ConnectionReport(LogService.Spotify, ConnectionSnapshot.LimitReached, limit), spotifyConnection(SpotifyHealth.Ok, limit))
        assertEquals(
            ConnectionReport(LogService.Spotify, ConnectionSnapshot.Down, "timeout"),
            spotifyConnection(SpotifyHealth.Unreachable("timeout"), limit),
            "an outage is worse",
        )
    }
}
