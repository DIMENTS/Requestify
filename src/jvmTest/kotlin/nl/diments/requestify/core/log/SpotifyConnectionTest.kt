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
    fun aPlaybackStateOrABusySpotifyIsNoConnectionNews() {
        val up = listOf(
            SpotifyHealth.Ok,
            SpotifyHealth.NothingPlaying,
            SpotifyHealth.NoActiveDevice,
            SpotifyHealth.LoggedOut,
            SpotifyHealth.PremiumRequired,
            SpotifyHealth.RateLimited(untilEpochMs = 1L),
        )
        for (health in up) {
            assertEquals(ConnectionReport(LogService.Spotify, ConnectionSnapshot.Up), spotifyConnection(health), "$health")
        }
    }
}
