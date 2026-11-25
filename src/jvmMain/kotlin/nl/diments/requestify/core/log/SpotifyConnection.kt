package nl.diments.requestify.core.log

import nl.diments.requestify.core.playback.SpotifyHealth

/**
 * Spotify as the Log sees it. A playback state (nothing playing, no device, not set up) is no connection news. A limit
 * Spotify holds says which, for which call and after how many calls: Spotify publishes none of its limits. It can be another call's than the polls
 * behind [health]: [limit] is whichever Spotify holds. An unreachable Spotify says why, so a long outage can be told apart afterwards: a timeout, a
 * Spotify error (`HTTP 503`) or a login that could not be refreshed (`token unavailable: …`).
 */
fun spotifyConnection(health: SpotifyHealth, limit: String? = null): ConnectionReport = when (health) {
    SpotifyHealth.LoginNeeded -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.LoginExpired)
    is SpotifyHealth.Unreachable -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.Down, health.message)
    else -> when {
        limit != null -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.LimitReached, limit)
        health is SpotifyHealth.RateLimited -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.LimitReached, health.detail)
        else -> ConnectionReport(LogService.Spotify, ConnectionSnapshot.Up)
    }
}
