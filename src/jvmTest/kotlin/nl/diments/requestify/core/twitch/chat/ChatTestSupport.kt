package nl.diments.requestify.core.twitch.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.twitch.TwitchApi

/** A [TwitchApi] on a mock engine that records the chat messages sent and the follower lookups. */
class RecordingTwitchApi {
    data class Sent(val message: String, val replyTo: String?)

    val sends = CopyOnWriteArrayList<Sent>()
    val followerLookups = CopyOnWriteArrayList<String>()
    val followers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The HTTP status the follower lookups answer. */
    @Volatile var followersStatus = 200

    /** HTTP statuses for the next chat sends, in order; 200 once used up. */
    val sendStatuses = ConcurrentLinkedQueue<Int>()
    private val ids = AtomicInteger()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    val api = TwitchApi(
        http = HttpClient(
            MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/chat/messages") -> {
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        sends += Sent(
                            body.getValue("message").jsonPrimitive.content,
                            body["reply_parent_message_id"]?.jsonPrimitive?.content,
                        )
                        val status = sendStatuses.poll() ?: 200
                        if (status == 200) {
                            val id = ids.incrementAndGet()
                            respond("{\"data\":[{\"message_id\":\"own-$id\",\"is_sent\":true}]}", HttpStatusCode.OK, json)
                        } else {
                            respond("{}", HttpStatusCode.fromValue(status), json)
                        }
                    }
                    request.url.encodedPath.endsWith("/channels/followers") -> {
                        val user = request.url.parameters["user_id"].orEmpty()
                        followerLookups += user
                        if (followersStatus != 200) return@MockEngine respond("{}", HttpStatusCode.fromValue(followersStatus), json)
                        val data = if (user in followers) "[{\"user_id\":\"$user\"}]" else "[]"
                        respond("{\"data\":$data}", HttpStatusCode.OK, json)
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, json)
                }
            },
        ),
        token = { TokenResult.Ok("tok") },
        broadcasterId = { BROADCASTER_ID },
        baseUrl = "https://twitch.test/helix",
    )

    companion object {
        const val BROADCASTER_ID = "b1"
    }
}

/**
 * Runs the test scheduler until [condition] holds. The mock engine and the string resources answer on real threads, so
 * virtual time alone does not wait for them. It sleeps the thread instead of suspending: a suspended test body would let
 * the scheduler jump ahead in virtual time (and fire timeouts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.awaitUntil(what: String = "condition", condition: () -> Boolean) {
    repeat(2_000) {
        runCurrent()
        if (condition()) return
        Thread.sleep(5)
    }
    fail("timed out waiting for $what")
}

/** Gives work that must not happen a chance to (wrongly) happen. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle() {
    repeat(20) {
        runCurrent()
        Thread.sleep(5)
    }
    runCurrent()
}
