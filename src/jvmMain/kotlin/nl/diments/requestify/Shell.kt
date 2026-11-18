package nl.diments.requestify

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.desktop.LaunchMode
import nl.diments.requestify.desktop.NoticeTarget
import nl.diments.requestify.ui.Destination

/** Only a start by the streamer shows the window; Windows' autostart and the crash restart start hidden in the tray. */
fun initiallyVisible(mode: LaunchMode): Boolean = mode == LaunchMode.Normal

/**
 * What the shell around the app shows: whether the window is visible, how often it was asked to come to the front, and
 * the screen a notification click asked for. Requests that come before the window exists are kept until it does.
 */
class ShellState(visible: Boolean) {
    var visible by mutableStateOf(visible)

    /** Grows with every request to show the window, so an open one is raised again. */
    var raiseRequests by mutableIntStateOf(0)
        private set

    /** The screen to open once, then null ([opened]). */
    var openOn by mutableStateOf<Destination?>(null)
        private set

    /** A second start, the tray's Open, or a click on the tray icon. */
    fun showRequested() {
        visible = true
        raiseRequests++
    }

    /** A click on a notification: the window, on the screen the notification is about. */
    fun noticeClicked(target: NoticeTarget) {
        openOn = when (target) {
            NoticeTarget.Home -> Destination.Home
            NoticeTarget.Connections -> Destination.Connections
        }
        showRequested()
    }

    fun opened() {
        openOn = null
    }
}

/**
 * Quit from the tray: the icon and the window go at once (a window that waits on screen looks frozen), then the graceful
 * stop (the Channel Points reward paused, pending saves flushed; bounded), then the marker that tells the next start
 * this was no crash, then the exit.
 */
fun quitApp(
    hideTray: () -> Unit,
    hideWindow: () -> Unit,
    stopGracefully: suspend () -> Unit,
    markOrderlyEnd: () -> Unit,
    exit: () -> Unit,
) {
    hideTray()
    hideWindow()
    runBlocking { stopGracefully() }
    markOrderlyEnd()
    exit()
}
