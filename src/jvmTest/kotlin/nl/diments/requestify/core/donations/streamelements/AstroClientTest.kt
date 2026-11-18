package nl.diments.requestify.core.donations.streamelements

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.storage.AppJson

@OptIn(ExperimentalCoroutinesApi::class)
class AstroClientTest {
    private val base = "wss://astro.streamelements.com/"

    private class FakeSocket : AstroSocket {
        val toClient = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var failSend = false
        override val incoming: ReceiveChannel<String> get() = toClient
        override suspend fun send(text: String) {
            if (failSend) throw java.io.IOException("closed")
            sent += text
        }
        override suspend fun close() {
            toClient.close()
        }
    }

    private inner class FakeConnector(val now: () -> Long) : AstroConnector {
        val connects = mutableListOf<Pair<Long, String>>()
        val sockets = mutableListOf<FakeSocket>()
        var failing = 0
        var failSends = 0
        override suspend fun connect(url: String): AstroSocket {
            connects += now() to url
            if (failing > 0) {
                failing--
                throw IOException("down")
            }
            return FakeSocket().also { it.failSend = failSends > 0; if (failSends > 0) failSends--; sockets += it }
        }
    }

    private class Run(val client: AstroClient, val connector: FakeConnector, val tips: List<JsonObject>, val fresh: List<Unit>)

    private fun TestScope.run(): Run {
        val connector = FakeConnector { testScheduler.currentTime }
        val client = AstroClient(connector, jwt = { "tok" }, room = { "room1" }, scope = backgroundScope, clock = { testScheduler.currentTime })
        val tips = mutableListOf<JsonObject>()
        val fresh = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.tips.collect { tips += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.freshSessions.collect { fresh += it } }
        client.start()
        runCurrent()
        return Run(client, connector, tips, fresh)
    }

    private val welcome = """{"type":"welcome","data":{"message":"hi","client_id":"c1"}}"""
    private val subscribed = """{"type":"response","nonce":"n","data":{"message":"successfully subscribed to topic","topic":"channel.tips","room":"room1"}}"""
    private val tip = """{"id":"e1","ts":"2026-11-18T20:10:00Z","type":"message","topic":"channel.tips","room":"room1","data":{"_id":"t1","approved":"allowed","status":"success","donation":{"amount":3}}}"""

    private fun TestScope.say(socket: FakeSocket, vararg messages: String) {
        messages.forEach { socket.toClient.trySend(it) }
        runCurrent()
    }

    @Test
    fun subscribesToChannelTipsWithTheJwt() = runTest {
        val r = run()
        val socket = r.connector.sockets.single()
        assertEquals(base, r.connector.connects.single().second)
        say(socket, welcome)

        val subscribe = AppJson.instance.parseToJsonElement(socket.sent.single()).jsonObject
        assertEquals("subscribe", subscribe.getValue("type").jsonPrimitive.content)
        val data = subscribe.getValue("data").jsonObject
        assertEquals("channel.tips", data.getValue("topic").jsonPrimitive.content)
        assertEquals("room1", data.getValue("room").jsonPrimitive.content)
        assertEquals("tok", data.getValue("token").jsonPrimitive.content)
        assertEquals("jwt", data.getValue("token_type").jsonPrimitive.content)
        assertTrue(subscribe.containsKey("nonce"))
    }

    @Test
    fun aTipIsPassedOnOnceSubscribed() = runTest {
        val r = run()
        val socket = r.connector.sockets.single()
        say(socket, welcome, subscribed)
        assertEquals(AstroState.Connected, r.client.state.value)
        assertEquals(1, r.fresh.size)

        say(socket, tip)
        assertEquals("t1", r.tips.single().getValue("_id").jsonPrimitive.content)
    }

    @Test
    fun aRefusedTokenSaysRefused() = runTest {
        val r = run()
        say(r.connector.sockets.single(), welcome, """{"type":"response","nonce":"n","error":"err_unauthorized","data":{"message":"invalid token"}}""")
        assertEquals(AstroState.Refused, r.client.state.value)

        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, r.connector.connects.size, "a refused token is not tried again by itself")
    }

    @Test
    fun reconnectsWithBackoff() = runTest {
        val r = run()
        say(r.connector.sockets.single(), welcome, subscribed)
        r.connector.failing = 2
        r.connector.sockets.single().toClient.close() // the connection breaks at 0
        runCurrent()
        assertIs<AstroState.Disconnected>(r.client.state.value)
        assertEquals(0L, (r.client.state.value as AstroState.Disconnected).sinceEpochMs)

        advanceTimeBy(1_000 + 2_000 + 4_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), r.connector.connects.map { it.first })
        say(r.connector.sockets.last(), welcome, subscribed)
        assertEquals(AstroState.Connected, r.client.state.value)
        assertEquals(2, r.fresh.size, "a new session: whatever came meanwhile is gone")
    }

    @Test
    fun aReconnectMessageResumesWithItsToken() = runTest {
        val r = run()
        val first = r.connector.sockets.single()
        say(first, welcome, subscribed)
        say(first, """{"type":"reconnect","data":{"reconnect_token":"abc"}}""")

        assertEquals("${base}?reconnect_token=abc", r.connector.connects.last().second)
        val second = r.connector.sockets.last()
        say(second, welcome)
        assertTrue(second.sent.isEmpty(), "the subscriptions are restored without a new subscribe")
        assertEquals(AstroState.Connected, r.client.state.value)
        assertEquals(1, r.fresh.size, "a resumed session lost nothing")
        say(second, tip)
        assertEquals(1, r.tips.size)
    }

    @Test
    fun aSubscribeThatCannotBeSentIsTriedAgain() = runTest {
        val connector = FakeConnector { testScheduler.currentTime }
        connector.failSends = 1 // the socket closes right after its welcome
        val client = AstroClient(connector, jwt = { "tok" }, room = { "room1" }, scope = backgroundScope, clock = { testScheduler.currentTime })
        client.start()
        runCurrent()
        say(connector.sockets.single(), welcome)
        assertIs<AstroState.Disconnected>(client.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, connector.connects.size, "tried again after the backoff")
        say(connector.sockets.last(), welcome, subscribed)
        assertEquals(AstroState.Connected, client.state.value)
    }
}
