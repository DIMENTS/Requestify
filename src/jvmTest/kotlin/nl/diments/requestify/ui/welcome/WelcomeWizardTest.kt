package nl.diments.requestify.ui.welcome

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
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
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.settings.WizardStep
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.AppScaffold

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class WelcomeWizardTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-wizard")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    /** A new streamer's app: the wizard on, nothing connected, never reaching the network. */
    private fun graph() = AppGraph(
        AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
        MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = true,
    )

    private fun ComposeUiTest.click(text: String) {
        onNode(hasText(text) and hasClickAction()).performClick()
        waitForIdle()
    }

    @Test
    fun theWelcomeCardLeadsToSpotify() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        onNodeWithTag(WIZARD_TAG).assertIsDisplayed()
        onNodeWithText("Hey! Welcome to Requestify").assertIsDisplayed()
        click("Let's go")
        onNodeWithText("Connect Spotify").assertIsDisplayed()
        onNodeWithText("Create a Spotify app", substring = true).assertIsDisplayed() // the Connections page's own steps
    }

    @Test
    fun laterSkipsToTheNextCard() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        click("Let's go")
        click("Later")
        onNodeWithText("Connect Twitch").assertIsDisplayed()
        click("Later")
        onNodeWithText("Connect donations").assertIsDisplayed()
    }

    @Test
    fun aConnectedPlatformShowsAsABadge() = runDesktopComposeUiTest(width = 1180, height = 760) {
        JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }.write(
            Settings(
                twitch = TwitchSettings(account = TwitchAccount("1", "lofilisa", "LofiLisa")),
                welcomeStep = WizardStep.Welcome, // a wizard in progress: connecting Twitch did not end it
            ),
        )
        setContent { AppScaffold(graph()) }
        onNodeWithTag(wizardBadgeTag(WizardPlatform.Twitch), useUnmergedTree = true).assertIsDisplayed()
        click("Let's go")
        click("Later") // Spotify
        onNodeWithText("Connect donations").assertIsDisplayed() // Twitch is done: its card is skipped
    }

    @Test
    fun skipEndsTheWizardAndShowsTheApp() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val graph = graph()
        setContent { AppScaffold(graph) }
        click("Skip")
        onNodeWithTag(WIZARD_TAG).assertDoesNotExist()
        onNode(hasText("Settings") and hasClickAction()).assertIsDisplayed()
        assertEquals(true, graph.settings.settings.value.welcomeDone)
    }

    @Test
    fun theDoneCardSummarises() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        click("Let's go")
        repeat(3) { click("Later") }
        onNodeWithText("Ready to rock!").assertIsDisplayed()
        assertEquals(3, onAllNodesWithText("You can do this later, in Connections").fetchSemanticsNodes().size)
        onNodeWithText("Requestify now starts with Windows", substring = true).assertIsDisplayed()
        click("Open Requestify")
        onNodeWithTag(WIZARD_TAG).assertDoesNotExist()
    }

    @Test
    fun itFitsTheMinimumWindow() = runDesktopComposeUiTest(width = 720, height = 640) {
        setContent { AppScaffold(graph()) }
        onNodeWithText("Let's go").assertIsDisplayed()
        click("Let's go")
        onNodeWithText("Later").assertIsDisplayed() // the card scrolls; its way out stays in view
    }

    @Test
    fun choosingNoneMovesTheWizardOn() = runDesktopComposeUiTest(width = 1180, height = 760) {
        setContent { AppScaffold(graph()) }
        click("Let's go")
        click("Later") // Spotify
        click("Later") // Twitch
        onNodeWithText("Connect donations").assertIsDisplayed()
        click("None")
        onNodeWithText("Ready to rock!").assertIsDisplayed()
    }

    @Test
    fun theWizardTwitchCardOffersTheBot() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val secrets = InMemorySecretStore().apply { put(nl.diments.requestify.core.secrets.SecretKeys.TWITCH_REFRESH_TOKEN, "r0") }
        val graph = AppGraph(
            AppPaths(dir, updatesDir = dir.resolve("updates")), secrets,
            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = true,
        )
        setContent { AppScaffold(graph) }
        click("Let's go")
        click("Later") // Spotify
        onNodeWithText("Connect Twitch").assertIsDisplayed()
        graph.settings.tryUpdate { it.copy(twitch = it.twitch.copy(account = TwitchAccount("42", "diments", "DIMENTS"))) } // logged in
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Optional: let your bot send the replies.").fetchSemanticsNodes().isNotEmpty()
        }
        mainClock.advanceTimeBy(5_000) // past the celebration
        waitForIdle()
        onNodeWithText("Connect Twitch").assertExists() // the card stays
        onNodeWithText("Optional: let your bot send the replies.").assertExists()
        onNode(hasText("Continue") and hasClickAction()).assertExists()
    }
}

/** The wizard in a maximized window: the card keeps a calm height and the card with its dots sits in the middle. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class WelcomeWizardTallWindowTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-wizard-tall")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    @Test
    fun onATallWindowTheCardKeepsItsHeightAndSitsInTheMiddle() = runDesktopComposeUiTest(width = 1920, height = 1400) {
        val graph = AppGraph(
            AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = true,
        )
        setContent { AppScaffold(graph) }
        onNode(hasText("Let's go") and hasClickAction()).performClick()
        waitForIdle()
        val wizard = onNodeWithTag(WIZARD_TAG).getBoundsInRoot()
        val card = onNodeWithTag(WIZARD_CARD_TAG).getBoundsInRoot()
        assertTrue(card.bottom - card.top <= WizardCardMaxHeight, "the card is ${card.bottom - card.top} tall")
        val above = card.top - wizard.top
        val below = wizard.bottom - card.bottom
        assertTrue(above > 200.dp && below > 200.dp, "the card sits in the middle: $above above, $below below")
        onNode(hasText("Later") and hasClickAction()).assertIsDisplayed()
    }
}
