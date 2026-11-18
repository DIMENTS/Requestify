package nl.diments.requestify

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.Frame
import java.beans.PropertyChangeListener
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.core.secrets.DpapiSecretStore
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.desktop.AppIconImages
import nl.diments.requestify.desktop.AwtTray
import nl.diments.requestify.desktop.CrashGuard
import nl.diments.requestify.desktop.InstanceStart
import nl.diments.requestify.desktop.Installation
import nl.diments.requestify.desktop.JnaRegistry
import nl.diments.requestify.desktop.LaunchMode
import nl.diments.requestify.desktop.Notice
import nl.diments.requestify.desktop.NoticeTarget
import nl.diments.requestify.desktop.Notifier
import nl.diments.requestify.desktop.RunKeyAutostart
import nl.diments.requestify.desktop.SingleInstance
import nl.diments.requestify.desktop.TrayLabels
import nl.diments.requestify.desktop.applyAutostartAtStart
import nl.diments.requestify.desktop.closeWindow
import nl.diments.requestify.desktop.detachedProcess
import nl.diments.requestify.desktop.launchMode
import nl.diments.requestify.desktop.noticeText
import nl.diments.requestify.desktop.trayLook
import nl.diments.requestify.desktop.windowsMessageBox
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import nl.diments.requestify.resources.crash_fatal
import nl.diments.requestify.resources.tray_open
import nl.diments.requestify.resources.tray_quit
import nl.diments.requestify.ui.AppScaffold
import nl.diments.requestify.ui.window.awtMinimumSize
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The app's version as the installed launcher reports it; "dev" under `gradlew run`. */
private val APP_VERSION: String = System.getProperty("jpackage.app-version") ?: "dev"

/** A test run sets this (seconds) to force a crash on the UI thread; never set for the streamer. */
private const val TEST_CRASH_ENV = "REQUESTIFY_TEST_CRASH_AFTER_S"

/** How often the running marker records that the app is still alive. */
private const val ALIVE_EVERY_MS = 5 * 60_000L

fun main(args: Array<String>) {
    val mode = launchMode(args)
    val installation = Installation.current()
    val paths = AppPaths.default()
    val shell = ShellState(visible = initiallyVisible(mode))

    // One Requestify per data folder: a second start shows the first one's window and leaves, touching nothing else.
    val instance = when (
        val start = SingleInstance(paths.dataDir).start(
            onShowRequested = { EventQueue.invokeLater { shell.showRequested() } },
            askToShow = mode == LaunchMode.Normal,
            waitForLockMs = if (mode == LaunchMode.AfterCrash) 10_000 else 0,
        )
    ) {
        is InstanceStart.First -> start.instance
        InstanceStart.AlreadyRunning -> exitProcess(0)
    }

    val crashGuard = CrashGuard(
        dir = paths.dataDir,
        clock = System::currentTimeMillis,
        mode = mode,
        installation = installation,
        version = APP_VERSION,
        relaunch = { command -> detachedProcess(command).start() },
        showFatal = ::windowsMessageBox,
        exit = { code -> exitProcess(code) },
        fatalText = { runBlocking { getString(Res.string.crash_fatal) } },
    )
    val previousRun = crashGuard.previousRun()
    val crashHandler = crashGuard.install()
    crashGuard.markRunning()

    // One scope and one graph for the app's whole life, on the UI thread; switching screens recreates nothing.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashHandler)
    // Secrets are read and decrypted here, before the window; later changes are saved in the background.
    val secrets = DpapiSecretStore(paths.secretsFile, SaveContext(appScope, System::currentTimeMillis))
    val graph = AppGraph(
        paths,
        secrets,
        scope = appScope,
        previousRun = previousRun,
        autostart = RunKeyAutostart(JnaRegistry, installation),
    )
    // The language is read once, before anything is composed; a change applies at the next start.
    localeFor(graph.settings.settings.value.language)?.let(Locale::setDefault)
    applyAutostartAtStart(graph.settings, graph.autostart)
    graph.start()

    lateinit var tray: AwtTray
    var lastNoticeTarget = NoticeTarget.Connections
    val notify: (Notice) -> Unit = { notice ->
        appScope.launch {
            val (title, text) = noticeText(notice)
            lastNoticeTarget = notice.target
            tray.notify(title, text)
        }
    }
    tray = AwtTray(
        labels = runBlocking { TrayLabels(getString(Res.string.tray_open), getString(Res.string.tray_quit)) },
        onOpen = { shell.showRequested() },
        onQuit = {
            quitApp(
                hideTray = { tray.remove() },
                hideWindow = shell::hideNow,
                stopGracefully = graph::stopGracefully,
                markOrderlyEnd = crashGuard::markOrderlyEnd,
                exit = {
                    instance.close()
                    exitProcess(0)
                },
            )
        },
        onMessageClick = { shell.noticeClicked(lastNoticeTarget) },
    )
    appScope.launch {
        combine(graph.problems, graph.saveHealth.problems) { problems, saves -> trayLook(problems, saves) }.collect(tray::show)
    }
    // The icon is there before anything is notified (a notice at start would otherwise find no icon to show it on).
    tray.show(runBlocking { trayLook(graph.problems.value, graph.saveHealth.problems.value) })
    Notifier(graph.problems, graph.saveHealth.problems, graph.settings, appScope, System::currentTimeMillis, notify).start()
    if (mode == LaunchMode.AfterCrash) notify(Notice.RestartedAfterCrash)
    appScope.launch(Dispatchers.IO) {
        while (true) {
            delay(ALIVE_EVERY_MS)
            crashGuard.touch()
        }
    }
    System.getenv(TEST_CRASH_ENV)?.toLongOrNull()?.let { seconds ->
        appScope.launch {
            delay(seconds * 1_000)
            EventQueue.invokeLater { throw IllegalStateException("Test crash ($TEST_CRASH_ENV)") }
        }
    }

    val windowIcon = BitmapPainter(AppIconImages.awt(256).toComposeImageBitmap())
    application(exitProcessOnExit = false) {
        val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
        Window(
            onCloseRequest = { closeWindow(graph.settings, hide = { shell.visible = false }, notify = notify) },
            state = windowState,
            visible = shell.visible,
            title = stringResource(Res.string.app_name),
            icon = windowIcon,
        ) {
            SideEffect {
                window.iconImages = AppIconImages.windowIcons()
                shell.hideWindow = { window.isVisible = false }
            }
            // Shown again (a second start, the tray, a notification): restored and brought to the front.
            LaunchedEffect(shell.raiseRequests) {
                if (shell.raiseRequests > 0) {
                    if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
            }
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
            AppScaffold(graph, window, openOn = shell.openOn, onOpened = shell::opened)
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
