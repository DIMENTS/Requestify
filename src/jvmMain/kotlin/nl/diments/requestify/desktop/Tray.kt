package nl.diments.requestify.desktop

import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BaseMultiResolutionImage
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.SaveProblem
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.connection_all_connected
import nl.diments.requestify.resources.tray_cannot_save
import nl.diments.requestify.resources.tray_tooltip
import nl.diments.requestify.ui.common.statusLook
import org.jetbrains.compose.resources.getString

/** What the tray icon shows: the warning mark, its tooltip and the status line at the top of its menu. */
data class TrayLook(val warning: Boolean, val tooltip: String, val statusLine: String)

/**
 * The tray's look for [problems] (the chip's list) and [saveProblems]: "All connected" without a mark while nothing is
 * wrong; otherwise the mark and the top problem as the chip says it (a file that cannot be saved when nothing else is
 * wrong).
 */
suspend fun trayLook(problems: List<ConnectionProblem>, saveProblems: List<SaveProblem>): TrayLook {
    val status = when {
        problems.isNotEmpty() -> problems.first().statusLook().let { getString(it.text, *it.args.toTypedArray()) }
        saveProblems.isNotEmpty() -> getString(Res.string.tray_cannot_save, saveProblems.joinToString { it.file })
        else -> getString(Res.string.connection_all_connected)
    }
    return TrayLook(
        warning = problems.isNotEmpty() || saveProblems.isNotEmpty(),
        tooltip = getString(Res.string.tray_tooltip, status),
        statusLine = status,
    )
}

/** The system tray icon, from the app's point of view. */
interface TrayPort {
    fun show(look: TrayLook)
    fun notify(title: String, text: String)
    fun remove()
}

/** The menu's words, in the app language. */
data class TrayLabels(val open: String, val quit: String)

/**
 * The AWT tray icon: a single left click opens the window ([onOpen]); a click on one of its notifications calls
 * [onMessageClick]; its menu has a disabled status line, Open and Quit. Windows picks the icon size that suits its
 * scale from all the sizes given.
 */
class AwtTray(
    labels: TrayLabels,
    private val onOpen: () -> Unit,
    onQuit: () -> Unit,
    onMessageClick: () -> Unit,
) : TrayPort {
    private val status = MenuItem("Requestify").apply { isEnabled = false }
    private val icon = TrayIcon(images(warning = false), "Requestify", PopupMenu().apply {
        add(status)
        addSeparator()
        add(MenuItem(labels.open).apply { addActionListener { onOpen() } })
        add(MenuItem(labels.quit).apply { addActionListener { onQuit() } })
    }).apply {
        isImageAutoSize = true
        // A click on a notification, or a double-click on the icon (Windows sends the same action for both).
        addActionListener { if (clicks.isNoticeClick()) onMessageClick() else onOpen() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) onOpen()
            }
        })
    }
    private var added = false
    private val pending = NoticeQueue()
    private val clicks = NoticeClicks()
    private var warning: Boolean? = null

    override fun show(look: TrayLook) {
        if (look.warning != warning) {
            icon.image = images(look.warning)
            warning = look.warning
        }
        icon.toolTip = look.tooltip
        status.label = look.statusLine
        if (!added && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().add(icon)
                added = true
                pending.ready(::display)
            } catch (_: AWTException) {
                // no tray right now (Explorer restarting): the next look tries again
            }
        }
    }

    override fun notify(title: String, text: String) = pending.offer(title, text, ::display)

    private fun display(title: String, text: String) {
        clicks.shown()
        icon.displayMessage(title, text, TrayIcon.MessageType.INFO)
    }

    override fun remove() {
        if (added) SystemTray.getSystemTray().remove(icon)
        added = false
    }

    private fun images(warning: Boolean) =
        BaseMultiResolutionImage(*listOf(16, 20, 24, 32, 40, 48, 64).map { AppIconImages.awt(it, warning) }.toTypedArray())
}

/**
 * The window's close button: the window hides and everything keeps running. The very first time, one notification
 * says so ([Notice.CloseHint]); it is remembered in the settings.
 */
fun closeWindow(settings: SettingsStore, hide: () -> Unit, notify: (Notice) -> Unit) {
    hide()
    if (!settings.settings.value.closeHintShown && settings.tryUpdate { it.copy(closeHintShown = true) }) notify(Notice.CloseHint)
}

/** Notifications sent before the tray icon exists (at start) wait for it, so none is lost. */
class NoticeQueue {
    private val waiting = mutableListOf<Pair<String, String>>()
    private var ready = false

    @Synchronized
    fun offer(title: String, text: String, show: (String, String) -> Unit) {
        if (ready) show(title, text) else waiting += title to text
    }

    /** The icon is there: shows what waited, and everything from now on at once. */
    @Synchronized
    fun ready(show: (String, String) -> Unit) {
        ready = true
        waiting.forEach { (title, text) -> show(title, text) }
        waiting.clear()
    }
}

/**
 * Windows reports a click on a notification and a double-click on the tray icon as the same action: it counts as a
 * notification click only within [windowMs] after one was shown.
 */
class NoticeClicks(private val clock: () -> Long = System::currentTimeMillis, private val windowMs: Long = 30_000) {
    @Volatile
    private var lastShownAt: Long? = null

    fun shown() {
        lastShownAt = clock()
    }

    fun isNoticeClick(): Boolean = lastShownAt?.let { clock() - it <= windowMs } == true
}
