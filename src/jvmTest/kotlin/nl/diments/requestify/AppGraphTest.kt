package nl.diments.requestify

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.model.ChannelPointsRef
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.stream.StreamState
import nl.diments.requestify.core.twitch.RedemptionStatus
import nl.diments.requestify.core.twitch.eventsub.EventSubConnector
import nl.diments.requestify.core.twitch.eventsub.EventSubSocket
import nl.diments.requestify.core.twitch.eventsub.EventSubState
import nl.diments.requestify.core.twitch.points.OutboxEntry
import nl.diments.requestify.ui.Destination
import nl.diments.requestify.ui.common.TwitchHealth
import nl.diments.requestify.ui.startDestination

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class AppGraphTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-graph") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun buildsWithTempDir() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val graph = AppGraph(AppPaths(dir), InMemorySecretStore(), engine, backgroundScope)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(emptyList(), graph.home.state.value.queue)
        assertTrue(requests.isEmpty(), "building the graph must not poll Spotify: $requests")
        assertEquals(EventSubState.Stopped, graph.eventSub.state.value, "building the graph must not connect to Twitch")
    }

    @Test
    fun requestsAreOpenWithoutTwitch() = runTest {
        val graph = AppGraph(AppPaths(dir), InMemorySecretStore(), unreachable(), backgroundScope)
        runCurrent()
        assertTrue(graph.gate.open.value)
        assertFalse(graph.gate.status.value.twitch)
    }

    @Test
    fun twitchLoggedOutKeepsRequestsOpenWithoutTwitch() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respondError(HttpStatusCode.ServiceUnavailable)
        }
        val graph = AppGraph(AppPaths(dir), InMemorySecretStore(), engine, backgroundScope)
        graph.start()
        runCurrent()
        assertTrue(graph.gate.open.value, "without a Twitch account the plain switch decides, default open")
        assertFalse(graph.gate.status.value.twitch)
        assertEquals(EventSubState.Stopped, graph.eventSub.state.value, "logged out: no EventSub connection")
        assertFalse(requests.any { "twitch" in it }, "logged out: nothing is asked of Twitch: $requests")
        assertEquals(TwitchHealth(), graph.twitchSession.health.value)
        graph.stop()
    }

    @Test
    fun aTwitchAccountKeepsRequestsOnTheStreamEvenWhenTheLoginIsDead() = runTest {
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }
            .write(Settings(twitch = TwitchSettings(account = TwitchAccount("42", "diments", "DIMENTS", "affiliate"))))
        // No saved login (it was lost), yet the account is still set up: requests must not fall back to the plain switch.
        val graph = AppGraph(AppPaths(dir), InMemorySecretStore(), unreachable(), backgroundScope)
        runCurrent()
        assertTrue(graph.gate.status.value.twitch)
        assertFalse(graph.gate.open.value, "a Twitch streamer's requests follow the stream, which is not live")
    }

    @Test
    fun firstRunStartsOnConnections() = runTest {
        val graph = AppGraph(AppPaths(dir), InMemorySecretStore(), unreachable(), backgroundScope)
        assertEquals(Destination.Connections, startDestination(graph.settings.settings.value, graph.auth.status.value))
    }

    @Test
    fun startsOnHomeOnceSpotifyIsSetUp() = runTest {
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }
            .write(Settings(spotifyClientId = "0123456789abcdef0123456789abcdef"))
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val graph = AppGraph(AppPaths(dir), secrets, unreachable(), backgroundScope)
        assertEquals(Destination.Home, startDestination(graph.settings.settings.value, graph.auth.status.value))
    }

    @Test
    fun offlineTokenRefreshIsUnreachableNotLoginNeeded() = runBlocking {
        val health = firstHealth(loggedIn()) { throw IOException("offline") }
        assertEquals(SpotifyHealth.Unreachable("token unavailable"), health)
    }

    @Test
    fun deletedSpotifyAppNeedsLogin() = runBlocking {
        val health = firstHealth(loggedIn()) {
            respond("""{"error":"invalid_client"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(SpotifyHealth.LoginNeeded, health)
    }

    @Test
    fun freshInstallIsNotConnectedRatherThanLoginNeeded() = runBlocking {
        val health = firstHealth(InMemorySecretStore()) { error("no login, so no token request") }
        assertEquals(SpotifyHealth.LoggedOut, health)
    }

    private fun loggedIn() = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }

    /**
     * Starts a graph with a saved Client ID on real threads (Spotify's token endpoint answers with [tokenEndpoint],
     * the Web API is down) and returns Spotify's health after the first poll.
     */
    private suspend fun firstHealth(
        secrets: SecretStore,
        tokenEndpoint: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SpotifyHealth {
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }
            .write(Settings(spotifyClientId = "0123456789abcdef0123456789abcdef"))
        val engine = MockEngine { request ->
            if (request.url.host == "accounts.spotify.com") tokenEndpoint(request) else respondError(HttpStatusCode.ServiceUnavailable)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val graph = AppGraph(AppPaths(dir), secrets, engine, scope)
            graph.start()
            return withTimeout(10.seconds) {
                graph.home.state.map { it.health }.first { it != SpotifyHealth.NothingPlaying }
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun unreachable() = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }

    @Test
    fun chatRequestEndToEnd() = runBlocking {
        val services = Services()
        withGraph(services, loggedInToBoth()) { graph ->
            awaitTwitch(graph, services, sockets = 1)
            await("requests open: Twitch says the stream is live") { graph.gate.open.value }
            assertTrue(graph.gate.status.value.live)

            services.lastSocket.push(chat("msg-1", "!sr https://open.spotify.com/track/$TRACK_A"))
            await("the request in the queue") { graph.home.state.value.queue.isNotEmpty() }
            await("the reply") { services.sends.isNotEmpty() }
            delay(QUIET_MS) // nothing else comes

            val request = graph.home.state.value.queue.single()
            assertEquals(TRACK_A, request.track.id)
            assertEquals(RequestSource.CHAT, request.source)
            assertEquals("Viewer_One", request.requester.displayName)
            assertEquals(listOf("msg-1"), services.sends.map { it.replyTo }, "one reply, threaded under the message")
        }
    }

    @Test
    fun channelPointsLifecycleRunsWithoutTwitchSession() = runBlocking {
        queueRedemption()
        val services = Services()
        withGraph(services, InMemorySecretStore()) { graph -> // logged in to nothing
            await("the saved queue") { graph.home.state.value.queue.isNotEmpty() }
            graph.home.onRemove("red-1") // the streamer's X

            await("the refund saved") { redemptions().isNotEmpty() }
            assertEquals(listOf(OutboxEntry("rw-1", "red-1", RedemptionStatus.CANCELED)), redemptions())
            delay(QUIET_MS)
            assertFalse(services.calls.any { "twitch" in it }, "without a login it waits to be sent: ${services.calls}")
        }
    }

    @Test
    fun aStreamThatEndedWhileTheAppWasClosedRefundsItsRedemptionsAtStart() = runBlocking {
        queueRedemption()
        // A Twitch account (its login lost), and a stream that went offline long ago: its grace ran out meanwhile.
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }
            .write(Settings(twitch = TwitchSettings(account = TwitchAccount("42", "diments", "DIMENTS", "affiliate"))))
        JsonFileStore(AppPaths(dir).streamFile, StreamState.serializer()) { StreamState() }
            .write(StreamState(live = false, offlineSinceMs = 1L))

        withGraph(Services(), InMemorySecretStore()) { graph ->
            await("the refund saved") { redemptions().isNotEmpty() }
            assertEquals(listOf(OutboxEntry("rw-1", "red-1", RedemptionStatus.CANCELED)), redemptions())
            assertEquals(emptyList(), graph.home.state.value.queue, "the stream's end cleared the free request")
        }
    }

    /** Saves a queue holding one request of Requestify's own reward (redemption `red-1`). */
    private fun queueRedemption() {
        val request = Request(
            id = "red-1",
            track = Track(TRACK_A, "spotify:track:$TRACK_A", "Alpha", listOf("Artist"), 200_000, false, null, emptyList()),
            requester = Requester("Viewer_One", "viewer_one", "7"),
            source = RequestSource.CHANNEL_POINTS,
            payment = null,
            createdAtEpochMs = 0,
            sourceRef = ChannelPointsRef(rewardId = "rw-1", redemptionId = "red-1", managed = true).format(),
        )
        JsonFileStore(AppPaths(dir).queueFile, PersistedPlayback.serializer()) { PersistedPlayback() }
            .write(PersistedPlayback(queue = RequestQueue(listOf(request))))
    }

    /** What redemptions.json holds. */
    private fun redemptions(): List<OutboxEntry> =
        JsonFileStore(AppPaths(dir).redemptionsFile, ListSerializer(OutboxEntry.serializer())) { emptyList() }.read()

    @Test
    fun sourcesStopWhenTheSessionEnds() = runBlocking {
        val services = Services()
        withGraph(services, loggedInToBoth()) { graph ->
            awaitTwitch(graph, services, sockets = 1)
            await("requests open") { graph.gate.open.value }
            val hold = CompletableDeferred<Unit>()
            services.trackHold = hold
            services.lastSocket.push(chat("msg-1", "!sr https://open.spotify.com/track/$TRACK_A")) // its lookup hangs
            services.lastSocket.push(chat("msg-2", "!sr https://open.spotify.com/track/$TRACK_B")) // waits behind it
            await("the first lookup under way") { services.trackLookups.get() == 1 }

            graph.twitchSetup.onLogout()
            await("the chat's work cut off with the session") { services.cancelledLookups.get() == 1 }
            hold.complete(Unit)
            delay(QUIET_MS)

            assertFalse(graph.twitchSession.connected.value)
            assertEquals(emptyList(), graph.home.state.value.queue, "after logout no chat message is handled")
            assertEquals(1, services.trackLookups.get(), "the message still waiting was dropped too")
            assertTrue(services.sends.isEmpty(), "${services.sends}")
        }
    }

    @Test
    fun aSessionFlipRunsEachSourceOnce() = runBlocking {
        val services = Services()
        withGraph(services, loggedInToBoth()) { graph ->
            awaitTwitch(graph, services, sockets = 1)
            val account = graph.settings.settings.value.twitch.account
            graph.settings.update { it.copy(twitch = it.twitch.copy(account = null)) }
            await("the session ended") { graph.eventSub.state.value == EventSubState.Stopped }
            graph.settings.update { it.copy(twitch = it.twitch.copy(account = account)) }
            awaitTwitch(graph, services, sockets = 2)
            await("requests open") { graph.gate.open.value }

            services.lastSocket.push(chat("msg-1", "!sr https://open.spotify.com/track/$TRACK_A"))
            await("the reply") { services.sends.isNotEmpty() }
            delay(QUIET_MS) // a second chat reader would answer again
            assertEquals(listOf("msg-1"), services.sends.map { it.replyTo })
            assertEquals(listOf(TRACK_A), graph.home.state.value.queue.map { it.track.id })
        }
    }

    /** Saves a Spotify Client ID and a Twitch account, and returns both logins. */
    private fun loggedInToBoth(): SecretStore {
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }.write(
            Settings(
                spotifyClientId = "0123456789abcdef0123456789abcdef",
                twitch = TwitchSettings(account = TwitchAccount("42", "diments", "DIMENTS", "affiliate")),
            ),
        )
        return InMemorySecretStore().apply {
            put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "s1")
            put(SecretKeys.TWITCH_REFRESH_TOKEN, "t1")
        }
    }

    /** Runs a started graph on real threads, then stops it and everything it left running. */
    private suspend fun withGraph(services: Services, secrets: SecretStore, block: suspend (AppGraph) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val graph = AppGraph(AppPaths(dir), secrets, services.engine, scope, services.connector)
            graph.start()
            try {
                block(graph)
            } finally {
                graph.stop()
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private suspend fun awaitTwitch(graph: AppGraph, services: Services, sockets: Int) = await("EventSub connected") {
        services.connector.sockets.size == sockets && graph.eventSub.state.value == EventSubState.Connected
    }

    private suspend fun await(what: String, condition: () -> Boolean) {
        withTimeoutOrNull(10.seconds) {
            while (!condition()) delay(10)
        } ?: fail("timed out waiting for $what")
    }

    private class FakeSocket : EventSubSocket {
        private val channel = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<String> get() = channel
        override suspend fun close() { channel.close() }
        fun push(text: String) = check(channel.trySend(text).isSuccess) { "the socket is closed" }
    }

    /** Every connection is welcomed at once. */
    private class FakeConnector : EventSubConnector {
        val sockets = CopyOnWriteArrayList<FakeSocket>()

        override suspend fun connect(url: String): EventSubSocket =
            FakeSocket().also {
                it.push(welcome("S${sockets.size + 1}"))
                sockets += it
            }
    }

    /**
     * Spotify (a track lookup, nothing playing) and Twitch (logins, subscriptions, a live stream, chat sends) for a
     * whole graph, plus the fake EventSub. Records the calls and the chat messages sent.
     */
    private class Services {
        data class Sent(val message: String, val replyTo: String?)

        val connector = FakeConnector()
        val sends = CopyOnWriteArrayList<Sent>()
        val calls = CopyOnWriteArrayList<String>()
        val trackLookups = AtomicInteger()

        /** Track lookups cut off while waiting for [trackHold]. */
        val cancelledLookups = AtomicInteger()

        /** While set, Spotify's track lookups wait for it. */
        @Volatile var trackHold: CompletableDeferred<Unit>? = null
        private val sentIds = AtomicInteger()
        private val json = headersOf(HttpHeaders.ContentType, "application/json")

        val lastSocket: FakeSocket get() = connector.sockets.last()

        val engine = MockEngine { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            calls += "$host$path"
            when {
                host == "accounts.spotify.com" ->
                    respond("""{"access_token":"sp","token_type":"Bearer","expires_in":3600}""", HttpStatusCode.OK, json)
                host == "api.spotify.com" && path.startsWith("/v1/tracks/") -> {
                    trackLookups.incrementAndGet()
                    try {
                        trackHold?.await()
                    } catch (e: CancellationException) {
                        cancelledLookups.incrementAndGet()
                        throw e
                    }
                    val id = path.substringAfterLast('/')
                    respond(
                        """{"id":"$id","uri":"spotify:track:$id","name":"Song $id","type":"track","duration_ms":200000,""" +
                            """"explicit":false,"artists":[{"id":"a1","name":"Artist"}],"is_playable":true}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
                host == "api.spotify.com" && path == "/v1/me/player/currently-playing" -> respond("", HttpStatusCode.NoContent)
                host == "api.spotify.com" -> respondError(HttpStatusCode.ServiceUnavailable)
                host == "id.twitch.tv" && path == "/oauth2/token" -> respond(
                    """{"access_token":"tw","expires_in":14000,"refresh_token":"t2","scope":[],"token_type":"bearer"}""",
                    HttpStatusCode.OK,
                    json,
                )
                host == "id.twitch.tv" && path == "/oauth2/validate" -> respond(
                    """{"client_id":"cid","login":"diments","scopes":[],"user_id":"42","expires_in":5000}""",
                    HttpStatusCode.OK,
                    json,
                )
                host == "api.twitch.tv" && path == "/helix/users" -> respond(
                    """{"data":[{"id":"42","login":"diments","display_name":"DIMENTS","broadcaster_type":"affiliate"}]}""",
                    HttpStatusCode.OK,
                    json,
                )
                host == "api.twitch.tv" && path == "/helix/eventsub/subscriptions" -> respond(
                    """{"data":[{"id":"sub","status":"enabled"}],"total":1,"total_cost":0,"max_total_cost":10}""",
                    HttpStatusCode.Accepted,
                    json,
                )
                host == "api.twitch.tv" && path == "/helix/streams" ->
                    respond("""{"data":[{"id":"s1","type":"live"}]}""", HttpStatusCode.OK, json)
                host == "api.twitch.tv" && path == "/helix/chat/messages" -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    sends += Sent(
                        body.getValue("message").jsonPrimitive.content,
                        body["reply_parent_message_id"]?.jsonPrimitive?.content,
                    )
                    respond(
                        """{"data":[{"message_id":"own-${sentIds.incrementAndGet()}","is_sent":true}]}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
    }

    private companion object {
        val TRACK_A = "A".repeat(22)
        val TRACK_B = "B".repeat(22)

        /** How long the tests wait to see that nothing more happens. */
        const val QUIET_MS = 300L
        const val TIMESTAMP = "2026-11-17T19:47:10.123456789Z"

        fun welcome(sessionId: String) =
            """{"metadata":{"message_id":"${UUID.randomUUID()}","message_type":"session_welcome","message_timestamp":"$TIMESTAMP"},""" +
                """"payload":{"session":{"id":"$sessionId","status":"connected","connected_at":"$TIMESTAMP",""" +
                """"keepalive_timeout_seconds":30,"reconnect_url":null}}}"""

        fun chat(messageId: String, text: String) =
            """{"metadata":{"message_id":"${UUID.randomUUID()}","message_type":"notification","message_timestamp":"$TIMESTAMP",""" +
                """"subscription_type":"channel.chat.message","subscription_version":"1"},""" +
                """"payload":{"subscription":{"id":"sub-chat","status":"enabled","type":"channel.chat.message","version":"1"},""" +
                """"event":{"broadcaster_user_id":"42","broadcaster_user_login":"diments","broadcaster_user_name":"DIMENTS",""" +
                """"chatter_user_id":"7","chatter_user_login":"viewer_one","chatter_user_name":"Viewer_One",""" +
                """"message_id":"$messageId","message":{"text":"$text","fragments":[]},"badges":[],""" +
                """"source_broadcaster_user_id":null}}}"""
    }
}
