package nl.diments.requestify.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.deleteTestDir

/**
 * The screenshots in the README (`docs/images/nl/`, `docs/images/en/`), made offscreen with made-up requests, requesters
 * and album art from [LiveFixture] (colour gradients: no real covers). Runs only when `REQUESTIFY_SCREENSHOTS` names the
 * folder to write to: `REQUESTIFY_SCREENSHOTS=docs/images .\gradlew.bat jvmTest --tests "*ReadmeScreenshots*" --rerun`.
 */
@OptIn(ExperimentalTestApi::class)
class ReadmeScreenshots {
    private val out: Path? = System.getenv("REQUESTIFY_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let { Path.of(it).createDirectories() }

    /** The language being captured: `nl` or `en`, also the subfolder of [out]. */
    private var language = "nl"

    private fun ComposeUiTest.capture(name: String) {
        val dir = out?.resolve(language)?.createDirectories() ?: return
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", dir.resolve("$name.png").toFile())
    }

    private fun inLanguage(tag: String, block: () -> Unit) {
        val saved = Locale.getDefault()
        language = tag.substringBefore('-')
        Locale.setDefault(Locale.forLanguageTag(tag))
        try { block() } finally { Locale.setDefault(saved) }
    }

    @Test
    fun readmeScreens() {
        if (out == null) return
        for (tag in listOf("nl-NL", "en-US")) inLanguage(tag) {
            LiveFixture.FakeArt().use { art ->
                // Home and the Log of a streamer mid-stream.
                for ((name, screen) in listOf("home" to Destination.Home, "log" to Destination.Log)) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val dir = createTempDirectory("requestify-readme")
                    LiveFixture.write(dir, art)
                    try {
                        runDesktopComposeUiTest(width = 1280, height = 800) {
                            val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
                            val graph = AppGraph(AppPaths(dir, updatesDir = dir.resolve("updates")), secrets, LiveFixture.spotify(art), scope)
                            graph.start()
                            setContent { AppScaffold(graph, openOn = screen) }
                            waitUntil(timeoutMillis = 15_000) { graph.home.state.value.nowPlaying?.track != null }
                            Thread.sleep(2_500) // the album art arrives and the colours follow it
                            waitForIdle()
                            capture(name)
                            graph.stop()
                        }
                    } finally {
                        scope.cancel()
                        dir.deleteTestDir()
                    }
                }
                // A new streamer: the welcome wizard, then the Connections page.
                for ((name, wizard) in listOf("wizard" to true, "connections" to false)) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val dir = createTempDirectory("requestify-readme")
                    JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }.write(Settings(darkMode = DarkMode.Dark))
                    try {
                        runDesktopComposeUiTest(width = 1280, height = 800) {
                            val graph = AppGraph(
                                AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                                MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = wizard,
                            )
                            setContent { AppScaffold(graph, openOn = if (wizard) null else Destination.Connections) }
                            Thread.sleep(1_200)
                            waitForIdle()
                            capture(name)
                        }
                    } finally {
                        scope.cancel()
                        dir.deleteTestDir()
                    }
                }
                // The wizard celebrating a connection (paused mid-confetti).
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-readme")
                JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }.write(Settings(darkMode = DarkMode.Dark))
                val spotifyConnected = MutableStateFlow(false)
                try {
                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        val graph = AppGraph(AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)
                        val vm = nl.diments.requestify.ui.welcome.WelcomeWizardViewModel(graph.settings, spotifyConnected, MutableStateFlow(false), MutableStateFlow(false), scope)
                        vm.begin()
                        mainClock.autoAdvance = false
                        setContent {
                            nl.diments.requestify.ui.theme.RequestifyTheme(nl.diments.requestify.ui.theme.BrandSeed, dark = true) {
                                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                    nl.diments.requestify.ui.welcome.WelcomeWizard(vm, graph.spotifySetup, graph.twitchSetup, graph.donationsSetup)
                                }
                            }
                        }
                        mainClock.advanceTimeBy(1_500)
                        spotifyConnected.value = true
                        mainClock.advanceTimeBy(380)
                        capture("wizard-celebration")
                    }
                } finally {
                    scope.cancel()
                    dir.deleteTestDir()
                }
            }
        }
    }
}
