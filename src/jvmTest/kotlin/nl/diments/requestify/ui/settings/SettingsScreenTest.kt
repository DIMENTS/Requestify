package nl.diments.requestify.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.desktop.Autostart
import nl.diments.requestify.desktop.AutostartState
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private lateinit var dir: Path
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-settings-screen")
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest fun tearDown() {
        Locale.setDefault(originalLocale)
        dir.deleteTestDir()
    }

    /** Windows' startup entry, in memory. */
    private class FakeAutostart(var current: AutostartState) : Autostart {
        val calls = mutableListOf<String>()
        override fun state() = current
        override fun enable(): Boolean { calls += "enable"; current = AutostartState.On; return true }
        override fun disable(): Boolean { calls += "disable"; current = AutostartState.Off; return true }
        override fun repairPath() {}
    }

    private fun settings() = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())

    private fun androidx.compose.ui.test.ComposeUiTest.show(settings: SettingsStore, autostart: Autostart) {
        setContent { RequestifyTheme(BrandSeed, dark = true) { SettingsScreen(settings, autostart) } }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag(START_WITH_WINDOWS_TAG).assertIsEnabled() }.isSuccess || autostart.state() == AutostartState.Unavailable }
        waitForIdle()
    }

    @Test
    fun theSwitchFollowsWindows() = runDesktopComposeUiTest(width = 900, height = 1100) {
        show(settings(), FakeAutostart(AutostartState.On))
        onNodeWithTag(START_WITH_WINDOWS_TAG).assertIsOn()
        onNodeWithText("Starts hidden in the system tray when you sign in to Windows.").assertIsDisplayed()
    }

    @Test
    fun unavailableIsDisabledWithItsHint() = runDesktopComposeUiTest(width = 900, height = 1100) {
        show(settings(), FakeAutostart(AutostartState.Unavailable))
        onNodeWithTag(START_WITH_WINDOWS_TAG).assertIsNotEnabled()
        onNodeWithText("Only in the installed app.").assertIsDisplayed()
    }

    @Test
    fun switchingOffDisablesAndSaves() = runDesktopComposeUiTest(width = 900, height = 1100) {
        val settings = settings()
        val autostart = FakeAutostart(AutostartState.On)
        show(settings, autostart)
        onNodeWithTag(START_WITH_WINDOWS_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { autostart.calls.isNotEmpty() }
        waitForIdle()
        assertEquals(listOf("disable"), autostart.calls)
        assertEquals(false, settings.settings.value.startWithWindows, "remembered: never turned back on by a start")
        onNodeWithTag(START_WITH_WINDOWS_TAG).assertIsOff()
    }

    @Test
    fun taskManagerDisabledShowsOffWithItsHint() = runDesktopComposeUiTest(width = 900, height = 1100) {
        show(settings(), FakeAutostart(AutostartState.DisabledInTaskManager))
        onNodeWithTag(START_WITH_WINDOWS_TAG).assertIsOff()
        onNodeWithText("Switched off in Task Manager → Startup apps. Switch it on here to turn it back on.").assertIsDisplayed()
    }
}
