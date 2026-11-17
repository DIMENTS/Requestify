package nl.diments.requestify.ui.setup

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.auth.LoopbackReceiver
import nl.diments.requestify.core.spotify.auth.SpotifyAuth
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class SpotifySetupScreenTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-setup-screen")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    private class NoCatalog : SpotifyCatalog {
        override suspend fun searchTracks(query: String, limit: Int) = SpotifyResult.Ok(emptyList<Track>())
        override suspend fun track(id: String) = SpotifyResult.Failed("unused")
        override suspend fun isPremium() = SpotifyResult.Ok(true)
    }

    /**
     * A valid Client ID is set; the browser opens, but nobody logs in, so a login runs until [loginTimeout]. With
     * [savedLogin] Spotify is already logged in. [controller] plays to [player].
     */
    private fun viewModel(
        loginTimeout: Duration,
        savedLogin: Boolean = false,
        player: FakeSpotifyPlayer = FakeSpotifyPlayer { 0L },
    ): SpotifySetupViewModel {
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
        val secrets = InMemorySecretStore()
        if (savedLogin) secrets.put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1")
        val auth = SpotifyAuth(
            http = HttpClient(MockEngine { error("no token request without a login") }),
            secrets = secrets,
            clientId = { settings.settings.value.spotifyClientId },
            clock = { 0L },
            openBrowser = {},
            receiverFactory = { LoopbackReceiver(0) },
            loginTimeout = loginTimeout,
        )
        controller = PlaybackController(
            player = player,
            store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
            requestsOpen = MutableStateFlow(true),
            paidPriority = { true },
            scope = scope,
            clock = { 0L },
        )
        return SpotifySetupViewModel(settings, auth, NoCatalog(), controller, scope).apply {
            onClientIdChange("0123456789abcdef0123456789abcdef")
        }
    }

    private lateinit var controller: PlaybackController

    private fun ComposeUiTest.clickLogIn() =
        onNode(hasText("Log in with Spotify") and hasClickAction()).performClick()

    @Test
    fun aLoginThatTakesTooLongPointsAtTheClientIdAndRedirectUri() = runDesktopComposeUiTest(width = 1180, height = 1400) {
        setContent { RequestifyTheme(BrandSeed, dark = false) { SpotifySetupScreen(viewModel(loginTimeout = 200.milliseconds)) } }
        clickLogIn()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Logging in took too long", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Logging in took too long. Check the Client ID and the redirect URI, then try again.").assertIsDisplayed()
    }

    @Test
    fun aLoginCanBeCancelled() = runDesktopComposeUiTest(width = 1180, height = 1400) {
        val vm = viewModel(loginTimeout = 5.minutes)
        setContent { RequestifyTheme(BrandSeed, dark = false) { SpotifySetupScreen(vm) } }
        clickLogIn()
        waitForIdle()
        onNodeWithText("Waiting for you to log in in your browser…").assertIsDisplayed()

        onNode(hasText("Cancel") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { !vm.state.value.loggingIn }
        waitForIdle()
        onNode(hasText("Log in with Spotify") and hasClickAction()).assertIsDisplayed()
        assertFalse(vm.state.value.loggingIn)
    }

    @Test
    fun aLoginWithoutTheNewPermissionOffersToLogInAgain() = runDesktopComposeUiTest(width = 1180, height = 1400) {
        val player = FakeSpotifyPlayer { 0L }.apply { recentResult = SpotifyResult.MissingPermission }
        val vm = viewModel(loginTimeout = 5.minutes, savedLogin = true, player = player)
        setContent { RequestifyTheme(BrandSeed, dark = false) { SpotifySetupScreen(vm) } }
        val note = "Log in again to show your last played song."
        onNodeWithText(note).assertDoesNotExist()

        controller.start() // its first poll finds the permission missing
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(note).fetchSemanticsNodes().isNotEmpty() }
        controller.stop()
        onNodeWithText(note).assertIsDisplayed()
        onNode(hasText("Log out") and hasClickAction()).assertIsDisplayed()

        clickLogIn() // logs in again without logging out
        waitForIdle()
        onNodeWithText("Waiting for you to log in in your browser…").assertIsDisplayed()
        onNode(hasText("Cancel") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { !vm.state.value.loggingIn }
    }
}
