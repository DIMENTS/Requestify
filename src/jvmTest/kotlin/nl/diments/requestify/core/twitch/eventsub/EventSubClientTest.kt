package nl.diments.requestify.core.twitch.eventsub

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.twitch.TwitchApi

@OptIn(ExperimentalCoroutinesApi::class)
class EventSubClientTest {
    private class FakeSocket : EventSubSocket {
        private val channel = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<String> get() = channel
        var closes = 0
            private set

        override suspend fun close() {
            closes++
            channel.close()
        }

        fun push(text: String) = check(channel.trySend(text).isSuccess) { "the socket is closed" }

        /** The connection breaks (Twitch or the network closed it). */
        fun drop() {
            channel.close()
        }
    }

    private class FakeConnector(private val clock: () -> Long) : EventSubConnector {
        val attempts = mutableListOf<Long>()
        val urls = mutableListOf<String>()
        val sockets = mutableListOf<FakeSocket>()

        /** This many next connects throw. */
        var failNext = 0

        val last: FakeSocket get() = sockets.last()

        override suspend fun connect(url: String): EventSubSocket {
            attempts += clock()
            urls += url
            if (failNext > 0) {
                failNext--
                throw IOException("Twitch cannot be reached")
            }
            return FakeSocket().also { sockets += it }
        }
    }

    private data class Sub(val type: String, val version: String, val condition: Map<String, String>, val sessionId: String)

    /** The client on a fake socket and a `MockEngine` Helix, all in the test's virtual time. */
    private class Harness(scope: TestScope, var rewardId: String? = null, var broadcasterId: String? = BROADCASTER) {
        val connector = FakeConnector { scope.currentTime }
        val subs = mutableListOf<Sub>()

        /** Subscribe calls in order, and "fresh" where [EventSubClient.freshSessions] fired. */
        val timeline = mutableListOf<String>()
        var httpCalls = 0

        /** Twitch's answer to a subscribe call. */
        var answer: (Sub) -> HttpStatusCode = { HttpStatusCode.Accepted }
        var revoked = 0
        val events = mutableListOf<EventSubEvent>()
        val states = mutableListOf<EventSubState>()
        var fresh = 0

        /** The client's own scope, so a test can see that nothing of it is left running. */
        val clientJob = Job(scope.backgroundScope.coroutineContext.job)

        private val api = TwitchApi(
            http = HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                        addHandler { request ->
                            httpCalls++
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            val sub = Sub(
                                type = body["type"]!!.jsonPrimitive.content,
                                version = body["version"]!!.jsonPrimitive.content,
                                condition = body["condition"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                                sessionId = body["transport"]!!.jsonObject["session_id"]!!.jsonPrimitive.content,
                            )
                            subs += sub
                            timeline += sub.type
                            val status = answer(sub)
                            respond(
                                if (status == HttpStatusCode.Accepted) SUBSCRIBED else """{"status":${status.value}}""",
                                status,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ),
            ),
            token = { forceRefresh -> TokenResult.Ok(if (forceRefresh) "fresh" else "tok") },
            broadcasterId = { broadcasterId },
        )

        val client = EventSubClient(
            connector = connector,
            api = api,
            broadcasterId = { broadcasterId },
            rewardId = { rewardId },
            onAuthorizationRevoked = { revoked++ },
            scope = CoroutineScope(scope.backgroundScope.coroutineContext + clientJob),
            clock = { scope.currentTime },
        )

        init {
            val collectors = scope.backgroundScope + UnconfinedTestDispatcher(scope.testScheduler)
            collectors.launch { client.events.collect { events += it } }
            collectors.launch { client.state.collect { states += it } }
            collectors.launch {
                client.freshSessions.collect {
                    fresh++
                    timeline += "fresh"
                }
            }
        }

        /** The chat notifications delivered, by their notification id. */
        fun chatIds(): List<String> =
            events.filterIsInstance<EventSubEvent.ChatMessage>().map { it.messageId.removePrefix("chat-") }
    }

