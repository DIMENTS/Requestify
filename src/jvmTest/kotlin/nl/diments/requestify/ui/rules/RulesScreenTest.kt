package nl.diments.requestify.ui.rules

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.rules.Rules
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class RulesScreenTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-rules")
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    private fun store(file: Path = dir.resolve("settings.json")) =
        SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() })

    private fun ComposeUiTest.show(settings: SettingsStore) {
        setContent { RequestifyTheme(BrandSeed, dark = true) { RulesScreen(settings) } }
        waitForIdle()
    }

    @Test
    fun defaultsShown() = runDesktopComposeUiTest(width = 900, height = 1100) {
        show(store())
        onNodeWithText("Paid requests skip the limits. Blacklist, length and explicit always apply.").assertIsDisplayed()
        onNodeWithText("Queue").assertIsDisplayed()
        onNodeWithText("Songs").assertIsDisplayed()

        onNodeWithText("25").assertIsDisplayed() // maximum in the queue
        onNodeWithText("2").assertIsDisplayed() // per viewer at once
        onNodeWithText("off").assertIsDisplayed() // cooldown
        onNodeWithText("No duplicate songs").assertIsOn()
        onNodeWithText("60 min").assertIsDisplayed() // the duplicate window
        onNodeWithText("Paid requests go first").assertIsOn()
        onNodeWithText("0:30").assertIsDisplayed()
        onNodeWithText("7:00").assertIsDisplayed()
        onNodeWithText("Block explicit songs").assertIsOff()
    }

    @Test
    fun steppersRespectBoundsAndSteps() = runDesktopComposeUiTest(width = 900, height = 1100) {
        val settings = store()
        show(settings)

        onNodeWithContentDescription("Increase Maximum length").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText("7:30").assertIsDisplayed()
        assertEquals(450_000, settings.settings.value.rules.maxDurationMs)

        settings.update { it.copy(rules = it.rules.copy(maxDurationMs = 900_000)) }
        waitForIdle()
        onNodeWithText("15:00").assertIsDisplayed()
        onNodeWithContentDescription("Increase Maximum length").assertIsNotEnabled()
        onNodeWithContentDescription("Decrease Maximum length").assertIsEnabled()

        settings.update { it.copy(rules = it.rules.copy(maxQueueLength = 1, maxPerViewer = 10, cooldownMinutes = 60)) }
        waitForIdle()
        onNodeWithContentDescription("Decrease Maximum in the queue").assertIsNotEnabled()
        onNodeWithContentDescription("Increase Per viewer at once").assertIsNotEnabled()
        onNodeWithContentDescription("Increase Cooldown per viewer").assertIsNotEnabled()

        settings.update { it.copy(rules = it.rules.copy(minDurationMs = 0, cooldownMinutes = 0)) }
        waitForIdle()
        onNodeWithContentDescription("Decrease Minimum length").assertIsNotEnabled()
        onNodeWithContentDescription("Decrease Cooldown per viewer").assertIsNotEnabled()
    }

    @Test
    fun changesAreSaved() = runDesktopComposeUiTest(width = 900, height = 1100) {
        val settings = store()
        show(settings)

        onNodeWithContentDescription("Increase Maximum in the queue").performClick()
        onNodeWithContentDescription("Decrease Per viewer at once").performClick()
        onNodeWithContentDescription("Increase Cooldown per viewer").performClick() // off -> 1 min
        onNodeWithContentDescription("Increase Minimum length").performClick() // 0:30 -> 0:45
        onNodeWithText("Block explicit songs").performClick()
        onNodeWithText("Paid requests go first").performClick()
        onNodeWithContentDescription("Decrease Duplicate window").performClick() // 60 -> 45
        waitForIdle()

        val rules = settings.settings.value.rules
        assertEquals(
            Rules(maxQueueLength = 26, maxPerViewer = 1, cooldownMinutes = 1, minDurationMs = 45_000, blockExplicit = true, duplicateWindowMinutes = 45),
            rules,
        )
        assertEquals(false, settings.settings.value.paidPriority)

        onNodeWithText("No duplicate songs").performClick()
        waitForIdle()
        assertEquals(false, settings.settings.value.rules.noDuplicates)
        onNodeWithContentDescription("Decrease Duplicate window").assertDoesNotExist()

        // What was saved is what the next start reads.
        val reread = store()
        assertEquals(settings.settings.value, reread.settings.value)
    }

    @Test
    fun aFailedSaveSaysSoAndKeepsTheValue() = runDesktopComposeUiTest(width = 900, height = 1100) {
        // A file where the data folder should be: every save fails.
        val settings = store(dir.resolve("blocker").createFile().resolve("settings.json"))
        show(settings)

        onNodeWithContentDescription("Increase Maximum in the queue").performClick()
        waitForIdle()
        onNodeWithText("Couldn’t save the change. Try again.").assertIsDisplayed()
        onNodeWithText("25").assertIsDisplayed()
        assertEquals(25, settings.settings.value.rules.maxQueueLength)
    }
}
