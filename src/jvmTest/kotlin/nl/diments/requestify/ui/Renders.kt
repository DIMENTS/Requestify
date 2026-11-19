package nl.diments.requestify.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.foundation.layout.padding
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

    /** The tray icon at the sizes Windows uses (and the window's 256), with and without the warning mark, 8× larger. */
    @Test
    fun trayIcons() {
        val dir = out ?: return
        for (size in listOf(16, 20, 24, 32)) for (warning in listOf(false, true)) {
            val small = nl.diments.requestify.desktop.AppIconImages.awt(size, warning)
            val big = java.awt.image.BufferedImage(size * 8, size * 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            big.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                drawImage(small, 0, 0, size * 8, size * 8, null)
                dispose()
            }
            ImageIO.write(big, "png", dir.resolve("tray-$size${if (warning) "-warning" else ""}.png").toFile())
        }
    }

    /** The title bar with its glyph under two album colours and a custom colour, dark and light. */
    @Test
    fun titleBarGlyph() {
        if (out == null) return
        val seeds = mapOf(
            "album-green" to androidx.compose.ui.graphics.Color(0xFF1DB954),
            "album-pink" to androidx.compose.ui.graphics.Color(0xFFE91E63),
            "custom-blue" to androidx.compose.ui.graphics.Color(0xFF3F51B5),
        )
        dutch {
            for ((name, seed) in seeds) for (dark in listOf(true, false)) {
                runDesktopComposeUiTest(width = 520, height = 56) {
                    setContent {
                        nl.diments.requestify.ui.theme.RequestifyTheme(seed, dark) {
                            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                nl.diments.requestify.ui.window.TitleBar(problems = emptyList())
                            }
                        }
                    }
                    waitForIdle()
                    capture("titlebar-$name-${if (dark) "dark" else "light"}")
                }
            }
        }
    }

    /** Settings with Start with Windows: on, not installed, switched off in Task Manager; dark and light. */
    @Test
    fun settingsStartup() {
        if (out == null) return
        dutch {
            for (state in listOf(
                nl.diments.requestify.desktop.AutostartState.On,
                nl.diments.requestify.desktop.AutostartState.Unavailable,
                nl.diments.requestify.desktop.AutostartState.DisabledInTaskManager,
            )) for (dark in listOf(true, false)) {
                val dir = createTempDirectory("requestify-render")
                val settings = nl.diments.requestify.core.settings.SettingsStore(
                    nl.diments.requestify.core.storage.JsonFileStore(dir.resolve("settings.json"), nl.diments.requestify.core.settings.Settings.serializer()) {
                        nl.diments.requestify.core.settings.Settings()
                    },
                    nl.diments.requestify.core.storage.inCallerSaveContext(),
                )
                val autostart = object : nl.diments.requestify.desktop.Autostart {
                    override fun state() = state
                    override fun enable() = true
                    override fun disable() = true
                    override fun repairPath() {}
                }
                runDesktopComposeUiTest(width = 900, height = 900) {
                    setContent {
                        nl.diments.requestify.ui.theme.RequestifyTheme(nl.diments.requestify.ui.theme.BrandSeed, dark) {
                            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                nl.diments.requestify.ui.settings.SettingsScreen(settings, autostart)
                            }
                        }
                    }
                    Thread.sleep(300)
                    waitForIdle()
                    capture("settings-startup-${state.name}-${if (dark) "dark" else "light"}")
                }
            }
        }
    }

    /** The title-bar chip with a DonoLink key that expires in 6 days. */
    @Test
    fun keyExpiringChip() {
        if (out == null) return
        dutch {
            val problem = nl.diments.requestify.core.health.ConnectionProblem.Donation(
                nl.diments.requestify.core.settings.DonationSourceKind.DonoLink,
                nl.diments.requestify.core.health.DonationProblem.KeyExpiring,
                System.currentTimeMillis() + 6 * 24 * 3_600_000L + 3_600_000L,
            )
            runDesktopComposeUiTest(width = 620, height = 56) {
                setContent {
                    nl.diments.requestify.ui.theme.RequestifyTheme(nl.diments.requestify.ui.theme.BrandSeed, true) {
                        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                            nl.diments.requestify.ui.window.TitleBar(problems = listOf(problem))
                        }
                    }
                }
                waitForIdle()
                capture("chip-key-expiring")
            }
        }
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

    /** Settings → Over Requestify in each update state, Dutch, dark and light. */
    @Test
    fun aboutStates() {
        if (out == null) return
        val page = "https://github.com/DIMENTS/Requestify/releases/tag/v0.2.1"
        val v021 = nl.diments.requestify.core.update.AppVersion(0, 2, 1)
        val states = listOf(
            "uptodate" to (nl.diments.requestify.core.update.UpdateState.UpToDate(0) to true),
            "ready" to (nl.diments.requestify.core.update.UpdateState.Ready(v021, "notes", page) to true),
            "ready-live" to (nl.diments.requestify.core.update.UpdateState.Ready(v021, "notes", page) to false),
            "failed" to (nl.diments.requestify.core.update.UpdateState.InstallFailed(v021, page) to true),
            "checkfailed" to (nl.diments.requestify.core.update.UpdateState.CheckFailed(manual = true) to true),
        )
        dutch {
            for ((name, value) in states) for (dark in listOf(true, false)) {
                runDesktopComposeUiTest(width = 900, height = 520) {
                    setContent {
                        nl.diments.requestify.ui.theme.RequestifyTheme(nl.diments.requestify.ui.theme.BrandSeed, dark) {
                            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(nl.diments.requestify.ui.theme.Spacing.xl)) {
                                    nl.diments.requestify.ui.settings.AboutCard("0.2.0", value.first, value.second, hasWizard = true, actions = nl.diments.requestify.ui.settings.AboutActions())
                                }
                            }
                        }
                    }
                    waitForIdle()
                    capture("about-$name-${if (dark) "dark" else "light"}")
                }
            }
        }
    }

    /** Home after an update, with its card, and the rail's dot while the next one waits; Dutch, dark and light. */
    @Test
    fun updatedCardAndDot() {
        if (out == null) return
        dutch {
            for (dark in listOf(true, false)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-render")
                val store = nl.diments.requestify.core.update.UpdateStore(dir.resolve("updates"))
                store.saveJustUpdated(nl.diments.requestify.core.update.JustUpdated("0.2.0", "0.2.1", "## Nieuw\n- Een **welkomstwizard**\n- Requestify werkt zichzelf bij"))
                store.savePending(nl.diments.requestify.core.update.PendingUpdate("0.2.2", "Requestify-0.2.2.msi", "ab", "", ""))
                try {
                    runDesktopComposeUiTest(width = 1180, height = 760) {
                        val graph = AppGraph(
                            AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope,
                            runningVersion = nl.diments.requestify.core.update.AppVersion(0, 2, 1),
                        )
                        graph.settings.tryUpdate {
                            it.copy(darkMode = if (dark) DarkMode.Dark else DarkMode.Light, spotifyClientId = "0123456789abcdef0123456789abcdef")
                        }
                        setContent { AppScaffold(graph, openOn = nl.diments.requestify.ui.Destination.Home) }
                        Thread.sleep(500)
                        waitForIdle()
                        capture("updated-card-${if (dark) "dark" else "light"}")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    /** Connections with the platform logos; Dutch, dark and light. */
    @Test
    fun connections() {
        if (out == null) return
        dutch {
            for (dark in listOf(true, false)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-render")
                try {
                    runDesktopComposeUiTest(width = 1180, height = 1500) {
                        val graph = AppGraph(AppPaths(dir), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)
                        graph.settings.tryUpdate { it.copy(darkMode = if (dark) DarkMode.Dark else DarkMode.Light) }
                        setContent { AppScaffold(graph, openOn = nl.diments.requestify.ui.Destination.Connections) }
                        Thread.sleep(300)
                        waitForIdle()
                        capture("connections-${if (dark) "dark" else "light"}")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    /** Every welcome-wizard card, Dutch, dark and light, at the default and the minimum window size. */
    @Test
    fun wizardCards() {
        if (out == null) return
        dutch {
            for (dark in listOf(true, false)) for ((width, height) in listOf(1180 to 760, 720 to 640)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-render")
                try {
                    runDesktopComposeUiTest(width = width, height = height) {
                        val graph = AppGraph(
                            AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = true,
                        )
                        graph.settings.tryUpdate { it.copy(darkMode = if (dark) DarkMode.Dark else DarkMode.Light) }
                        setContent { AppScaffold(graph) }
                        val suffix = "${if (dark) "dark" else "light"}-$width"
                        fun shot(name: String) {
                            Thread.sleep(400)
                            waitForIdle()
                            capture("wizard-$name-$suffix")
                        }
                        shot("1-welcome")
                        graph.welcome.begin()
                        shot("2-spotify")
                        graph.welcome.later()
                        shot("3-twitch")
                        graph.welcome.later()
                        shot("4-donations")
                        graph.welcome.later()
                        shot("5-done")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
