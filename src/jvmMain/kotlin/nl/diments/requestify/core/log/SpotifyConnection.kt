package nl.diments.requestify.core.log

import nl.diments.requestify.core.playback.SpotifyHealth

/**
 * Spotify as the Log sees it. A playback state (nothing playing, no device, not set up) or a busy Spotify is no
 * connection news. An unreachable Spotify says why, so a long outage can be told apart afterwards: a timeout, a
 * Spotify error (`HTTP 503`) or a login that could not be refreshed (`token unavailable: …`).
 */
fun spotifyConnection(health: SpotifyHealth): ConnectionReport = when (health) {
    SpotifyHealth.LoginNeeded -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.LoginExpired)
    is SpotifyHealth.Unreachable -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.Down, health.message)
    else -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.Up)
}
