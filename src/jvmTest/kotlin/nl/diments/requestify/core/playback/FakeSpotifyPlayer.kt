package nl.diments.requestify.core.playback

import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.spotify.CurrentlyPlaying
import nl.diments.requestify.core.spotify.SpotifyPlayer
import nl.diments.requestify.core.spotify.SpotifyResult

/** A scriptable [SpotifyPlayer] that records every call with the (virtual) time it was made at. */
class FakeSpotifyPlayer(private val clock: () -> Long) : SpotifyPlayer {
    data class Call(val name: String, val arg: String?, val atMs: Long)

    val calls = mutableListOf<Call>()

    var currentlyPlayingResult: SpotifyResult<CurrentlyPlaying?> = SpotifyResult.Ok(null)

    /** When set, [currentlyPlaying] throws it instead of answering. */
    var currentlyPlayingError: Exception? = null
    var userQueueResult: SpotifyResult<List<String>> = SpotifyResult.Ok(emptyList())

    /** Answers for the next [addToQueue] calls, in order; `Ok` once used up. */
    val addToQueueResults = ArrayDeque<SpotifyResult<Unit>>()

    fun plays(track: Track?, progressMs: Long = 0, isPlaying: Boolean = true) {
        currentlyPlayingResult = SpotifyResult.Ok(CurrentlyPlaying(track, progressMs, isPlaying))
    }

    fun callsTo(name: String): List<Call> = calls.filter { it.name == name }

    override suspend fun currentlyPlaying(): SpotifyResult<CurrentlyPlaying?> {
        record("currentlyPlaying")
        currentlyPlayingError?.let { throw it }
        return currentlyPlayingResult
    }

    override suspend fun userQueueTrackIds(): SpotifyResult<List<String>> {
        record("userQueueTrackIds")
        return userQueueResult
    }

    override suspend fun addToQueue(trackUri: String): SpotifyResult<Unit> {
        record("addToQueue", trackUri)
        return addToQueueResults.removeFirstOrNull() ?: SpotifyResult.Ok(Unit)
    }

    override suspend fun skipNext(): SpotifyResult<Unit> {
        record("skipNext")
        return SpotifyResult.Ok(Unit)
    }

    override suspend fun pause(): SpotifyResult<Unit> {
        record("pause")
        setIsPlaying(false)
        return SpotifyResult.Ok(Unit)
    }

    override suspend fun resume(): SpotifyResult<Unit> {
        record("resume")
        setIsPlaying(true)
        return SpotifyResult.Ok(Unit)
    }

    private fun setIsPlaying(isPlaying: Boolean) {
        val current = (currentlyPlayingResult as? SpotifyResult.Ok)?.value ?: return
        currentlyPlayingResult = SpotifyResult.Ok(current.copy(isPlaying = isPlaying))
    }

    private fun record(name: String, arg: String? = null) {
        calls += Call(name, arg, clock())
    }
}
