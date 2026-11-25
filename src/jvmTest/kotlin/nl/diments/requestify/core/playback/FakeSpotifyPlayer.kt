package nl.diments.requestify.core.playback

import kotlinx.coroutines.CompletableDeferred
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.spotify.CurrentlyPlaying
import nl.diments.requestify.core.spotify.RecentTrack
import nl.diments.requestify.core.spotify.SpotifyDevice
import nl.diments.requestify.core.spotify.SpotifyPlayer
import nl.diments.requestify.core.spotify.SpotifyResult

/**
 * A steady pace, for tests of what a poll does rather than of when it comes: every 3 s, every second in a song's last
 * 20 s. [PollPaceTest] and [PlaybackControllerPaceTest] test the real pace.
 */
internal val STEADY_POLLS = PlaybackConfig(
    playingPollMs = 3_000,
    nearEndPollMs = 1_000,
    nearEndWindowMs = 20_000,
    pausedPollMs = 3_000,
    pausedNearEndPollMs = 3_000,
    nothingPollMs = 3_000,
    nothingPollMaxMs = 3_000,
    failedPollMaxMs = 3_000,
    upcomingRefreshMs = 30_000,
    recentlyPlayedRefreshMs = 60_000,
    deviceCheckMaxMs = 6_000,
)

/** A scriptable [SpotifyPlayer] that records every call with the (virtual) time it was made at. */
class FakeSpotifyPlayer(private val clock: () -> Long) : SpotifyPlayer {
    data class Call(val name: String, val arg: String?, val atMs: Long)

    val calls = mutableListOf<Call>()

    var currentlyPlayingResult: SpotifyResult<CurrentlyPlaying?> = SpotifyResult.Ok(null)

    /** When set, [currentlyPlaying] throws it instead of answering. */
    var currentlyPlayingError: Exception? = null
    var userQueueResult: SpotifyResult<List<String>> = SpotifyResult.Ok(emptyList())
    var queueTracks: SpotifyResult<List<Track>> = SpotifyResult.Ok(emptyList())

    /** When set, [userQueue] waits for it before answering, like a slow Spotify. */
    var queueGate: CompletableDeferred<Unit>? = null
    var skipResult: SpotifyResult<Unit> = SpotifyResult.Ok(Unit)
    var previousResult: SpotifyResult<Unit> = SpotifyResult.Ok(Unit)
    var recentResult: SpotifyResult<RecentTrack?> = SpotifyResult.Ok(null)

    /** When set, [recentlyPlayed] waits for it before answering, like a slow Spotify. */
    var recentGate: CompletableDeferred<Unit>? = null

    /** What [recentlyPlayedSince] answers, whatever time it is asked for. */
    var recentSinceResult: SpotifyResult<List<RecentTrack>> = SpotifyResult.Ok(emptyList())

    /** When set, [recentlyPlayedSince] waits for it before answering, like a slow Spotify. */
    var recentSinceGate: CompletableDeferred<Unit>? = null

    /** Answers for the next [addToQueue] calls, in order; `Ok` once used up. */
    val addToQueueResults = ArrayDeque<SpotifyResult<Unit>>()

    /** When set, [addToQueue] waits for it before answering, like a slow Spotify. */
    var addToQueueGate: CompletableDeferred<Unit>? = null

    /** When set, [currentlyPlaying] waits for it before answering, like a slow Spotify. */
    var currentlyPlayingGate: CompletableDeferred<Unit>? = null

    /** Answers for the next [pause] and [resume] calls, in order; `Ok` once used up. */
    val playCommandResults = ArrayDeque<SpotifyResult<Unit>>()

    /** When set, [pause] and [resume] wait for it before answering, like a slow Spotify. */
    var playCommandGate: CompletableDeferred<Unit>? = null

    /** What [devices] answers; by default one device that is active already, so nothing is transferred. */
    var devicesResult: SpotifyResult<List<SpotifyDevice>> =
        SpotifyResult.Ok(listOf(SpotifyDevice("pc", isActive = true, isRestricted = false)))

    /**
     * Answers for the next [transferPlayback] calls, in order; `Ok` once used up. One that works makes that device
     * the active one in [devicesResult], as Spotify does.
     */
    val transferResults = ArrayDeque<SpotifyResult<Unit>>()

