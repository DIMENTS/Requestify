package nl.diments.requestify.core.spotify

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.storage.AppJson

sealed interface SpotifyResult<out T> {
    data class Ok<T>(val value: T) : SpotifyResult<T>
    data object NoActiveDevice : SpotifyResult<Nothing>
    data object PremiumRequired : SpotifyResult<Nothing>
    data class RateLimited(val retryAfterMs: Long) : SpotifyResult<Nothing>

    /** The saved login stopped working (expired, revoked, or the Spotify app is gone): log in again. */
    data object LoginNeeded : SpotifyResult<Nothing>

    /** Not logged in to Spotify at all (never, or logged out). */
    data object LoggedOut : SpotifyResult<Nothing>

    /** Spotify refused the call because the login was granted without the needed scope: log in again to grant it. */
    data object MissingPermission : SpotifyResult<Nothing>
    data class Failed(val message: String) : SpotifyResult<Nothing>
}

/** The [SpotifyResult.Failed.message] when the login is fine but no access token could be had right now. */
const val TOKEN_UNAVAILABLE = "token unavailable"

/** [track] is null while an ad or a podcast episode plays. */
data class CurrentlyPlaying(val track: Track?, val progressMs: Long, val isPlaying: Boolean)

/** The last track that played and when Spotify logged it, which is when its play ended, in epoch milliseconds. */
data class RecentTrack(val track: Track, val playedAtEpochMs: Long)

/** The port the playback controller uses. */
interface SpotifyPlayer {
    /** `Ok(null)` when nothing is playing (HTTP 204). */
    suspend fun currentlyPlaying(): SpotifyResult<CurrentlyPlaying?>
    suspend fun userQueueTrackIds(): SpotifyResult<List<String>>

    /** The user queue as usable tracks, in order (items without a usable track are dropped). */
    suspend fun userQueue(): SpotifyResult<List<Track>>

    /** The most recently played track, `Ok(null)` when there is none. */
    suspend fun recentlyPlayed(): SpotifyResult<RecentTrack?>
    suspend fun addToQueue(trackUri: String): SpotifyResult<Unit>
    suspend fun skipNext(): SpotifyResult<Unit>
    suspend fun skipPrevious(): SpotifyResult<Unit>
    suspend fun pause(): SpotifyResult<Unit>
    suspend fun resume(): SpotifyResult<Unit>
}

interface SpotifyCatalog {
    /** [limit] is coerced to 1..10 (dev-mode cap). */
    suspend fun searchTracks(query: String, limit: Int = 5): SpotifyResult<List<Track>>
    suspend fun track(id: String): SpotifyResult<Track>

    /** From `GET /me` `product` when present, else `Ok(null)`. */
    suspend fun isPremium(): SpotifyResult<Boolean?>
}

/**
 * Spotify Web API client. [token] returns the bearer token or why there is none; it is called with
 * `forceRefresh = true` once after a 401. A token that is only unavailable for now is a [SpotifyResult.Failed]
 * (a network problem), never a login problem. Every failure is returned as a [SpotifyResult], never thrown
 * (except coroutine cancellation).
 */
