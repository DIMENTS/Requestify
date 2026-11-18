package nl.diments.requestify.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import com.sun.nio.file.ExtendedOpenOption
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.ThemeMode
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.settings.CustomSeedPresets
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.window.TitleBarHeight

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class AppScaffoldTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-scaffold")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    /** A graph that is never started and never reaches the network. */
    private fun graph(dataDir: Path = dir) =
        AppGraph(AppPaths(dataDir), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)

    @Test
    fun aSettingThatCannotBeSavedYetIsApplied() = runDesktopComposeUiTest(width = 1180, height = 760) {
        // A file where the data folder should be: every save fails, like a file held by another program.
        val graph = graph(dataDir = dir.resolve("blocker").createFile())
        setContent { AppScaffold(graph) }
        onNode(hasText("Settings") and hasClickAction()).performClick()
        waitForIdle()

        onNodeWithText("Dark").performClick()
        waitForIdle()
        assertEquals(DarkMode.Dark, graph.settings.settings.value.darkMode, "applied at once, saving retried")
    }

    @Test
    fun aSaveProblemShowsOneBanner() = runDesktopComposeUiTest(width = 1180, height = 760) {
        // A file where the data folder should be: every save fails.
        val graph = graph(dataDir = dir.resolve("blocker").createFile())
        setContent { AppScaffold(graph) }
        onNode(hasText("Settings") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("Dark").performClick()
        waitUntil(timeoutMillis = 5_000) { graph.saveHealth.problems.value.isNotEmpty() }
        waitForIdle()

        onAllNodes(hasText("Can’t save ", substring = true)).assertCountEquals(1)
        onNode(hasText("settings.json", substring = true)).assertIsDisplayed()
    }

    @Test
    fun settingsThatCannotBeReadStillOpenTheAppAndSaySo() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val settingsFile = AppPaths(dir).settingsFile
        JsonFileStore(settingsFile, Settings.serializer()) { Settings() }
            .write(Settings(spotifyClientId = "0123456789abcdef0123456789abcdef", darkMode = DarkMode.Dark))
        val text = settingsFile.readText()
        // Another program holds settings.json and denies reading it (a Windows sharing violation).
        Files.newByteChannel(settingsFile, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ).use {
            val graph = graph()
            setContent { AppScaffold(graph) }
            waitForIdle()
            onNodeWithText(
                "Couldn’t read your settings, so Requestify uses the defaults until it can read them again.",
            ).assertIsDisplayed()
            assertEquals(Settings(), graph.settings.settings.value)
        }
        assertEquals(text, settingsFile.readText(), "the saved settings are untouched")
    }

    @Test
    fun firstRunOpensTheSpotifySetup() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        waitForIdle()
        onNodeWithText("Connect Requestify to Spotify and Twitch. You only need to do this once.").assertIsDisplayed()
        onNodeWithText("Create a Spotify app").assertIsDisplayed()
        onNodeWithText("Paste the Client ID").assertExists()
    }

    @Test
    fun theTitleBarShowsTheConnectionStatusAndTheRequestsToggleOnEveryScreen() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        onNode(hasText("Settings") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("Requestify").assertIsDisplayed()
        onNodeWithText("All connected").assertIsDisplayed()
        onNode(hasText("Requests ", substring = true) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun addButtonOpensHomeAndFocusesTheAddField() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        onNodeWithContentDescription("Add a track").performClick()
        waitForIdle()
        onNodeWithText("Queue").assertExists()
        onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun theAddButtonIsCentredOverTheRailItemsAndClearOfTheWindowEdge() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        waitForIdle()
        val add = onNodeWithContentDescription("Add a track").getBoundsInRoot()
        val items = listOf("Home", "Connections", "Rules", "Blacklist", "Settings").map { railItem(it) }

        items.forEach { item ->
            assertEquals(item.centerX().value, add.centerX().value, absoluteTolerance = 1f, "add button over $item")
        }
        assertTrue(add.left >= 16.dp, "the add button is ${add.left} from the window's left edge")
        assertEquals(TitleBarHeight + 12.dp, add.top, "from the title bar to the add button")
        assertEquals(40.dp, items.first().top - add.bottom, "from the add button to the first item")
    }

    @Test
    fun everyPageStartsWhereHomeStarts() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) } // a first run opens on Connections
        waitForIdle()
        val connections = headline("Connections")
        onNode(hasText("Home") and hasClickAction()).performClick()
        waitForIdle()
        val home = headline("Queue")
        onNode(hasText("Settings") and hasClickAction()).performClick()
        waitForIdle()
        val settings = headline("Settings")

        // The rail is 96 dp wide; pages keep 24 dp from it and from the title bar.
        assertEquals(96.dp + 24.dp, home.left, "Home's content start")
        assertEquals(TitleBarHeight + 24.dp, home.top, "Home's top line")
        for ((page, bounds) in listOf("Connections" to connections, "Settings" to settings)) {
            assertEquals(home.left, bounds.left, "$page's headline starts where Home's does")
            assertEquals(home.top, bounds.top, "$page's headline is on Home's top line")
        }
    }

    private fun ComposeUiTest.railItem(label: String): DpRect = onNode(hasText(label) and hasClickAction()).getBoundsInRoot()

    /** A page's headline: its text, not the rail item with the same label. */
    private fun ComposeUiTest.headline(text: String): DpRect = onNode(hasText(text) and !hasClickAction()).getBoundsInRoot()

    private fun DpRect.centerX() = (left + right) / 2

    @Test
    fun customColourIsChosenInSettings() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val graph = graph()
        setContent { AppScaffold(graph) }
        onNode(hasText("Settings") and hasClickAction()).performClick()
        waitForIdle()

        onNodeWithText("Custom").performClick()
        waitForIdle()
        assertEquals(ThemeMode.Custom(BrandSeed.toArgb()), graph.settings.settings.value.themeMode)

        onNodeWithContentDescription("Colour 3").performClick()
        waitForIdle()
        assertEquals(ThemeMode.Custom(CustomSeedPresets[2].toArgb()), graph.settings.settings.value.themeMode)
    }

    @Test
    fun railHasRules() = runDesktopComposeUiTest(width = 1180, height = 760) {
        assertEquals(
            listOf(
                Destination.Home, Destination.Connections, Destination.Rules, Destination.Blacklist, Destination.Log,
                Destination.Settings,
            ),
            Destination.entries,
        )
        val graph = graph()
        setContent { AppScaffold(graph) }
        onNode(hasText("Rules") and hasClickAction()).performClick()
        waitForIdle()

        headline("Rules").let { assertEquals(TitleBarHeight + 24.dp, it.top, "Rules starts where Home starts") }
        onNodeWithText("Paid requests skip the limits. Blacklist, length and explicit always apply.").assertIsDisplayed()
        onNodeWithText("25").assertIsDisplayed()
    }

    @Test
    fun railOpensTheLog() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val graph = graph()
        setContent { AppScaffold(graph) }
        onNode(hasText("Log") and hasClickAction()).performClick()
        waitForIdle()

        headline("Log").let { assertEquals(TitleBarHeight + 24.dp, it.top, "the Log starts where Home starts") }
        onNodeWithText("What went right or wrong, and why. Kept for 30 days.").assertIsDisplayed()
    }
}
