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
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.storage.AppPaths

/**
 * Offscreen renders for the user to look at before a UI task is reviewed. They run only when the environment
 * variable `REQUESTIFY_RENDERS` names a folder; the PNGs go there. Never part of a normal test run.
 */
@OptIn(ExperimentalTestApi::class)
class Renders {
    private val out: Path? = System.getenv("REQUESTIFY_RENDERS")?.takeIf { it.isNotBlank() }?.let { Path.of(it).createDirectories() }

    /** Writes the whole window as [name].png. */
    private fun ComposeUiTest.capture(name: String) {
        val dir = out ?: return
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", dir.resolve("$name.png").toFile())
    }

    private fun dutch(block: () -> Unit) {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("nl-NL"))
        try { block() } finally { Locale.setDefault(saved) }
    }

    @Test
    fun narrowBanners() {
        if (out == null) return
        dutch {
            for (dark in listOf(true, false)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dataDir = createTempDirectory("requestify-render").resolve("blocker").createFile()
                try {
                    runDesktopComposeUiTest(width = 720, height = 640) {
                        val graph = AppGraph(AppPaths(dataDir), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)
                        graph.settings.tryUpdate { it.copy(darkMode = if (dark) DarkMode.Dark else DarkMode.Light) }
                        setContent { AppScaffold(graph) }
                        waitUntil(timeoutMillis = 5_000) { graph.saveHealth.problems.value.isNotEmpty() }
                        waitForIdle()
                        capture("narrow-banner-${if (dark) "dark" else "light"}")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun saveBanner() {
        if (out == null) return
        dutch {
            for (dark in listOf(true, false)) for ((width, height) in listOf(1180 to 760, 720 to 640)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                // A file where the data folder should be: every save fails.
                val dataDir = createTempDirectory("requestify-render").resolve("blocker").createFile()
                try {
                    runDesktopComposeUiTest(width = width, height = height) {
                        val graph = AppGraph(AppPaths(dataDir), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)
                        graph.settings.tryUpdate { it.copy(darkMode = if (dark) DarkMode.Dark else DarkMode.Light) }
                        setContent { AppScaffold(graph) }
                        waitUntil(timeoutMillis = 5_000) { graph.saveHealth.problems.value.isNotEmpty() }
                        waitForIdle()
                        capture("save-banner-${if (dark) "dark" else "light"}-$width")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
