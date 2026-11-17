package nl.diments.requestify

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.diments.requestify.core.secrets.DpapiSecretStore
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import nl.diments.requestify.ui.AppScaffold
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
                graph.stop()
                exitApplication()
            },
            state = windowState,
            title = stringResource(Res.string.app_name),
        ) {
            LaunchedEffect(window) {
                window.minimumSize = with(MinimumWindowSize) { Dimension(width.value.toInt(), height.value.toInt()) }
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