class SpotifyApi(
    private val http: HttpClient,
    private val token: suspend (forceRefresh: Boolean) -> TokenResult,
    private val baseUrl: String = "https://api.spotify.com/v1",
    private val requestTimeoutMs: Long = 15_000,
) : SpotifyPlayer, SpotifyCatalog {

    private val json: Json = AppJson.instance

    override suspend fun currentlyPlaying(): SpotifyResult<CurrentlyPlaying?> =
        execute(HttpMethod.Get, "me", "player", "currently-playing") { body ->
            if (body.isBlank()) return@execute null
            val dto = json.decodeFromString<CurrentlyPlayingDto>(body)
            val track = dto.item?.takeIf { it.type == "track" }?.toTrackOrNull()
            CurrentlyPlaying(track, dto.progressMs ?: 0L, dto.isPlaying)
        }

    override suspend fun userQueueTrackIds(): SpotifyResult<List<String>> =
        execute(HttpMethod.Get, "me", "player", "queue") { body ->
            json.decodeFromString<QueueDto>(body).queue.filter { it.type == "track" }.mapNotNull { it.id?.takeIf(String::isNotBlank) }
        }

    override suspend fun userQueue(): SpotifyResult<List<Track>> =
        execute(HttpMethod.Get, "me", "player", "queue") { body ->
            json.decodeFromString<TrackQueueDto>(body).queue.filter { it.type == "track" }.mapNotNull { it.toTrackOrNull() }
        }

    override suspend fun recentlyPlayed(): SpotifyResult<RecentTrack?> =
        execute(HttpMethod.Get, "me", "player", "recently-played", params = listOf("limit" to "1")) { body ->
            val item = json.decodeFromString<RecentlyPlayedDto>(body).items.firstOrNull() ?: return@execute null
            val track = item.track?.toTrackOrNull() ?: return@execute null
            val playedAt = runCatching { Instant.parse(item.playedAt).toEpochMilli() }.getOrNull() ?: return@execute null
            RecentTrack(track, playedAt)
        }

    override suspend fun addToQueue(trackUri: String): SpotifyResult<Unit> =
        execute(HttpMethod.Post, "me", "player", "queue", params = listOf("uri" to trackUri)) { }

    override suspend fun skipNext(): SpotifyResult<Unit> =
        execute(HttpMethod.Post, "me", "player", "next") { }

    override suspend fun skipPrevious(): SpotifyResult<Unit> =
        execute(HttpMethod.Post, "me", "player", "previous") { }

    override suspend fun pause(): SpotifyResult<Unit> =
        execute(HttpMethod.Put, "me", "player", "pause") { }

    override suspend fun resume(): SpotifyResult<Unit> =
        execute(HttpMethod.Put, "me", "player", "play") { }

    override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> =
        execute(
            HttpMethod.Get,
            "search",
            params = listOf("q" to query, "type" to "track", "limit" to limit.coerceIn(1, 10).toString()),
        ) { body ->
            json.decodeFromString<SearchDto>(body).tracks.items.mapNotNull { it?.toTrackOrNull() }
        }

    override suspend fun track(id: String): SpotifyResult<Track> =
        execute(HttpMethod.Get, "tracks", id) { body -> json.decodeFromString<TrackDto>(body).toTrackOrNull() ?: error("track has no usable id, uri or name") }

    override suspend fun isPremium(): SpotifyResult<Boolean?> =
        execute(HttpMethod.Get, "me") { body ->
            json.decodeFromString<MeDto>(body).product?.let { it == "premium" }
        }

    private class Raw(val status: Int, val retryAfter: String?, val body: String)

    private suspend fun send(
        method: HttpMethod,
        segments: List<String>,
        params: List<Pair<String, String>>,
        bearer: String,
    ): Raw = withTimeout(requestTimeoutMs) {
        val response = http.request {
            this.method = method
            url(
                URLBuilder(baseUrl).apply {
                    appendPathSegments(segments)
                    params.forEach { (k, v) -> parameters.append(k, v) }
                }.build(),
            )
            header(HttpHeaders.Authorization, "Bearer $bearer")
        }
        Raw(response.status.value, response.headers[HttpHeaders.RetryAfter], response.bodyAsText())
    }

    /** The shared request helper: auth + one forced-refresh retry, error mapping, lenient success parsing. */
    private suspend fun <T> execute(
        method: HttpMethod,
        vararg segments: String,
        params: List<Pair<String, String>> = emptyList(),
        onSuccess: (body: String) -> T,
    ): SpotifyResult<T> = try {
        val path = segments.toList()
        withToken(token(false)) { bearer ->
            val raw = send(method, path, params, bearer)
            if (raw.status != 401) {
                map(raw, onSuccess)
            } else {
                withToken(token(true)) { refreshed ->
                    val retried = send(method, path, params, refreshed)
                    if (retried.status == 401) SpotifyResult.LoginNeeded else map(retried, onSuccess)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        SpotifyResult.Failed("timeout")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpotifyResult.Failed(e.message ?: e::class.simpleName ?: "error")
    }

    /** Runs [call] with the bearer token, or answers why there is none. */
    private inline fun <T> withToken(result: TokenResult, call: (bearer: String) -> SpotifyResult<T>): SpotifyResult<T> =
        when (result) {
            is TokenResult.Ok -> call(result.token)
            TokenResult.LoggedOut -> SpotifyResult.LoggedOut
            TokenResult.LoginNeeded -> SpotifyResult.LoginNeeded
            TokenResult.Unavailable -> SpotifyResult.Failed(TOKEN_UNAVAILABLE)
        }

    private fun <T> map(raw: Raw, onSuccess: (String) -> T): SpotifyResult<T> = when (raw.status) {
        200, 201, 202, 204 -> SpotifyResult.Ok(onSuccess(raw.body))
        403 -> forbidden(raw.body)
        404 -> if (errorReason(raw.body) == "NO_ACTIVE_DEVICE") {
            SpotifyResult.NoActiveDevice
        } else {
            SpotifyResult.Failed("HTTP 404")
        }
        429 -> SpotifyResult.RateLimited(
            raw.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 } ?: DEFAULT_RETRY_AFTER_MS,
        )
        else -> SpotifyResult.Failed("HTTP ${raw.status}")
    }

    private fun forbidden(body: String): SpotifyResult<Nothing> {
        val error = runCatching { json.decodeFromString<ErrorEnvelope>(body).error }.getOrNull()
        return when {
            error?.reason == "PREMIUM_REQUIRED" -> SpotifyResult.PremiumRequired
            error?.message?.contains("scope", ignoreCase = true) == true -> SpotifyResult.MissingPermission
            else -> SpotifyResult.Failed("HTTP 403")
        }
    }

    private fun errorReason(body: String): String? =
        runCatching { json.decodeFromString<ErrorEnvelope>(body).error?.reason }.getOrNull()

    private companion object {
        const val DEFAULT_RETRY_AFTER_MS = 5_000L
        const val ART_TARGET_WIDTH = 300
    }

    @Serializable
    private class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private class ErrorBody(val reason: String? = null, val message: String? = null)

    @Serializable
    private class CurrentlyPlayingDto(
        @SerialName("progress_ms") val progressMs: Long? = null,
        @SerialName("is_playing") val isPlaying: Boolean = false,
        val item: TrackDto? = null,
    )

    @Serializable
    private class QueueDto(val queue: List<QueueItemDto> = emptyList())

    @Serializable
    private class TrackQueueDto(val queue: List<TrackDto> = emptyList())

    @Serializable
    private class RecentlyPlayedDto(val items: List<RecentItemDto> = emptyList())

    @Serializable
    private class RecentItemDto(@SerialName("played_at") val playedAt: String? = null, val track: TrackDto? = null)

    @Serializable
    private class QueueItemDto(val id: String? = null, val type: String? = null)

    @Serializable
    private class SearchDto(val tracks: TracksPage = TracksPage())

    @Serializable
    private class TracksPage(val items: List<TrackDto?> = emptyList())

    @Serializable
    private class MeDto(val product: String? = null)

    @Serializable
    private class TrackDto(
        val id: String? = null,
        val uri: String? = null,
        val name: String? = null,
        val type: String? = null,
        @SerialName("duration_ms") val durationMs: Long = 0L,
        val explicit: Boolean = false,
        val artists: List<ArtistDto> = emptyList(),
        val album: AlbumDto? = null,
    ) {
        /** Null for items without a usable id, uri or name (e.g. local files, episodes). */
        fun toTrackOrNull(): Track? {
            if (id.isNullOrBlank() || uri.isNullOrBlank() || name.isNullOrBlank()) return null
            return Track(
                id = id,
                uri = uri,
                name = name,
                artists = artists.mapNotNull { it.name },
                durationMs = durationMs,
                explicit = explicit,
                albumArtUrl = album?.images
                    ?.minByOrNull { abs((it.width ?: Int.MAX_VALUE / 2) - ART_TARGET_WIDTH) }?.url,
            )
        }
    }

    @Serializable
    private class ArtistDto(val name: String? = null)

    @Serializable
    private class AlbumDto(val images: List<ImageDto> = emptyList())

    @Serializable
    private class ImageDto(val url: String, val width: Int? = null)
}