    @Test
    fun welcomeSubscribesEverythingWithinTheSession() = runTest {
        val h = Harness(this, rewardId = "rw-1")
        h.client.start()
        runCurrent()
        assertEquals(listOf(DEFAULT_URL), h.connector.urls)
        assertEquals(EventSubState.Connecting, h.client.state.value)
        assertTrue(h.subs.isEmpty(), "nothing is subscribed before the welcome")

        h.connector.last.push(welcome("S1"))
        runCurrent()

        val b = BROADCASTER
        val reward = mapOf("broadcaster_user_id" to b, "reward_id" to "rw-1")
        assertEquals(
            listOf(
                Sub("channel.chat.message", "1", mapOf("broadcaster_user_id" to b, "user_id" to b), "S1"),
                Sub("channel.cheer", "1", mapOf("broadcaster_user_id" to b), "S1"),
                Sub("stream.online", "1", mapOf("broadcaster_user_id" to b), "S1"),
                Sub("stream.offline", "1", mapOf("broadcaster_user_id" to b), "S1"),
                Sub("channel.channel_points_custom_reward_redemption.add", "1", reward, "S1"),
                Sub("channel.channel_points_custom_reward.update", "1", reward, "S1"),
                Sub("channel.channel_points_custom_reward.remove", "1", reward, "S1"),
            ),
            h.subs,
        )
        assertEquals(0L, currentTime, "subscribed at once, well within the welcome's 30 s")
        assertEquals(h.subs.map { it.type } + "fresh", h.timeline, "a fresh session is announced after its subscriptions")
        assertEquals(EventSubState.Connected, h.client.state.value)

        h.connector.last.push(chat("n1"))
        runCurrent()
        assertEquals(listOf("n1"), h.chatIds())
    }

    @Test
    fun reconnectMessageMovesWithoutResubscribing() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        val old = h.connector.last
        old.push(welcome("S1"))
        runCurrent()
        assertEquals(4, h.subs.size)
        assertEquals(1, h.fresh)

        old.push(reconnect(RECONNECT_URL))
        runCurrent()
        assertEquals(listOf(DEFAULT_URL, RECONNECT_URL), h.connector.urls, "the reconnect URL is used unmodified")
        val new = h.connector.last
        assertEquals(0, old.closes, "the old socket stays open until the new welcome")

        old.push(chat("n1"))
        runCurrent()
        assertEquals(listOf("n1"), h.chatIds(), "the old socket still delivers")

        new.push(welcome("S1"))
        runCurrent()
        assertEquals(1, old.closes)
        assertEquals(0, new.closes)
        assertEquals(4, h.subs.size, "subscriptions carry over: no resubscribe")
        assertEquals(1, h.fresh, "nothing was missed: no fresh session")
        assertEquals(
            listOf(EventSubState.Stopped, EventSubState.Connecting, EventSubState.Connected),
            h.states,
            "connected throughout the move",
        )

        new.push(chat("n1")) // resent by Twitch on the new socket
        new.push(chat("n2"))
        runCurrent()
        assertEquals(listOf("n1", "n2"), h.chatIds(), "no notification is handled twice")

        // The client now watches the new socket: its silence starts a fresh connection.
        advanceTimeBy(35_001)
        runCurrent()
        assertEquals(listOf(DEFAULT_URL, RECONNECT_URL, DEFAULT_URL), h.connector.urls)
        assertEquals(1, new.closes)
        assertEquals(1, old.closes, "the old socket is closed exactly once")
    }

    @Test
    fun keepaliveSilenceReconnectsAndResubscribes() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        val first = h.connector.last
        first.push(welcome("S1", keepaliveSeconds = 30))
        first.push(chat("n1"))
        runCurrent()

