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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.settings.ChannelPointsSettings
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.eventsub.EventSubState
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

    /** The reward calls Twitch got, as "METHOD ?query body". */
    private fun twitchEngine(calls: MutableList<String>) = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.encodedPath == "/oauth2/token" ->
                respond("""{"access_token":"a1","expires_in":14000,"refresh_token":"r2","scope":[],"token_type":"bearer"}""", HttpStatusCode.OK, json)
            request.url.encodedPath.endsWith("/channel_points/custom_rewards") -> {
                calls += "${request.method.value} ${request.url.encodedQuery} ${(request.body as? TextContent)?.text.orEmpty()}"
                respond(
                    """{"data":[{"id":"rw-1","title":"Song request","cost":500,"prompt":"x","is_enabled":true,"is_paused":true}]}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
            else -> respondError(HttpStatusCode.ServiceUnavailable)
        }
    }

    private suspend fun exitCalls(points: ChannelPointsSettings): List<String> {
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }.write(
            Settings(twitch = TwitchSettings(account = TwitchAccount("42", "diments", "DIMENTS", "affiliate"), channelPoints = points)),
        )
        val calls = CopyOnWriteArrayList<String>()
        val secrets = InMemorySecretStore().apply { put(SecretKeys.TWITCH_REFRESH_TOKEN, "r1") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val graph = AppGraph(AppPaths(dir), secrets, twitchEngine(calls), scope)
            withTimeout(10.seconds) { graph.stopGracefully() }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
        return calls.toList()
    }

    @Test
    fun gracefulExitPausesOwnReward() = runBlocking {
        val calls = exitCalls(ChannelPointsSettings(enabled = true, rewardId = "rw-1", managed = true))
        val pause = calls.single()
        assertTrue(pause.startsWith("PATCH ") && "id=rw-1" in pause, pause)
        assertTrue(""""is_paused":true""" in pause, pause)
    }

    @Test
    fun gracefulExitLeavesOtherRewardsAlone() = runBlocking {
        assertEquals(emptyList(), exitCalls(ChannelPointsSettings(enabled = true, rewardId = "rw-1", managed = false)), "not ours")
    }

    @Test
    fun gracefulExitGivesUpAfterThreeSeconds() = runBlocking {
        JsonFileStore(AppPaths(dir).settingsFile, Settings.serializer()) { Settings() }.write(
            Settings(
                twitch = TwitchSettings(
                    account = TwitchAccount("42", "diments", "DIMENTS", "affiliate"),
                    channelPoints = ChannelPointsSettings(enabled = true, rewardId = "rw-1", managed = true),
                ),
            ),
        )
        val secrets = InMemorySecretStore().apply { put(SecretKeys.TWITCH_REFRESH_TOKEN, "r1") }
        val hang = MockEngine { delay(60_000); respondError(HttpStatusCode.ServiceUnavailable) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val graph = AppGraph(AppPaths(dir), secrets, hang, scope)
            val started = System.nanoTime()
            graph.stopGracefully()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs in 2_500..6_000, "the exit waited $tookMs ms")
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun unreachable() = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
}
