package nl.diments.requestify.core.playback

import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.spotify.SpotifyResult

/**
 * When Spotify's queue is read again, and what of it is shown. Not thread-safe: used only under the controller's
 * `spotifyMutex`, because only Spotify sequences read Spotify's queue or make it stale.
 */
internal class UpcomingBook(private val config: PlaybackConfig, private val clock: () -> Long) {
    private var readAtMs: Long? = null // null: read it on the next tick that finds something active
    private var readForTrackId: String? = null
    private var stale = false

    /**
     * Whether Spotify's queue is read on this tick: after a hand-over, [markStale], on another track, or when
     * [PlaybackConfig.upcomingRefreshMs] has passed and Spotify plays. Never while nothing is [active] (or nobody needs
     * to know) or Spotify asked to wait.
     */
    fun due(active: Boolean, trackId: String?, handOverResult: SpotifyResult<Unit>?, isPlaying: Boolean = true): Boolean {
        if (!active || handOverResult is SpotifyResult.RateLimited) return false
        val readAt = readAtMs ?: return true
        return handOverResult is SpotifyResult.Ok || stale || trackId != readForTrackId ||
            (isPlaying && clock() - readAt !in 0 until config.upcomingRefreshMs)
    }

    /** The queue is being read now, while [trackId] plays. */
    fun markRead(trackId: String?) {
        readAtMs = clock()
        readForTrackId = trackId
        stale = false
    }

    /** Something changed Spotify's queue (a skip or previous): read it on the next tick. */
    fun markStale() {
        stale = true
    }

    /** Nothing is active: read the queue again as soon as something is. */
    fun reset() {
        readAtMs = null
    }

    /**
     * Spotify's queue without the handed-over request (it shows on its own) and repeats, at most [UPCOMING_LIMIT].
     * Known gap: tracks the streamer queued in Spotify itself play before the handed-over request, yet show after
     * the requests (Spotify's queue does not tell them apart from what its playlist or album plays next).
     */
    fun from(spotifyQueue: List<Track>, handedOver: Request?): List<Track> {
        val sentAt = handedOver?.let { sent -> spotifyQueue.indexOfFirst { it.id == sent.track.id } } ?: -1
        return spotifyQueue.filterIndexed { index, _ -> index != sentAt }.distinctBy { it.id }.take(UPCOMING_LIMIT)
    }
}