        advanceTimeBy(34_999)
        runCurrent()
        assertEquals(1, h.connector.urls.size, "30 s keepalive plus 5 s margin is not over yet")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(DEFAULT_URL, DEFAULT_URL), h.connector.urls, "a fresh connection, not a reconnect URL")
        assertEquals(35_000L, h.connector.attempts[1])
        assertEquals(1, first.closes)
        assertEquals(EventSubState.Disconnected(35_000), h.client.state.value)

        val second = h.connector.last
        second.push(welcome("S2"))
        runCurrent()
        assertEquals(List(4) { "S1" } + List(4) { "S2" }, h.subs.map { it.sessionId })
        assertEquals(h.subs.take(4).map { it.type }, h.subs.drop(4).map { it.type }, "everything is subscribed again")
        assertEquals(2, h.fresh, "the controller learns about the gap")
        assertEquals(EventSubState.Connected, h.client.state.value)

        second.push(chat("n1")) // resent after the reconnect
        second.push(chat("n2"))
        runCurrent()
        assertEquals(listOf("n1", "n2"), h.chatIds(), "no notification is handled twice")
    }

    @Test
    fun everyMessageResetsTheKeepaliveWatchdog() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        h.connector.last.push(welcome("S1", keepaliveSeconds = 30))
        runCurrent()

        advanceTimeBy(30_000)
        h.connector.last.push(keepalive())
        advanceTimeBy(30_000)
        h.connector.last.push(chat("n1"))
        advanceTimeBy(34_999)
        runCurrent()
        assertEquals(1, h.connector.urls.size, "a keepalive and a notification each restarted the watch")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.connector.urls.size)
        assertEquals(95_000L, h.connector.attempts[1])
    }

    @Test
    fun duplicateMessageIdIsDeliveredOnce() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        val socket = h.connector.last
        socket.push(welcome("S1"))
        socket.push(chat("m1"))
        socket.push(chat("m1"))
        runCurrent()
        assertEquals(listOf("m1"), h.chatIds())

        repeat(499) { socket.push(chat("x$it")) }
        socket.push(chat("m1")) // still among the last 500 ids
        runCurrent()
        assertEquals(1, h.chatIds().count { it == "m1" })

        socket.push(chat("x499")) // the 501st id pushes m1 out
        socket.push(chat("m1"))
        runCurrent()
        assertEquals(2, h.chatIds().count { it == "m1" }, "only the last 500 ids are kept")
        assertEquals(502, h.events.size)
    }

    @Test
    fun subscribe401StopsWithLoginNeeded() = runTest {
        val h = Harness(this)
        h.answer = { HttpStatusCode.Unauthorized }
        h.client.start()
        runCurrent()
        h.connector.last.push(welcome("S1"))
        runCurrent()

        assertEquals(EventSubState.LoginNeeded, h.client.state.value)
        assertEquals(2, h.httpCalls, "one subscription, retried once after a forced refresh; no other type is tried")
        assertEquals(1, h.connector.last.closes)
        assertEquals(0, h.fresh)

        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(1, h.connector.urls.size, "a dead login does not reconnect")
        assertEquals(2, h.httpCalls)

        // After a new login, start() connects again.
        h.answer = { HttpStatusCode.Accepted }
        h.client.start()
        runCurrent()
        assertEquals(2, h.connector.urls.size)
        assertEquals(EventSubState.Connecting, h.client.state.value)
        h.connector.last.push(welcome("S2"))
        runCurrent()
        assertEquals(EventSubState.Connected, h.client.state.value)
        assertEquals(1, h.fresh)
    }

    @Test
    fun revocationCallsAuthorizationRevoked() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        val socket = h.connector.last
        socket.push(welcome("S1"))
        socket.push(revocation("r1", "user_removed"))
        runCurrent()
        assertEquals(0, h.revoked, "only a revoked authorization is a dead login")

        socket.push(revocation("r2", "authorization_revoked"))
        runCurrent()
        assertEquals(1, h.revoked)

        socket.push(revocation("r2", "authorization_revoked")) // resent
        socket.push(revocation("r3", "authorization_revoked")) // the next subscription of the same login
        runCurrent()
        assertEquals(1, h.revoked, "reported once per session")
    }

    @Test
    fun backoffDoublesToThirtySeconds() = runTest {
        val h = Harness(this)
        h.connector.failNext = 7
        h.client.start()
        runCurrent()
        assertEquals(EventSubState.Disconnected(0), h.client.state.value)

        advanceTimeBy(91_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000, 3_000, 7_000, 15_000, 31_000, 61_000, 91_000), h.connector.attempts)
        h.connector.last.push(welcome("S1"))
        runCurrent()
        assertEquals(EventSubState.Connected, h.client.state.value)

        // The welcome reset the backoff: after a drop the client reconnects at once, then waits 1 s, 2 s, ...
        h.connector.failNext = 2
        h.connector.last.drop()
        runCurrent()
        assertEquals(EventSubState.Disconnected(91_000), h.client.state.value)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(91_000L, 92_000, 94_000), h.connector.attempts.drop(8))
    }

    @Test
    fun socketWithoutAWelcomeIsAFailedConnect() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        val silent = h.connector.last

        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, silent.closes, "no welcome within 15 s")
        assertEquals(1, h.connector.urls.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(0L, 16_000), h.connector.attempts, "retried after the first backoff step")

        h.connector.last.drop() // closed before its welcome
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, h.connector.attempts.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(18_000L, h.connector.attempts.last())
    }

    @Test
    fun rewardSubscriptionsOnlyWithARewardId() = runTest {
        val h = Harness(this, rewardId = null)
        h.client.rewardChanged() // not running: nothing to do
        h.client.start()
        runCurrent()
        h.connector.last.push(welcome("S1"))
        runCurrent()
        assertEquals(listOf("channel.chat.message", "channel.cheer", "stream.online", "stream.offline"), h.subs.map { it.type })

        h.client.rewardChanged() // still no reward
        runCurrent()
        assertEquals(4, h.subs.size)

        h.rewardId = "rw-2"
        h.client.rewardChanged()
        runCurrent()
        val reward = mapOf("broadcaster_user_id" to BROADCASTER, "reward_id" to "rw-2")
        assertEquals(
            listOf(
                Sub("channel.channel_points_custom_reward_redemption.add", "1", reward, "S1"),
                Sub("channel.channel_points_custom_reward.update", "1", reward, "S1"),
                Sub("channel.channel_points_custom_reward.remove", "1", reward, "S1"),
            ),
            h.subs.drop(4),
        )
        assertEquals(1, h.fresh, "a new reward is not a fresh session")
    }

    @Test
    fun failedSubscriptionIsRetriedBeforeTheSessionCountsAsFresh() = runTest {
        val h = Harness(this)
        var chatAnswers = 0
        h.answer = { sub ->
            if (sub.type == "channel.chat.message" && chatAnswers++ == 0) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Accepted
        }
        h.client.start()
        runCurrent()
        h.connector.last.push(welcome("S1"))
        runCurrent()
        assertEquals(0, h.fresh)
        assertEquals(EventSubState.Connecting, h.client.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            listOf("channel.chat.message", "channel.cheer", "stream.online", "stream.offline", "channel.chat.message", "fresh"),
            h.timeline,
        )
        assertEquals(EventSubState.Connected, h.client.state.value)
    }

    @Test
    fun stopClosesEverySocketAndLeavesNothingRunning() = runTest {
        val h = Harness(this)
        h.client.start()
        runCurrent()
        val old = h.connector.last
        old.push(welcome("S1"))
        old.push(reconnect(RECONNECT_URL))
        runCurrent()
        val moving = h.connector.last

        h.client.stop()
        runCurrent()
        assertEquals(1, old.closes)
        assertEquals(1, moving.closes, "the socket being moved to is closed too")
        assertEquals(EventSubState.Stopped, h.client.state.value)
        assertTrue(h.clientJob.children.none(), "no coroutine of the client is left running")

        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(2, h.connector.urls.size)
        assertEquals(4, h.subs.size)
    }

    @Test
    fun withoutAnAccountNothingConnects() = runTest {
        val h = Harness(this, broadcasterId = null)
        h.client.start()
        runCurrent()
        assertTrue(h.connector.urls.isEmpty())
        assertEquals(EventSubState.Stopped, h.client.state.value)
    }

    private companion object {
        const val BROADCASTER = "141981764"
        const val DEFAULT_URL = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30"
        const val RECONNECT_URL = "wss://eventsub.wss.twitch.tv/ws?reconnect=AQoQ&challenge=7f3a9c"
        const val SUBSCRIBED = """{"data":[{"id":"sub","status":"enabled"}],"total":1,"total_cost":0,"max_total_cost":10}"""
        const val TIMESTAMP = "2026-11-17T19:47:10.123456789Z"

        fun id() = UUID.randomUUID().toString()

        fun welcome(sessionId: String, keepaliveSeconds: Int = 30) =
            """{"metadata":{"message_id":"${id()}","message_type":"session_welcome","message_timestamp":"$TIMESTAMP"},""" +
                """"payload":{"session":{"id":"$sessionId","status":"connected","connected_at":"$TIMESTAMP",""" +
                """"keepalive_timeout_seconds":$keepaliveSeconds,"reconnect_url":null}}}"""

        fun keepalive() =
            """{"metadata":{"message_id":"${id()}","message_type":"session_keepalive","message_timestamp":"$TIMESTAMP"},"payload":{}}"""

        fun reconnect(url: String) =
            """{"metadata":{"message_id":"${id()}","message_type":"session_reconnect","message_timestamp":"$TIMESTAMP"},""" +
                """"payload":{"session":{"id":"S1","status":"reconnecting","keepalive_timeout_seconds":null,""" +
                """"reconnect_url":"$url","connected_at":"$TIMESTAMP"}}}"""

        /** A chat notification with notification id [messageId]; its chat message id is "chat-[messageId]". */
        fun chat(messageId: String) =
            """{"metadata":{"message_id":"$messageId","message_type":"notification","message_timestamp":"$TIMESTAMP",""" +
                """"subscription_type":"channel.chat.message","subscription_version":"1"},""" +
                """"payload":{"subscription":{"id":"sub-chat","status":"enabled","type":"channel.chat.message","version":"1"},""" +
                """"event":{"broadcaster_user_id":"$BROADCASTER","broadcaster_user_login":"diments","broadcaster_user_name":"DIMENTS",""" +
                """"chatter_user_id":"4145994","chatter_user_login":"viewer_one","chatter_user_name":"Viewer_One",""" +
                """"message_id":"chat-$messageId","message":{"text":"!sr song","fragments":[]},"badges":[],""" +
                """"source_broadcaster_user_id":null}}}"""

        fun revocation(messageId: String, status: String) =
            """{"metadata":{"message_id":"$messageId","message_type":"revocation","message_timestamp":"$TIMESTAMP",""" +
                """"subscription_type":"channel.chat.message","subscription_version":"1"},""" +
                """"payload":{"subscription":{"id":"sub-$messageId","status":"$status","type":"channel.chat.message","version":"1"}}}"""
    }
}
