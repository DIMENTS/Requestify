package nl.diments.requestify

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.Destination
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

    private fun unreachable() = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
}