    /**
     * After a [pause] or [resume] that worked, this many polls still report the play state from before it, like
     * Spotify's Web API, which takes a moment to catch up.
     */
    var stalePollsAfterPlayCommand = 0
    private var stalePollsLeft = 0
    private var staleIsPlaying = false

    fun plays(track: Track?, progressMs: Long = 0, isPlaying: Boolean = true) {
        currentlyPlayingResult = SpotifyResult.Ok(CurrentlyPlaying(track, progressMs, isPlaying))
    }

    fun callsTo(name: String): List<Call> = calls.filter { it.name == name }

    override suspend fun currentlyPlaying(): SpotifyResult<CurrentlyPlaying?> {
        record("currentlyPlaying")
        currentlyPlayingGate?.await()
        currentlyPlayingError?.let { throw it }
        val current = (currentlyPlayingResult as? SpotifyResult.Ok)?.value
        if (stalePollsLeft > 0 && current != null) {
            stalePollsLeft--
            return SpotifyResult.Ok(current.copy(isPlaying = staleIsPlaying))
        }
        return currentlyPlayingResult
    }

    override suspend fun userQueueTrackIds(): SpotifyResult<List<String>> {
        record("userQueueTrackIds")
        return userQueueResult
    }

    override suspend fun userQueue(): SpotifyResult<List<Track>> {
        record("userQueue")
        queueGate?.await()
        return queueTracks
    }

    override suspend fun recentlyPlayed(): SpotifyResult<RecentTrack?> {
        record("recentlyPlayed")
        recentGate?.await()
        return recentResult
    }

    /** Recorded with [afterEpochMs] as its argument. */
    override suspend fun recentlyPlayedSince(afterEpochMs: Long): SpotifyResult<List<RecentTrack>> {
        record("recentlyPlayedSince", afterEpochMs.toString())
        recentSinceGate?.await()
        return recentSinceResult
    }

    override suspend fun addToQueue(trackUri: String): SpotifyResult<Unit> {
        record("addToQueue", trackUri)
        addToQueueGate?.await()
        return addToQueueResults.removeFirstOrNull() ?: SpotifyResult.Ok(Unit)
    }

    override suspend fun skipNext(): SpotifyResult<Unit> {
        record("skipNext")
        return skipResult
    }

    override suspend fun skipPrevious(): SpotifyResult<Unit> {
        record("skipPrevious")
        return previousResult
    }

    override suspend fun devices(): SpotifyResult<List<SpotifyDevice>> {
        record("devices")
        return devicesResult
    }

    /** Recorded with [deviceId] as its argument. */
    override suspend fun transferPlayback(deviceId: String): SpotifyResult<Unit> {
        record("transferPlayback", deviceId)
        val result = transferResults.removeFirstOrNull() ?: SpotifyResult.Ok(Unit)
        val listed = (devicesResult as? SpotifyResult.Ok)?.value
        if (result is SpotifyResult.Ok && listed != null) {
            devicesResult = SpotifyResult.Ok(listed.map { it.copy(isActive = it.id == deviceId) })
        }
        return result
    }

    override suspend fun pause(): SpotifyResult<Unit> = playCommand("pause", isPlaying = false)

    override suspend fun resume(): SpotifyResult<Unit> = playCommand("resume", isPlaying = true)

    private suspend fun playCommand(name: String, isPlaying: Boolean): SpotifyResult<Unit> {
        record(name)
        playCommandGate?.await()
        val result = playCommandResults.removeFirstOrNull() ?: SpotifyResult.Ok(Unit)
        if (result is SpotifyResult.Ok) setIsPlaying(isPlaying)
        return result
    }

    private fun setIsPlaying(isPlaying: Boolean) {
        val current = (currentlyPlayingResult as? SpotifyResult.Ok)?.value ?: return
        staleIsPlaying = current.isPlaying
        stalePollsLeft = stalePollsAfterPlayCommand
        currentlyPlayingResult = SpotifyResult.Ok(current.copy(isPlaying = isPlaying))
    }

    private fun record(name: String, arg: String? = null) {
        calls += Call(name, arg, clock())
    }
}
