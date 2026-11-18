package nl.diments.requestify.desktop

import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.SaveProblem
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.storage.inCallerSaveContext

class TrayTest {
    private lateinit var dir: Path
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-tray")
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun tearDown() {
        Locale.setDefault(originalLocale)
        dir.deleteTestDir()
    }

    @Test
    fun nothingWrongIsAllConnectedWithoutAMark() = runBlocking {
        val look = trayLook(emptyList(), emptyList())
        assertFalse(look.warning)
        assertEquals("All connected", look.statusLine)
        assertEquals("Requestify — All connected", look.tooltip)
    }

    @Test
    fun aProblemShowsTheMarkAndItsText() = runBlocking {
        val look = trayLook(listOf(ConnectionProblem.TwitchLoginNeeded, ConnectionProblem.TwitchDisconnected), emptyList())
        assertTrue(look.warning)
        assertTrue(look.statusLine.isNotBlank() && look.statusLine != "All connected", look.statusLine)
        assertEquals("Requestify — ${look.statusLine}", look.tooltip, "the top problem, as the chip says it")
    }

    @Test
    fun aSaveProblemAloneShowsTheMark() = runBlocking {
        val look = trayLook(emptyList(), listOf(SaveProblem("settings.json", "disk full", 0)))
        assertTrue(look.warning)
        assertEquals("Can’t save settings.json", look.statusLine)
    }

    @Test
    fun aNoticeBeforeTheIconExistsWaitsForIt() {
        val shown = mutableListOf<String>()
        val queue = NoticeQueue()
        queue.offer("Requestify is herstart", "…") { title, _ -> shown += title } // sent at start, before the icon
        assertEquals(emptyList(), shown)
        queue.ready { title, _ -> shown += title }
        assertEquals(listOf("Requestify is herstart"), shown, "shown once the icon is there, not lost")
        queue.offer("Later", "…") { title, _ -> shown += title }
        assertEquals(listOf("Requestify is herstart", "Later"), shown)
    }

    @Test
    fun onlyAClickSoonAfterANotificationCountsAsOne() {
        var now = 0L
        val clicks = NoticeClicks(clock = { now })
        assertFalse(clicks.isNoticeClick(), "a double-click on the icon, no notification shown: just open")
        clicks.shown()
        now += 10_000
        assertTrue(clicks.isNoticeClick())
        now += 60_000
        assertFalse(clicks.isNoticeClick(), "long after: a double-click on the icon again")
    }

    @Test
    fun showingTheIconFromTwoThreadsAtOnceAddsItOnce() {
        // At start, Main shows the icon itself while the problem collector does the same on the UI thread.
        val adds = AtomicInteger()
        val insideAdd = CountDownLatch(1)
        val tray = AwtTray(
            TrayLabels(open = "Open", quit = "Quit"),
            onOpen = {},
            onQuit = {},
            onMessageClick = {},
            addIcon = {
                // Like Windows' tray: an icon that is already in it is refused.
                if (adds.incrementAndGet() > 1) throw IllegalArgumentException("adding TrayIcon that is already added")
                insideAdd.countDown()
                Thread.sleep(300) // the real add is a native call that takes a while
            },
            removeIcon = {},
        )
        val look = TrayLook(warning = false, tooltip = "Requestify — All connected", statusLine = "All connected")
        val failures = ConcurrentLinkedQueue<Throwable>()
        val first = thread { runCatching { tray.show(look) }.onFailure { failures += it } }
        assertTrue(insideAdd.await(5, TimeUnit.SECONDS))
        val second = thread { runCatching { tray.show(look) }.onFailure { failures += it } }
        first.join()
        second.join()
        assertEquals(emptyList(), failures.map { it.toString() }, "the second show must not crash the app")
        assertEquals(1, adds.get(), "the icon is added once")
    }

    @Test
    fun theCloseHintIsShownOnceEver() {
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
        val sent = mutableListOf<Notice>()
        var hidden = 0
        closeWindow(settings, hide = { hidden++ }, notify = { sent += it })
        closeWindow(settings, hide = { hidden++ }, notify = { sent += it })
        assertEquals(2, hidden, "the window hides every time")
        assertEquals(listOf<Notice>(Notice.CloseHint), sent, "the hint only the first time ever")
        assertTrue(settings.settings.value.closeHintShown)
    }
}
