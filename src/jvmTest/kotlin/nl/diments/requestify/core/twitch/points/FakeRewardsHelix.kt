package nl.diments.requestify.core.twitch.points

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.twitch.Reward
import nl.diments.requestify.core.twitch.TwitchApi

/**
 * A [TwitchApi] on a mock engine for the custom-rewards calls of one channel: it lists [rewards] (the manageable
 * ones, for `only_manageable_rewards`, are [manageable]), creates and patches them, and records every call.
 */
class FakeRewardsHelix {
    data class Call(val method: HttpMethod, val query: Map<String, String>, val body: JsonObject?) {
        val isPatch get() = method == HttpMethod.Patch
        val isCreate get() = method == HttpMethod.Post
    }

    val rewards = CopyOnWriteArrayList<Reward>()
    val manageable: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val calls = CopyOnWriteArrayList<Call>()

    /** The HTTP status the next create / update / list calls answer (400 on create is Twitch's duplicate title). */
    @Volatile var createStatus = 200

    @Volatile var updateStatus = 200

    @Volatile var listStatus = 200

    private var created = 0
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    fun reward(id: String, title: String = "Song request", cost: Int = 500, prompt: String = "Type a song") =
        Reward(id, title, cost, prompt, isEnabled = true, isPaused = false, isUserInputRequired = true, skipsRequestQueue = false)

    val patches get() = calls.filter { it.isPatch }
    val creates get() = calls.filter { it.isCreate }
    val deletes get() = calls.filter { it.method == HttpMethod.Delete }

    val api = TwitchApi(
        http = HttpClient(
            MockEngine { request ->
                val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
                val query = request.url.parameters.entries().associate { it.key to it.value.first() }
                calls += Call(request.method, query, body)
                when (request.method) {
                    HttpMethod.Get -> {
                        if (listStatus != 200) return@MockEngine respond("{}", HttpStatusCode.fromValue(listStatus), json)
                        val only = query["only_manageable_rewards"] == "true"
                        val list = rewards.filter { !only || it.id in manageable }
                        respond(
                            buildJsonObject { put("data", buildJsonArray { list.forEach { add(toJson(it)) } }) }.toString(),
                            HttpStatusCode.OK,
                            json,
                        )
                    }
                    HttpMethod.Post -> when (createStatus) {
                        400 -> respond(
                            """{"error":"Bad Request","status":400,"message":"CREATE_CUSTOM_REWARD_DUPLICATE_REWARD"}""",
                            HttpStatusCode.BadRequest,
                            json,
                        )
                        403 -> respond(
                            """{"error":"Forbidden","status":403,"message":"The broadcaster is not a partner or affiliate."}""",
                            HttpStatusCode.Forbidden,
                            json,
                        )
                        200 -> {
                            val b = body!!
                            val made = Reward(
                                id = "new-${++created}",
                                title = b.getValue("title").jsonPrimitive.content,
                                cost = b.getValue("cost").jsonPrimitive.intOrNull!!,
                                prompt = b["prompt"]?.jsonPrimitive?.content.orEmpty(),
                                isEnabled = true,
                                isPaused = false,
                                isUserInputRequired = b["is_user_input_required"]?.jsonPrimitive?.booleanOrNull == true,
                                skipsRequestQueue = b["should_redemptions_skip_request_queue"]?.jsonPrimitive?.booleanOrNull == true,
                            )
                            rewards += made
                            manageable += made.id
                            respond(single(made), HttpStatusCode.OK, json)
                        }
                        else -> respond("{}", HttpStatusCode.fromValue(createStatus), json)
                    }
                    HttpMethod.Patch -> {
                        if (updateStatus != 200) {
                            return@MockEngine respond("""{"status":$updateStatus,"message":"x"}""", HttpStatusCode.fromValue(updateStatus), json)
                        }
                        val id = query.getValue("id")
                        val old = rewards.first { it.id == id }
                        val b = body!!
                        val new = old.copy(
                            title = b["title"]?.jsonPrimitive?.content ?: old.title,
                            cost = b["cost"]?.jsonPrimitive?.intOrNull ?: old.cost,
                            prompt = b["prompt"]?.jsonPrimitive?.content ?: old.prompt,
                            isEnabled = b["is_enabled"]?.jsonPrimitive?.booleanOrNull ?: old.isEnabled,
                            isPaused = b["is_paused"]?.jsonPrimitive?.booleanOrNull ?: old.isPaused,
                        )
                        rewards[rewards.indexOf(old)] = new
                        respond(single(new), HttpStatusCode.OK, json)
                    }
                    else -> respond("{}", HttpStatusCode.NoContent, json)
                }
            },
        ),
        token = { TokenResult.Ok("tok") },
        broadcasterId = { "b1" },
        baseUrl = "https://twitch.test/helix",
    )

    private fun single(r: Reward) = buildJsonObject { put("data", buildJsonArray { add(toJson(r)) }) }.toString()

    private fun toJson(r: Reward) = buildJsonObject {
        put("id", r.id)
        put("title", r.title)
        put("cost", r.cost)
        put("prompt", r.prompt)
        put("is_enabled", r.isEnabled)
        put("is_paused", r.isPaused)
        put("is_user_input_required", r.isUserInputRequired)
        put("should_redemptions_skip_request_queue", r.skipsRequestQueue)
    }
}
