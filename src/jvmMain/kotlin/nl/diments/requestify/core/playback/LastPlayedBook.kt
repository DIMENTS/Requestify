package nl.diments.requestify.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.queue.LastPlayed
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.spotify.CurrentlyPlaying
import nl.diments.requestify.core.spotify.RecentTrack
import nl.diments.requestify.core.spotify.SpotifyResult

/**
 * The last song played, saved for the next startup, and Spotify's own recently played song, which is shown while
 * nothing is active. Not thread-safe: used only under the controller's `stateMutex`. [lastPlayed] is saved; it
 * changes when the song, its play state or Spotify's activity changes, and while a song plays at most every
 * [PlaybackConfig.upcomingRefreshMs], so most polls save nothing.
 */
internal class LastPlayedBook(private val config: PlaybackConfig, private val clock: () -> Long) {
    /** What is saved. */
    var lastPlayed: LastPlayed? = null
        private set
    private var lastLive: NowPlaying? = null // the live song the last poll showed
    private var spotifyRecent: RecentTrack? = null
    private var recentAskedAtMs: Long? = null

    /** Counts logins, so an answer asked for under an earlier login is ignored. */
    private var loginGeneration = 0

    private val _needsExtraPermission = MutableStateFlow(false)

    /**
     * The login lacks the permission to read Spotify's recently played songs: only the saved last played song is
     * shown until the streamer logs in again ([onLoginChanged]).
     */
    val needsExtraPermission: StateFlow<Boolean> = _needsExtraPermission.asStateFlow()

    /** Takes over what was saved. */
    fun restore(saved: PersistedPlayback) {
        lastPlayed = saved.lastPlayed
    }

    /**
     * What a poll shows: the live song (as [withPlayIntent] makes of it), or with nothing active the last played
     * one. Keeps [lastPlayed] (with who asked for the song, [playing]) up to date. [shown] is what is shown now.
     */
    fun show(
        cp: CurrentlyPlaying?,
        playing: Request?,
        shown: NowPlaying?,
        withPlayIntent: (NowPlaying?) -> NowPlaying?,
    ): NowPlaying? {
        val now = clock()
        val track = cp?.track
        val polled = if (cp != null && track != null) {
            NowPlaying(track, cp.progressMs, cp.isPlaying, playing?.takeIf { it.track.id == track.id }, now)
        } else {
            null
        }
        if (cp == null) {
            lastLive?.let { lastPlayed = lastPlayedOf(it, now) }
            lastLive = null
        }
        val live = withPlayIntent(polled)
        if (live != null && live.kind == NowPlayingKind.Live) {
            val before = lastLive
            val changed = before == null || before.track.id != live.track.id || before.isPlaying != live.isPlaying
            // While it plays the position moves on: keep the saved one from going stale, with one save per while.
            val savedLongAgo = lastPlayed.let { it == null || now - it.playedAtEpochMs >= config.upcomingRefreshMs }
            if (changed || (live.isPlaying && savedLongAgo)) lastPlayed = lastPlayedOf(live, now)
            lastLive = live
        }
        return live ?: if (cp == null) lastPlayedCard(shown) else null
    }

    private fun lastPlayedOf(shown: NowPlaying, now: Long) =
        LastPlayed(shown.track, shown.positionAt(now), playedAtEpochMs = now, request = shown.request)

    /**
     * The newer of [lastPlayed] and Spotify's recently played song, shown paused; null when neither is known.
     * Spotify's song starts at 0 and leaves the requester unknown; when it is the same song as [lastPlayed], the
     * saved one wins, with its position and requester. The card [shown] now is kept when it shows the same, so
     * idle polls publish nothing new.
     */
    fun lastPlayedCard(shown: NowPlaying?): NowPlaying? {
        val local = lastPlayed
        val spotify = spotifyRecent
        val spotifyWins = spotify != null &&
            (local == null || (spotify.track.id != local.track.id && spotify.playedAtEpochMs > local.playedAtEpochMs))
        val card = when {
            spotifyWins -> NowPlaying(
                spotify.track,
                progressMs = 0,
                isPlaying = false,
                request = null,
                sampledAtEpochMs = clock(),
                kind = NowPlayingKind.LastPlayed,
                requesterKnown = false,
            )
            local != null -> NowPlaying(
                local.track,
                local.progressMs,
                isPlaying = false,
                request = local.request,
                sampledAtEpochMs = clock(),
                kind = NowPlayingKind.LastPlayed,
            )
            else -> return null
        }
        return if (shown != null && shown.copy(sampledAtEpochMs = card.sampledAtEpochMs) == card) shown else card
    }

    /**
     * The login generation to ask for Spotify's recently played song under, or null when it is not due: it is
     * asked on the first tick with nothing active, then at most every [PlaybackConfig.recentlyPlayedRefreshMs],
     * and not at all while the login lacks the permission.
     */
    fun recentlyPlayedDue(): Int? {
        if (_needsExtraPermission.value) return null
        val askedAt = recentAskedAtMs
        if (askedAt != null && clock() - askedAt < config.recentlyPlayedRefreshMs) return null
        recentAskedAtMs = clock()
        return loginGeneration
    }

    /**
     * Applies Spotify's recently played answer, asked for under login generation [askedUnder]. Any other failure
     * than a missing permission is ignored: it is asked again later.
     */
    fun answeredRecentlyPlayed(result: SpotifyResult<RecentTrack?>, askedUnder: Int) {
        if (askedUnder != loginGeneration) return // asked under an earlier login: the next idle tick asks again
        when (result) {
            is SpotifyResult.Ok -> {
                spotifyRecent = result.value
                _needsExtraPermission.value = false
            }
            SpotifyResult.MissingPermission -> _needsExtraPermission.value = true
            else -> Unit
        }
    }

    /** The Spotify login changed: the permission is checked again by asking for the recently played song. */
    fun onLoginChanged() {
        loginGeneration++
        recentAskedAtMs = null
        _needsExtraPermission.value = false
    }
}
