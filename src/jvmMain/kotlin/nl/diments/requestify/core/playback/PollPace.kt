package nl.diments.requestify.core.playback

/**
 * When Spotify is polled, and how often the rest is asked. Spotify limits how many calls an app may make, so a poll
 * must be worth it: a song's end is known from one poll, so until then it is only followed loosely.
 *
 * While a song plays: every [playingPollMs], at [nearEndWindowMs] before its end (never later than [handoverLeadMs],
 * when the next request goes to Spotify), every [nearEndPollMs] from there, and [endGraceMs] after its end. Paused:
 * every [pausedPollMs], but while a request waits no later than [pausedNearEndPollMs] near the hand-over. While nothing
 * is active: from [nothingPollMs] doubling to [nothingPollMaxMs], but while a request waits at first no further than
 * [playingPollMs] ([waitingPolls] times), so a song resumed near its end is seen in time. While nobody needs to know what plays:
 * [quietPlayingPollMs] while a song plays, up to [quietIdlePollMs] otherwise. A poll that failed is tried again from
 * [failedPollMs] doubling to [failedPollMaxMs].
 *
 * [settlePollMs]: how soon Spotify is asked again when it has something to confirm, and the shortest wait of all.
 * [playIntentSettleMs]: how long that lasts; a pause or resume is shown that long while Spotify still reports the state
 * from before it (its Web API takes a moment to catch up). [retryMs]: how often a failed save or load is tried again.
 * [lookAgainGapMs]: how long after a poll another look is not worth one.
 *
 * [upcomingRefreshMs]: how often Spotify's queue is read again while a song plays when nothing else asked for it.
 * [positionSaveMs]: how often the saved position of the song playing is brought up to date. [recentlyPlayedRefreshMs]:
 * how often Spotify's recently played song is asked for while nothing is active. [deviceCheckMs]: how long after the
 * first time Spotify's devices are read again while nothing is active, doubling to [deviceCheckMaxMs], or to
 * [quietDeviceCheckMs] while nobody needs to know.
 */
data class PlaybackConfig(
    val handoverLeadMs: Long = 15_000,
    val playingPollMs: Long = 15_000,
    val nearEndPollMs: Long = 5_000,
    val nearEndWindowMs: Long = 15_000,
    val endGraceMs: Long = 1_000,
    val pausedPollMs: Long = 30_000,
    val pausedNearEndPollMs: Long = 10_000,
    val nothingPollMs: Long = 5_000,
    val nothingPollMaxMs: Long = 60_000,
    val waitingPolls: Int = 40,
    val quietPlayingPollMs: Long = 60_000,
    val quietIdlePollMs: Long = 300_000,
    val failedPollMs: Long = 3_000,
    val failedPollMaxMs: Long = 30_000,
    val settlePollMs: Long = 1_000,
    val playIntentSettleMs: Long = 4_000,
    val retryMs: Long = 3_000,
    val lookAgainGapMs: Long = 3_000,
    val upcomingRefreshMs: Long = 120_000,
    val positionSaveMs: Long = 30_000,
    val recentlyPlayedRefreshMs: Long = 600_000,
    val deviceCheckMs: Long = 6_000,
    val deviceCheckMaxMs: Long = 120_000,
    val quietDeviceCheckMs: Long = 1_800_000,
)

/**
 * What a poll found. [handOverWaits]: a request is ready to go to Spotify once the song is near its end;
 * [handOverRefused]: Spotify just refused it. [settling]: Spotify has something to confirm (a pause or resume, a device
 * made active). [quiet]: nobody needs to know what plays.
 */
internal class Polled(
    val active: Boolean,
    val trackId: String?,
    val isPlaying: Boolean,
    val remainingMs: Long?,
    val handOverWaits: Boolean,
    val handOverRefused: Boolean,
    val settling: Boolean,
    val quiet: Boolean,
)

/** When to poll next ([PlaybackConfig]). Not thread-safe: used only under the controller's `spotifyMutex`. */
internal class PollPace(private val config: PlaybackConfig, private val clock: () -> Long) {
    private class Command(val trackId: String?, val atMs: Long)

    /** A skip or previous Spotify took, while the song from before it may still be reported. */
    private var command: Command? = null

    // How often in a row: nothing was active, a poll failed, the song stayed at its end, a hand-over was refused.
    private var nothing = 0
    private var failures = 0
    private var overruns = 0
    private var refusals = 0

    /** Spotify took a skip or previous while [trackId] played. */
    fun commandTaken(trackId: String?) {
        command = Command(trackId, clock())
    }

    /** Somebody needs to know again: ask as often as at first. */
    fun lookAgain() {
        nothing = 0
        failures = 0
    }

    /** How long to wait after a poll that failed. */
    fun failed(quiet: Boolean): Long =
        grown(config.failedPollMs, if (quiet) config.quietIdlePollMs else config.failedPollMaxMs, failures++)

    /** How long to wait after [polled]. */
    fun next(polled: Polled): Long {
        failures = 0
        if (polled.active) nothing = 0
        if (!polled.handOverRefused) refusals = 0
        if (commandSettles(polled.trackId) || polled.settling) return config.settlePollMs
        return when {
            !polled.active -> grown(config.nothingPollMs, longestNothing(polled), nothing++)
            !polled.isPlaying -> if (polled.quiet) config.quietIdlePollMs else paused(polled)
            polled.quiet -> config.quietPlayingPollMs
            else -> playing(polled)
        }
    }

    private fun longestNothing(polled: Polled): Long = when {
        polled.quiet -> config.quietIdlePollMs
        polled.handOverWaits && nothing < config.waitingPolls -> minOf(config.playingPollMs, config.nothingPollMaxMs)
        else -> config.nothingPollMaxMs
    }

    private fun commandSettles(trackId: String?): Boolean {
        val sent = command ?: return false
        val settles = trackId == sent.trackId && clock() - sent.atMs in 0 until config.playIntentSettleMs
        if (!settles) command = null
        return settles
    }

    private fun paused(polled: Polled): Long {
        val toHandOver = polled.remainingMs?.takeIf { polled.handOverWaits }?.minus(config.handoverLeadMs)
            ?: return config.pausedPollMs
        return toHandOver.coerceAtLeast(config.pausedNearEndPollMs).coerceAtMost(config.pausedPollMs)
    }

    private fun playing(polled: Polled): Long {
        val left = polled.remainingMs ?: return config.playingPollMs
        if (polled.handOverRefused) return grown(config.settlePollMs, config.nearEndPollMs, refusals++)
        if (left < config.endGraceMs) return grown(config.settlePollMs, config.playingPollMs, overruns++)
        overruns = 0
        val toNearEnd = left - maxOf(config.nearEndWindowMs, config.handoverLeadMs)
        val wait = if (toNearEnd > 0) minOf(config.playingPollMs, toNearEnd) else config.nearEndPollMs
        return minOf(wait, left + config.endGraceMs).coerceAtLeast(config.settlePollMs)
    }
}

/** [firstMs], doubled [times] times, at most [maxMs]. */
internal fun grown(firstMs: Long, maxMs: Long, times: Int): Long =
    (firstMs shl times.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(maxMs)

private const val MAX_DOUBLINGS = 20
