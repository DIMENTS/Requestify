package nl.diments.requestify.desktop

import java.nio.file.Path
import java.util.Locale
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
