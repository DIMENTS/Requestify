package nl.diments.requestify

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.beans.PropertyChangeListener
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.core.secrets.DpapiSecretStore
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import nl.diments.requestify.ui.AppScaffold
import nl.diments.requestify.ui.window.awtMinimumSize
import org.jetbrains.compose.resources.stringResource

fun main() {
    // One scope and one graph for the app's whole life, on the UI thread; switching screens recreates nothing.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val paths = AppPaths.default()
    val graph = AppGraph(paths, DpapiSecretStore(paths.secretsFile), scope = appScope)
    // The language is read once, before anything is composed; a change applies at the next start.
    localeFor(graph.settings.settings.value.language)?.let(Locale::setDefault)
    graph.start()

    application {
        val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
        Window(
            onCloseRequest = {
                // Pauses the Channel Points reward first (at most 3 s), so nobody redeems it while the app is gone.
                runBlocking { graph.stopGracefully() }
                exitApplication()
            },
            state = windowState,
            title = stringResource(Res.string.app_name),
        ) {
            // dp -> device px (Compose's density) -> AWT units (the window's transform); redone when the window
            // moves to a screen with another scale.
            val density = LocalDensity.current.density
            DisposableEffect(window, density) {
                fun apply() {
                    val awtScale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                    window.minimumSize = awtMinimumSize(MinimumWindowSize, density, awtScale)
                }
                val listener = PropertyChangeListener { apply() }
                apply()
                window.addPropertyChangeListener("graphicsConfiguration", listener)
                onDispose { window.removePropertyChangeListener("graphicsConfiguration", listener) }
            }
            AppScaffold(graph, window)
        }
    }
}

/** The smallest the window gets: Home's narrow layout still shows a whole row of the queue under Now Playing. */
internal val MinimumWindowSize = DpSize(720.dp, 640.dp)

/** Null for [AppLanguage.System]: the JVM default (from Windows) stays. */
private fun localeFor(language: AppLanguage): Locale? = when (language) {
    AppLanguage.System -> null
    AppLanguage.English -> Locale.ENGLISH
    AppLanguage.Dutch -> Locale.forLanguageTag("nl-NL")
}
