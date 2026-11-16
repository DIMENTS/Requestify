package nl.diments.requestify.ui.setup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.io.IOException
import java.net.BindException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.auth.LoopbackReceiver
import nl.diments.requestify.core.spotify.auth.SpotifyAuth
import nl.diments.requestify.core.spotify.auth.SpotifyAuthStatus
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class SpotifySetupViewModelTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-setup") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private class FakeCatalog(private val premium: SpotifyResult<Boolean?>) : SpotifyCatalog {
        override suspend fun searchTracks(query: String, limit: Int) = SpotifyResult.Ok(emptyList<Track>())
        override suspend fun track(id: String) = SpotifyResult.Failed("unused")
        override suspend fun isPremium() = premium
    }

    private fun settingsStore() =
        SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })

    /**
     * A real [SpotifyAuth] over a mock token endpoint and a loopback receiver on a free port. [openBrowser] plays
     * the user's part: by default nobody logs in.
     */
    private fun auth(
        settings: SettingsStore,
        openBrowser: (url: String, receiver: LoopbackReceiver) -> Unit = { _, _ -> },
        receiverFactory: (() -> LoopbackReceiver)? = null,
        secrets: SecretStore = InMemorySecretStore(),
    ): SpotifyAuth {
        var receiver: LoopbackReceiver? = null
        return SpotifyAuth(
            http = HttpClient(
                MockEngine {
                    respond(
                        """{"access_token":"a1","token_type":"Bearer","expires_in":3600,"refresh_token":"r1"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            secrets = secrets,
            clientId = { settings.settings.value.spotifyClientId },
            clock = { 0L },
            openBrowser = { url -> openBrowser(url, receiver!!) },
            receiverFactory = receiverFactory ?: { LoopbackReceiver(0).also { receiver = it } },
        )
    }

    /** Answers the authorize page like a browser would after the user allowed access. */
    private fun allowInBrowser(url: String, receiver: LoopbackReceiver) {
        val state = Url(url).parameters["state"]!!
        Thread {
            java.net.http.HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${receiver.boundPort}/callback?code=c1&state=$state")).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }.start()
    }

    private fun viewModel(
        settings: SettingsStore,
        auth: SpotifyAuth,
        scope: CoroutineScope,
        premium: SpotifyResult<Boolean?> = SpotifyResult.Ok(true),
    ): SpotifySetupViewModel {
        val controller = PlaybackController(
            player = FakeSpotifyPlayer { 0L },
            store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
            requestsOpen = MutableStateFlow(true),
            paidPriority = { true },
            scope = scope,
            clock = { 0L },
        )
        return SpotifySetupViewModel(settings, auth, FakeCatalog(premium), controller, scope)
    }

    /**
     * Logs in for real (the browser step is answered from another thread) and returns the state once Premium
     * has been checked. Runs on real threads: the loopback callback cannot follow the test's virtual time.
     */
    private suspend fun loginAndCheckPremium(premium: SpotifyResult<Boolean?>): SpotifySetupState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val settings = settingsStore()
            val vm = viewModel(settings, auth(settings, openBrowser = ::allowInBrowser), scope, premium)
            vm.onClientIdChange(VALID_ID)
            vm.onLogin()
            return withContext(Dispatchers.Default) {
                withTimeout(10.seconds) { vm.state.first { it.premium != null } }
            }
        } finally {
            scope.cancel()
        }
    }

    /** A valid Client ID is set; nobody answers in the browser, so a login runs until it times out. */
    private fun TestScope.nobodyLogsIn(onBrowser: (String) -> Unit = {}): SpotifySetupViewModel {
        val settings = settingsStore()
        val vm = viewModel(settings, auth(settings, openBrowser = { url, _ -> onBrowser(url) }), backgroundScope)
        vm.onClientIdChange(VALID_ID)
        return vm
    }

    @Test
    fun validClientId() {
        assertTrue(isValidSpotifyClientId(VALID_ID))
        assertTrue(isValidSpotifyClientId("0123456789ABCDEFabcdef0123456789"))
        assertFalse(isValidSpotifyClientId(VALID_ID.dropLast(1)))
        assertFalse(isValidSpotifyClientId(VALID_ID + "a"))
        assertFalse(isValidSpotifyClientId(VALID_ID.dropLast(1) + "g"))
        assertTrue(isValidSpotifyClientId("  $VALID_ID \t"))
    }

    @Test
    fun invalidClientIdIsNotSaved() = runTest {
        val settings = settingsStore()
        val vm = viewModel(settings, auth(settings), backgroundScope)
        vm.onClientIdChange("not-a-client-id")
        runCurrent()
        assertNull(settings.settings.value.spotifyClientId)
        assertEquals("not-a-client-id", vm.state.value.clientId)
        assertFalse(vm.state.value.clientIdValid)
    }

    @Test
    fun validClientIdIsSaved() = runTest {
        val settings = settingsStore()
        val vm = viewModel(settings, auth(settings), backgroundScope)
        vm.onClientIdChange("  $VALID_ID ")
        runCurrent()
        assertEquals(VALID_ID, settings.settings.value.spotifyClientId)
        assertEquals(VALID_ID, settingsStore().settings.value.spotifyClientId, "saved to disk")
        assertTrue(vm.state.value.clientIdValid)
    }

    @Test
    fun freeAccountShowsNotPremium() = runTest {
        val state = loginAndCheckPremium(SpotifyResult.Ok(false))
        assertEquals(SpotifyAuthStatus.LoggedIn, state.auth)
        assertEquals(PremiumCheck.NotPremium, state.premium)
    }

    @Test
    fun missingProductIsUnknown() = runTest {
        assertEquals(PremiumCheck.Unknown, loginAndCheckPremium(SpotifyResult.Ok(null)).premium)
    }

    @Test
    fun premiumAccountShowsPremium() = runTest {
        assertEquals(PremiumCheck.Premium, loginAndCheckPremium(SpotifyResult.Ok(true)).premium)
    }

    @Test
    fun loginTimeoutShowsError() = runTest {
        val vm = nobodyLogsIn()
        vm.onLogin()
        runCurrent()
        assertTrue(vm.state.value.loggingIn)
        assertNull(vm.state.value.loginError)

        advanceTimeBy(5.minutes + 1.seconds)
        runCurrent()
        assertNotNull(vm.state.value.loginError)
        assertEquals(LoginError.TimedOut, vm.state.value.loginError)
        assertFalse(vm.state.value.loggingIn)
        assertEquals(SpotifyAuthStatus.LoggedOut, vm.state.value.auth)
    }

    @Test
    fun portInUseHasItsOwnError() = runTest {
        val settings = settingsStore()
        val vm = viewModel(settings, auth(settings, receiverFactory = { throw BindException("in use") }), backgroundScope)
        vm.onClientIdChange(VALID_ID)
        vm.onLogin()
        runCurrent()
        assertEquals(LoginError.PortInUse, vm.state.value.loginError)
        assertFalse(vm.state.value.loggingIn)
    }

    @Test
    fun loginUrlIsShownWhileTheBrowserCouldNotOpen() = runTest {
        lateinit var vm: SpotifySetupViewModel
        vm = nobodyLogsIn(onBrowser = { url -> vm.showLoginUrl(url) })
        vm.onLogin()
        runCurrent()
        val shown = vm.state.value.pendingLoginUrl
        assertTrue(shown != null && shown.startsWith("https://accounts.spotify.com/authorize?"), "shown: $shown")

        advanceTimeBy(5.minutes + 1.seconds)
        runCurrent()
        assertNull(vm.state.value.pendingLoginUrl, "gone once the login ended")
    }

    @Test
    fun clientIdThatCannotBeSavedSaysSoAndIsSavedAgainOnLogin() = runTest {
        // A file where the settings folder should be: every write fails, like a file held by another program.
        val blocker = dir.resolve("blocker").createFile()
        val settings = SettingsStore(JsonFileStore(blocker.resolve("settings.json"), Settings.serializer()) { Settings() })
        val vm = viewModel(settings, auth(settings), backgroundScope)
        vm.onClientIdChange(VALID_ID)
        runCurrent()
        assertNull(settings.settings.value.spotifyClientId)
        assertEquals(SaveFailure.ClientId, vm.state.value.saveFailure)

        vm.onLogin()
        runCurrent()
        assertFalse(vm.state.value.loggingIn, "no login while the Client ID is not saved")

        blocker.deleteExisting() // the other program let go
        vm.onLogin()
        runCurrent()
        assertEquals(VALID_ID, settings.settings.value.spotifyClientId)
        assertNull(vm.state.value.saveFailure)
        assertTrue(vm.state.value.loggingIn)
    }

    @Test
    fun logoutThatCannotBeSavedStaysLoggedIn() = runTest {
        val stored = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val secrets = object : SecretStore by stored {
            override fun remove(key: String) {
                throw IOException("secrets file in use")
            }
        }
        val settings = settingsStore()
        val vm = viewModel(settings, auth(settings, secrets = secrets), backgroundScope)
        runCurrent()
        assertEquals(SpotifyAuthStatus.LoggedIn, vm.state.value.auth)

        vm.onLogout()
        runCurrent()
        assertEquals(SpotifyAuthStatus.LoggedIn, vm.state.value.auth)
        assertEquals(SaveFailure.Logout, vm.state.value.saveFailure)
        assertEquals("r1", stored.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    private companion object {
        const val VALID_ID = "0123456789abcdef0123456789abcdef"
    }
}
