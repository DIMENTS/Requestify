package nl.diments.requestify.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
    fun aSettingThatCannotBeSavedSaysSoAndIsNotApplied() = runDesktopComposeUiTest(width = 1180, height = 760) {
        // A file where the data folder should be: every save fails, like a file held by another program.
        val graph = graph(dataDir = dir.resolve("blocker").createFile())
        setContent { AppScaffold(graph) }
        onNode(hasText("Settings") and hasClickAction()).performClick()
        waitForIdle()

        onNodeWithText("Dark").performClick()
        waitForIdle()
        onNodeWithText("Couldn’t save the change. Try again.").assertIsDisplayed()
        assertEquals(DarkMode.System, graph.settings.settings.value.darkMode)
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
                "Couldn’t read your settings, so Requestify uses the defaults for now. Restart Requestify to try again.",
            ).assertIsDisplayed()
            assertEquals(Settings(), graph.settings.settings.value)
        }
        assertEquals(text, settingsFile.readText(), "the saved settings are untouched")
    }

    @Test
    fun firstRunOpensTheSpotifySetup() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        waitForIdle()
        onNodeWithText("Connect Requestify to your own Spotify app. You only need to do this once.").assertIsDisplayed()
        onNodeWithText("Create a Spotify app").assertIsDisplayed()
        onNodeWithText("Paste the Client ID").assertExists()
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
}
