package nl.diments.requestify.core.twitch

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.storage.AppJson
import nl.diments.requestify.core.twitch.auth.TWITCH_CLIENT_ID

sealed interface TwitchResult<out T> {
    data class Ok<T>(val value: T) : TwitchResult<T>
    data object NotFound : TwitchResult<Nothing>
    data class Forbidden(val message: String) : TwitchResult<Nothing>
    data class BadRequest(val message: String) : TwitchResult<Nothing>

    /** The saved login stopped working (or Twitch kept answering 401): log in again. */
    data object LoginNeeded : TwitchResult<Nothing>

    /** Not logged in to Twitch at all. */
    data object LoggedOut : TwitchResult<Nothing>
    data class RateLimited(val retryAfterMs: Long) : TwitchResult<Nothing>
    data class Failed(val message: String) : TwitchResult<Nothing>
}

data class TwitchChannel(val id: String, val login: String, val displayName: String, val broadcasterType: String)

data class TwitchUserRef(val id: String, val login: String, val displayName: String)

/** [isSent] is false when Twitch accepted the call but dropped the message (duplicate, slow mode, AutoMod, ...). */
data class SentChat(val messageId: String, val isSent: Boolean)

data class Reward(
    val id: String,
    val title: String,
    val cost: Int,
    val prompt: String,
    val isEnabled: Boolean,
    val isPaused: Boolean,
    val isUserInputRequired: Boolean,
    val skipsRequestQueue: Boolean,
)

data class RewardSpec(val title: String, val cost: Int, val prompt: String)

/** Only the non-null fields are sent. */
data class RewardPatch(
    val title: String? = null,
    val cost: Int? = null,
    val prompt: String? = null,
    val isEnabled: Boolean? = null,
    val isPaused: Boolean? = null,
)

data class Redemption(
    val id: String,
    val rewardId: String,
    val userId: String,
    val userLogin: String,
    val userName: String,
    val userInput: String,
    val redeemedAtEpochMs: Long,
)

enum class RedemptionStatus { FULFILLED, CANCELED }

/**
 * Twitch Helix client on the broadcaster's own user token. [token] returns the bearer token or why there is none;
 * it is called with `forceRefresh = true` once after a 401. [broadcasterId] is the logged-in user's id (null when
 * logged out, which answers [TwitchResult.LoggedOut]). Every failure is returned as a [TwitchResult], never thrown
 * (except coroutine cancellation).
 */
