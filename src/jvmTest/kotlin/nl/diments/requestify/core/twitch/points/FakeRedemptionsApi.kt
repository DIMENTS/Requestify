package nl.diments.requestify.core.twitch.points

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.twitch.Redemption
import nl.diments.requestify.core.twitch.TwitchApi

/**
 * A [TwitchApi] on a mock engine for the redemption calls: it lists [unfulfilled] as the test sets it (a status
 * update does not change it) and records every status update with the (virtual) time it was made at.
 */
class FakeRedemptionsApi(private val clock: () -> Long = { 0L }) {
    data class Patch(val rewardId: String, val redemptionId: String, val status: String, val atMs: Long)

    /** What the redemptions list answers, oldest first. */
    val unfulfilled = CopyOnWriteArrayList<Redemption>()

    /** The reward ids whose unfulfilled redemptions were asked for. */
    val lists = CopyOnWriteArrayList<String>()
    val patches = CopyOnWriteArrayList<Patch>()

    /** HTTP statuses for the next status updates, in order; 200 once used up. */
    val patchStatuses = ConcurrentLinkedQueue<Int>()

    /** Runs at each status update, before it is answered. */
    @Volatile var onPatch: (Patch) -> Unit = {}

    /** What the token source answers; every call counts in [tokenCalls]. */
    @Volatile var token: TokenResult = TokenResult.Ok("tok")
    val tokenCalls = AtomicInteger()

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    val api = TwitchApi(
        http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    request.method == HttpMethod.Get && path.endsWith("/custom_rewards/redemptions") -> {
                        val reward = request.url.parameters["reward_id"].orEmpty()
                        lists += reward
                        val data = buildJsonArray {
                            unfulfilled.filter { it.rewardId == reward }.forEach { add(redemptionJson(it)) }
                        }
                        respond("{\"data\":$data,\"pagination\":{}}", HttpStatusCode.OK, json)
                    }
                    request.method == HttpMethod.Patch && path.endsWith("/custom_rewards/redemptions") -> {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val patch = Patch(
                            rewardId = request.url.parameters["reward_id"].orEmpty(),
                            redemptionId = request.url.parameters["id"].orEmpty(),
                            status = body.getValue("status").jsonPrimitive.content,
                            atMs = clock(),
                        )
                        onPatch(patch)
                        patches += patch
                        val status = patchStatuses.poll() ?: 200
                        respond(if (status == 200) "{\"data\":[]}" else "{}", HttpStatusCode.fromValue(status), json)
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, json)
                }
            },
        ),
        token = {
            tokenCalls.incrementAndGet()
            token
        },
        broadcasterId = { "b1" },
        baseUrl = "https://twitch.test/helix",
    )

    private fun redemptionJson(r: Redemption) = buildJsonObject {
        put("id", r.id)
        put("user_id", r.userId)
        put("user_login", r.userLogin)
        put("user_name", r.userName)
        put("user_input", r.userInput)
        put("status", "UNFULFILLED")
        put("redeemed_at", Instant.ofEpochMilli(r.redeemedAtEpochMs).toString())
        putJsonObject("reward") { put("id", r.rewardId) }
    }
}
