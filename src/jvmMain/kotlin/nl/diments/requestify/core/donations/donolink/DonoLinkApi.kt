package nl.diments.requestify.core.donations.donolink

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import nl.diments.requestify.core.storage.AppJson

/** What DonoLink answered. */
sealed interface DonoLinkResult<out T> {
    data class Ok<T>(val value: T) : DonoLinkResult<T>

    /** The key was refused (401/403), or there is none ([status] 0): a new key is needed. */
    data class KeyRefused(val status: Int) : DonoLinkResult<Nothing>

    /** 429 or 503: try again after [retryAfterMs]. */
    data class RateLimited(val retryAfterMs: Long) : DonoLinkResult<Nothing>

    data class Failed(val message: String) : DonoLinkResult<Nothing>
}

/** The key's owner and when the key stops working (null when DonoLink does not say). */
data class DonoLinkMe(val ownerName: String, val keyExpiresAtEpochMs: Long?)

/** One page of the activity feed; [items] are the raw activity objects, oldest first. */
data class DonoLinkPage(val items: List<JsonObject>, val hasMore: Boolean, val nextCursor: String?)

/** What [DonoLinkSource] needs from DonoLink. */
interface DonoLinkFeed {
    suspend fun me(): DonoLinkResult<DonoLinkMe>

    /** DonoLink's own donations recorded since [since] (inclusive, `recorded_at`), oldest first; [cursor] for the next page. */
    suspend fun activity(since: String, cursor: String?): DonoLinkResult<DonoLinkPage>
}

/**
 * DonoLink's REST API with the streamer's own API key (scope `activity:read`). It has no push: the source polls
 * [activity]. Every failure is an answer, never an exception.
 */
class DonoLinkApi(
    private val http: HttpClient,
    private val key: () -> String?,
    private val baseUrl: String = "https://donolink.nl/api/v1",
) : DonoLinkFeed {
    override suspend fun me(): DonoLinkResult<DonoLinkMe> = call("me", {}) { body ->
        val data = body["data"] as? JsonObject ?: return@call null
        val name = data.text("display_name")?.takeIf { it.isNotBlank() } ?: data.text("username") ?: return@call null
        val expires = (data["key"] as? JsonObject)?.text("expires_at")?.let(::parseInstant)
        DonoLinkMe(name, expires)
    }

    override suspend fun activity(since: String, cursor: String?): DonoLinkResult<DonoLinkPage> = call(
        "activity",
        {
            parameter("platform", "donolink")
            parameter("type", "donation")
            parameter("order", "asc")
            parameter("since", since)
            parameter("limit", PAGE_SIZE)
            if (cursor != null) parameter("cursor", cursor)
        },
    ) { body ->
        val items = (body["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@call null
        val pagination = body["pagination"] as? JsonObject
        DonoLinkPage(
            items = items,
            hasMore = (pagination?.get("has_more") as? JsonPrimitive)?.booleanOrNull ?: false,
            nextCursor = pagination?.text("next_cursor"),
        )
    }

    private suspend fun <T> call(
        path: String,
        params: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
        read: (JsonObject) -> T?,
    ): DonoLinkResult<T> {
        val apiKey = key()?.takeIf { it.isNotBlank() } ?: return DonoLinkResult.KeyRefused(0)
        val response: HttpResponse = try {
            http.get("$baseUrl/$path") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                params()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return DonoLinkResult.Failed(e.message ?: "network")
        } catch (e: Exception) {
            return DonoLinkResult.Failed(e::class.simpleName ?: "error")
        }
        val status = response.status.value
        return when {
            status == 401 || status == 403 -> DonoLinkResult.KeyRefused(status)
            status == 429 || status == 503 -> DonoLinkResult.RateLimited(retryAfterMs(response.headers[HttpHeaders.RetryAfter]))
            !response.status.isSuccess() -> DonoLinkResult.Failed("HTTP $status")
            else -> try {
                val body = AppJson.instance.parseToJsonElement(response.bodyAsText()).jsonObject
                read(body)?.let { DonoLinkResult.Ok(it) } ?: DonoLinkResult.Failed("unexpected answer")
            } catch (e: CancellationException) {
                throw e
            } catch (_: SerializationException) {
                DonoLinkResult.Failed("unexpected answer")
            } catch (_: IllegalArgumentException) {
                DonoLinkResult.Failed("unexpected answer")
            } catch (e: IOException) {
                DonoLinkResult.Failed(e.message ?: "network")
            }
        }
    }

    private fun retryAfterMs(header: String?): Long =
        header?.trim()?.toLongOrNull()?.let { (it * 1_000).coerceIn(0, MAX_WAIT_MS) } ?: DEFAULT_WAIT_MS

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val PAGE_SIZE = 100
        const val DEFAULT_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 300_000L
    }
}
