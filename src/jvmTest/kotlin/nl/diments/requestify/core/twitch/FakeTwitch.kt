package nl.diments.requestify.core.twitch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Twitch for tests, as one `MockEngine`: the id server (device code, tokens, validate) and Helix (the user, EventSub
 * subscriptions). Pass the test's dispatcher to run it in virtual time; [now] stamps [validations].
 */
internal class FakeTwitch(private val now: () -> Long = { 0L }, dispatcher: CoroutineDispatcher? = null) {
    /** Device-code polls answered `authorization_pending` before the login is approved; [Int.MAX_VALUE] = never. */
    var pendingPolls = 1

    /** Twitch cannot give a device code (the id server is down). */
    var deviceDown = false

    /** While set, the profile request waits for it. */
    var usersGate: CompletableDeferred<Unit>? = null

    /** While set, `/oauth2/validate` waits for it. */
    var validateGate: CompletableDeferred<Unit>? = null

    /** Helix cannot give the user's profile. */
    var usersDown = false

    var userCode = "ABCDEFGH"
    var broadcasterType = "affiliate"

    /** The account the logins belong to (validate and `/helix/users`): the streamer's by default, a bot's when set. */
    var userId = "42"
    var login = "diments"
    var displayName = "DIMENTS"

    /** The channels the user moderates (`/helix/moderation/channels`). */
    val moderatedChannels: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** The bodies of the chat messages sent; each is answered as sent. */
    val sentChats: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** When each `/oauth2/validate` was asked. */
    val validations = mutableListOf<Long>()

    private var polls = 0
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    val http = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                    when {
                        path == "/oauth2/device" ->
                            if (deviceDown) {
                                respondError(HttpStatusCode.ServiceUnavailable)
                            } else {
                                respond(
                                    """{"device_code":"dc","user_code":"$userCode","verification_uri":"https://www.twitch.tv/activate?public=true&device-code=$userCode","expires_in":1800,"interval":5}""",
                                    HttpStatusCode.OK,
                                    json,
                                )
                            }
                        path == "/oauth2/token" && "refresh_token" in body -> tokens("a-refreshed", "r-rotated")
                        path == "/oauth2/token" ->
                            if (++polls > pendingPolls) {
                                tokens("a1", "r1")
                            } else {
                                respond("""{"status":400,"message":"authorization_pending"}""", HttpStatusCode.BadRequest, json)
                            }
                        path == "/oauth2/validate" -> {
                            validateGate?.await()
                            validations += now()
                            respond(
                                """{"client_id":"cid","login":"$login","scopes":[],"user_id":"$userId","expires_in":5000}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        }
                        path == "/helix/users" && usersGate != null -> {
                            usersGate?.await()
                            respond(
                                """{"data":[{"id":"$userId","login":"$login","display_name":"$displayName","broadcaster_type":"$broadcasterType"}]}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        }
                        path == "/helix/users" && usersDown -> respondError(HttpStatusCode.ServiceUnavailable)
                        path == "/helix/users" -> respond(
                            """{"data":[{"id":"$userId","login":"$login","display_name":"$displayName","broadcaster_type":"$broadcasterType"}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                        path == "/helix/moderation/channels" -> respond(
                            """{"data":[${moderatedChannels.joinToString(",") { """{"broadcaster_id":"$it"}""" }}],"pagination":{}}""",
                            HttpStatusCode.OK,
                            json,
                        )
                        path == "/helix/chat/messages" -> {
                            sentChats += (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                            respond("""{"data":[{"message_id":"m${sentChats.size}","is_sent":true}]}""", HttpStatusCode.OK, json)
                        }
                        path == "/helix/eventsub/subscriptions" -> respond(
                            """{"data":[{"id":"sub","status":"enabled"}],"total":1,"total_cost":0,"max_total_cost":10}""",
                            HttpStatusCode.Accepted,
                            json,
                        )
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            },
        ),
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.tokens(access: String, refresh: String) = respond(
        """{"access_token":"$access","expires_in":14000,"refresh_token":"$refresh","scope":[],"token_type":"bearer"}""",
        HttpStatusCode.OK,
        json,
    )
}