class TwitchApi(
    private val http: HttpClient,
    private val token: suspend (forceRefresh: Boolean) -> TokenResult,
    private val broadcasterId: () -> String?,
    private val baseUrl: String = "https://api.twitch.tv/helix",
    private val requestTimeoutMs: Long = 15_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = AppJson.instance

    suspend fun me(): TwitchResult<TwitchChannel> =
        execute(HttpMethod.Get, "users") { body ->
            val user = json.decodeFromString<UsersDto>(body).data.firstOrNull() ?: error("no user in response")
            TwitchChannel(user.id, user.login, user.displayName, user.broadcasterType)
        }

    suspend fun userByLogin(login: String): TwitchResult<TwitchUserRef?> =
        execute(HttpMethod.Get, "users", params = listOf("login" to login)) { body ->
            json.decodeFromString<UsersDto>(body).data.firstOrNull()?.let { TwitchUserRef(it.id, it.login, it.displayName) }
        }

    suspend fun isLive(): TwitchResult<Boolean> = withBroadcaster { id ->
        execute(HttpMethod.Get, "streams", params = listOf("user_id" to id)) { body ->
            json.decodeFromString<StreamsDto>(body).data.any { it.type == "live" }
        }
    }

    /** Needs `moderator:read:followers`; without it Twitch silently answers an empty list, i.e. not following. */
    suspend fun follows(userId: String): TwitchResult<Boolean> = withBroadcaster { id ->
        execute(
            HttpMethod.Get,
            "channels",
            "followers",
            params = listOf("broadcaster_id" to id, "user_id" to userId),
        ) { body -> json.decodeFromString<FollowersDto>(body).data.isNotEmpty() }
    }

    suspend fun sendChat(message: String, replyTo: String?): TwitchResult<SentChat> = withBroadcaster { id ->
        val body = buildJsonObject {
            put("broadcaster_id", id)
            put("sender_id", id)
            put("message", message.take(MAX_CHAT_LENGTH))
            if (replyTo != null) put("reply_parent_message_id", replyTo)
        }
        execute(HttpMethod.Post, "chat", "messages", body = body) { response ->
            val sent = json.decodeFromString<SentChatDto>(response).data.firstOrNull() ?: error("no message in response")
            SentChat(sent.messageId, sent.isSent)
        }
    }

    suspend fun rewards(onlyManageable: Boolean): TwitchResult<List<Reward>> = withBroadcaster { id ->
        val params = buildList {
            add("broadcaster_id" to id)
            if (onlyManageable) add("only_manageable_rewards" to "true")
        }
        execute(HttpMethod.Get, "channel_points", "custom_rewards", params = params) { body ->
            json.decodeFromString<RewardsDto>(body).data.map { it.toReward() }
        }
    }

    /** A reward that needs viewer input and does not skip the queue (so rejected requests can be refunded). */
    suspend fun createReward(spec: RewardSpec): TwitchResult<Reward> = withBroadcaster { id ->
        val body = buildJsonObject {
            put("title", spec.title)
            put("cost", spec.cost)
            put("prompt", spec.prompt)
            put("is_user_input_required", true)
            put("should_redemptions_skip_request_queue", false)
        }
        execute(
            HttpMethod.Post,
            "channel_points",
            "custom_rewards",
            params = listOf("broadcaster_id" to id),
            body = body,
        ) { response -> singleReward(response) }
    }

    suspend fun updateReward(id: String, patch: RewardPatch): TwitchResult<Reward> = withBroadcaster { broadcaster ->
        val body = buildJsonObject {
            patch.title?.let { put("title", it) }
            patch.cost?.let { put("cost", it) }
            patch.prompt?.let { put("prompt", it) }
            patch.isEnabled?.let { put("is_enabled", it) }
            patch.isPaused?.let { put("is_paused", it) }
        }
        execute(
            HttpMethod.Patch,
            "channel_points",
            "custom_rewards",
            params = listOf("broadcaster_id" to broadcaster, "id" to id),
            body = body,
        ) { response -> singleReward(response) }
    }

    /** Every unfulfilled redemption of [rewardId], oldest first, across all pages. */
    suspend fun unfulfilledRedemptions(rewardId: String): TwitchResult<List<Redemption>> = withBroadcaster { id ->
        val all = mutableListOf<Redemption>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val params = buildList {
                add("broadcaster_id" to id)
                add("reward_id" to rewardId)
                add("status" to "UNFULFILLED")
                add("first" to PAGE_SIZE.toString())
                if (cursor != null) add("after" to cursor)
            }
            val page = execute(
                HttpMethod.Get,
                "channel_points",
                "custom_rewards",
                "redemptions",
                params = params,
            ) { body -> json.decodeFromString<RedemptionsDto>(body) }
            val dto = when (page) {
                is TwitchResult.Ok -> page.value
                is TwitchResult.NotFound -> return@withBroadcaster TwitchResult.NotFound
                is TwitchResult.Forbidden -> return@withBroadcaster page
                is TwitchResult.BadRequest -> return@withBroadcaster page
                is TwitchResult.LoginNeeded -> return@withBroadcaster page
                is TwitchResult.LoggedOut -> return@withBroadcaster page
                is TwitchResult.RateLimited -> return@withBroadcaster page
                is TwitchResult.Failed -> return@withBroadcaster page
            }
            all += dto.data.map { it.toRedemption() }
            val next = dto.pagination?.cursor?.takeIf { it.isNotBlank() && it != cursor }
            pages++
            if (next == null || pages >= MAX_PAGES) break
            cursor = next
        }
        TwitchResult.Ok(all.toList())
    }

    /** A 404 means the redemption is not (or no longer) unfulfilled, e.g. already handled in the dashboard. */
    suspend fun setRedemptionStatus(
        rewardId: String,
        redemptionId: String,
        status: RedemptionStatus,
    ): TwitchResult<Unit> = withBroadcaster { id ->
        execute(
            HttpMethod.Patch,
            "channel_points",
            "custom_rewards",
            "redemptions",
            params = listOf("id" to redemptionId, "broadcaster_id" to id, "reward_id" to rewardId),
            body = buildJsonObject { put("status", status.name) },
        ) { }
    }

    /** Subscribes on the WebSocket session; "already exists" (409) counts as success. */
    suspend fun subscribe(
        type: String,
        version: String,
        condition: Map<String, String>,
        sessionId: String,
    ): TwitchResult<Unit> {
        val body = buildJsonObject {
            put("type", type)
            put("version", version)
            putJsonObject("condition") { condition.forEach { (k, v) -> put(k, v) } }
            putJsonObject("transport") {
                put("method", "websocket")
                put("session_id", sessionId)
            }
        }
        return execute(HttpMethod.Post, "eventsub", "subscriptions", body = body, conflictIsOk = true) { }
    }

    private inline fun <T> withBroadcaster(call: (id: String) -> TwitchResult<T>): TwitchResult<T> {
        val id = broadcasterId()?.takeIf { it.isNotBlank() } ?: return TwitchResult.LoggedOut
        return call(id)
    }

    private fun singleReward(body: String): Reward =
        json.decodeFromString<RewardsDto>(body).data.firstOrNull()?.toReward() ?: error("no reward in response")

    private class Raw(val status: Int, val rateLimitReset: String?, val body: String)

    private suspend fun send(
        method: HttpMethod,
        segments: List<String>,
        params: List<Pair<String, String>>,
        body: JsonObject?,
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
            header("Client-Id", TWITCH_CLIENT_ID)
            header(HttpHeaders.Authorization, "Bearer $bearer")
            if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        Raw(response.status.value, response.headers["Ratelimit-Reset"], response.bodyAsText())
    }

    /** The shared request helper: auth + one forced-refresh retry, error mapping, lenient success parsing. */
    private suspend fun <T> execute(
        method: HttpMethod,
        vararg segments: String,
        params: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
        conflictIsOk: Boolean = false,
        onSuccess: (body: String) -> T,
    ): TwitchResult<T> = try {
        val path = segments.toList()
        withToken(token(false)) { bearer ->
            val raw = send(method, path, params, body, bearer)
            if (raw.status != 401) {
                map(raw, conflictIsOk, onSuccess)
            } else {
                withToken(token(true)) { refreshed ->
                    val retried = send(method, path, params, body, refreshed)
                    if (retried.status == 401) TwitchResult.LoginNeeded else map(retried, conflictIsOk, onSuccess)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        TwitchResult.Failed("timeout")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TwitchResult.Failed(e.message ?: e::class.simpleName ?: "error")
    }

    private inline fun <T> withToken(result: TokenResult, call: (bearer: String) -> TwitchResult<T>): TwitchResult<T> =
        when (result) {
            is TokenResult.Ok -> call(result.token)
            TokenResult.LoggedOut -> TwitchResult.LoggedOut
            TokenResult.LoginNeeded -> TwitchResult.LoginNeeded
            TokenResult.Unavailable -> TwitchResult.Failed(TOKEN_UNAVAILABLE)
        }

    private fun <T> map(raw: Raw, conflictIsOk: Boolean, onSuccess: (String) -> T): TwitchResult<T> = when (raw.status) {
        200, 202, 204 -> TwitchResult.Ok(onSuccess(raw.body))
        409 -> if (conflictIsOk) TwitchResult.Ok(onSuccess("")) else TwitchResult.Failed("HTTP 409")
        400 -> TwitchResult.BadRequest(errorMessage(raw))
        403 -> TwitchResult.Forbidden(errorMessage(raw))
        404 -> TwitchResult.NotFound
        429 -> TwitchResult.RateLimited(retryAfterMs(raw.rateLimitReset))
        else -> TwitchResult.Failed("HTTP ${raw.status}")
    }

    private fun retryAfterMs(reset: String?): Long =
        reset?.trim()?.toLongOrNull()?.let { (it * 1000 - nowMs()).coerceAtLeast(0) } ?: DEFAULT_RETRY_AFTER_MS

    private fun errorMessage(raw: Raw): String =
        runCatching { json.decodeFromString<ErrorDto>(raw.body).message }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "HTTP ${raw.status}"

    private companion object {
        const val MAX_CHAT_LENGTH = 500
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 100
        const val DEFAULT_RETRY_AFTER_MS = 5_000L
        const val TOKEN_UNAVAILABLE = "token unavailable"
    }

    @Serializable
    private class ErrorDto(val message: String? = null)

    @Serializable
    private class UsersDto(val data: List<UserDto> = emptyList())

    @Serializable
    private class UserDto(
        val id: String,
        val login: String,
        @SerialName("display_name") val displayName: String,
        @SerialName("broadcaster_type") val broadcasterType: String = "",
    )

    @Serializable
    private class StreamsDto(val data: List<StreamDto> = emptyList())

    @Serializable
    private class StreamDto(val type: String? = null)

    @Serializable
    private class FollowersDto(val data: List<FollowerDto> = emptyList())

    @Serializable
    private class FollowerDto(@SerialName("user_id") val userId: String? = null)

    @Serializable
    private class SentChatDto(val data: List<SentDto> = emptyList())

    @Serializable
    private class SentDto(
        @SerialName("message_id") val messageId: String,
        @SerialName("is_sent") val isSent: Boolean = false,
    )

    @Serializable
    private class RewardsDto(val data: List<RewardDto> = emptyList())

    @Serializable
    private class RewardDto(
        val id: String,
        val title: String,
        val cost: Int,
        val prompt: String = "",
        @SerialName("is_enabled") val isEnabled: Boolean = true,
        @SerialName("is_paused") val isPaused: Boolean = false,
        @SerialName("is_user_input_required") val isUserInputRequired: Boolean = false,
        @SerialName("should_redemptions_skip_request_queue") val skipsRequestQueue: Boolean = false,
    ) {
        fun toReward() = Reward(id, title, cost, prompt, isEnabled, isPaused, isUserInputRequired, skipsRequestQueue)
    }

    @Serializable
    private class RedemptionsDto(val data: List<RedemptionDto> = emptyList(), val pagination: PaginationDto? = null)

    @Serializable
    private class PaginationDto(val cursor: String? = null)

    @Serializable
    private class RedemptionDto(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("user_login") val userLogin: String,
        @SerialName("user_name") val userName: String,
        @SerialName("user_input") val userInput: String = "",
        @SerialName("redeemed_at") val redeemedAt: String,
        val reward: RedemptionRewardDto,
    ) {
        fun toRedemption() = Redemption(
            id = id,
            rewardId = reward.id,
            userId = userId,
            userLogin = userLogin,
            userName = userName,
            userInput = userInput,
            redeemedAtEpochMs = Instant.parse(redeemedAt).toEpochMilli(),
        )
    }

    @Serializable
    private class RedemptionRewardDto(val id: String)
}
