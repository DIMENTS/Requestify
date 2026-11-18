package nl.diments.requestify.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.update.AppVersion
import nl.diments.requestify.core.update.UpdateState
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class AboutCardTest {
    private val originalLocale = Locale.getDefault()
    private val page = "https://github.com/DIMENTS/Requestify/releases/tag/v0.2.1"

    @BeforeTest fun setUp() { Locale.setDefault(Locale.ENGLISH) }
    @AfterTest fun tearDown() { Locale.setDefault(originalLocale) }

    @Test
    fun showsEachUpdateState() = runDesktopComposeUiTest(width = 900, height = 900) {
        var state by mutableStateOf<UpdateState>(UpdateState.Off)
        var canInstall by mutableStateOf(true)
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                AboutCard(version = "0.2.0", state = state, canInstallNow = canInstall, hasWizard = true, actions = AboutActions())
            }
        }
        onNodeWithText("About Requestify").assertIsDisplayed()
        onNodeWithText("Version 0.2.0").assertIsDisplayed()
        onNodeWithText("Check for updates").assertDoesNotExist() // a build that does not update itself

        state = UpdateState.UpToDate(0)
        waitForIdle()
        onNodeWithText("You're up to date").assertIsDisplayed()
        onNodeWithText("Check for updates").assertIsEnabled()

        state = UpdateState.Checking(manual = true)
        waitForIdle()
        onNodeWithText("Checking…").assertIsDisplayed()

        state = UpdateState.Ready(AppVersion(0, 2, 1), "notes", page)
        waitForIdle()
        onNodeWithText("Version 0.2.1 is ready").assertIsDisplayed()
        onNodeWithText("Update now").assertIsEnabled()

        canInstall = false
        waitForIdle()
        onNodeWithText("Update now").assertIsNotEnabled()
        onNodeWithText("You can update while you're offline and requests are closed").assertIsDisplayed()

        state = UpdateState.InstallFailed(AppVersion(0, 2, 1), page)
        waitForIdle()
        onNodeWithText("Update failed").assertIsDisplayed()
        onNodeWithText("Download it yourself").assertIsDisplayed()

        state = UpdateState.CheckFailed(manual = true)
        waitForIdle()
        onNodeWithText("Couldn't check for updates").assertIsDisplayed()

        state = UpdateState.CheckFailed(manual = false)
        waitForIdle()
        onNodeWithText("Couldn't check for updates").assertDoesNotExist() // an automatic check fails quietly
    }

    @Test
    fun theButtonsDoWhatTheySay() = runDesktopComposeUiTest(width = 900, height = 900) {
        val done = mutableListOf<String>()
        val actions = AboutActions(
            check = { done += "check" }, install = { done += "install" }, openRelease = { done += "release" },
            openLicence = { done += "licence" }, openNotice = { done += "notice" }, openWizard = { done += "wizard" },
        )
        var hasWizard by mutableStateOf(true)
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                AboutCard("0.2.0", UpdateState.Ready(AppVersion(0, 2, 1), "notes", page), canInstallNow = true, hasWizard = hasWizard, actions = actions)
            }
        }
        onNodeWithText("Check for updates").performClick()
        onNodeWithText("Update now").performClick()
        onNodeWithText("What's new").performClick()
        onNodeWithText("Licence").performClick()
        onNodeWithText("Credits").performClick()
        onNodeWithText("Open the welcome wizard").performClick()
        assertEquals(listOf("check", "install", "release", "licence", "notice", "wizard"), done)

        hasWizard = false
        waitForIdle()
        onNodeWithText("Open the welcome wizard").assertDoesNotExist()
    }
}
