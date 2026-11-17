package nl.diments.requestify.ui.setup

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.FakeTwitch
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class TwitchCardTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-twitch-card")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    private fun parts(loggedIn: Boolean = false, account: TwitchAccount? = null, twitch: FakeTwitch = FakeTwitch()) =
        TwitchSetupParts(dir, scope, twitch, loggedIn, account)

    private val affiliate = TwitchAccount("42", "diments", "DIMENTS", "affiliate")

    private fun ComposeUiTest.show(p: TwitchSetupParts) =
        setContent { RequestifyTheme(BrandSeed, dark = true) { TwitchCard(p.vm) } }

    @Test
    fun loggedOutShowsLoginButton() = runDesktopComposeUiTest(width = 900, height = 900) {
        show(parts())
        onNode(hasText("Log in with Twitch") and hasClickAction()).assertIsDisplayed()
        onNodeWithText("Not connected").assertIsDisplayed()
        onNodeWithText("Log out").assertDoesNotExist()
    }

    @Test
    fun codeIsShownWithCopyAndOpen() = runDesktopComposeUiTest(width = 900, height = 900) {
        val p = parts(twitch = FakeTwitch().apply { pendingPolls = Int.MAX_VALUE })
        show(p)
        onNode(hasText("Log in with Twitch") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("ABCDEFGH").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("ABCDEFGH").assertIsDisplayed()
        onNodeWithText("Waiting until you confirm…").assertIsDisplayed()

        onNode(hasText("Copy") and hasClickAction()).performClick()
        assertEquals(listOf("ABCDEFGH"), p.copied)
        onNode(hasText("Open browser") and hasClickAction()).performClick()
        assertEquals(listOf("https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH"), p.opened)

        onNode(hasText("Cancel") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("ABCDEFGH").fetchSemanticsNodes().isEmpty() }
        onNode(hasText("Log in with Twitch") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun anUnreachableTwitchSaysSo() = runDesktopComposeUiTest(width = 900, height = 900) {
        val p = parts(twitch = FakeTwitch().apply { deviceDown = true })
        show(p)
        onNode(hasText("Log in with Twitch") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Couldn’t reach Twitch", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasText("Log in with Twitch") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun loggedInShowsAccountAndAffiliateTag() = runDesktopComposeUiTest(width = 900, height = 900) {
        show(parts(loggedIn = true, account = affiliate))
        onNodeWithText("DIMENTS").assertIsDisplayed()
        onNodeWithText("Connected").assertIsDisplayed()
        onNodeWithText("Affiliate").assertIsDisplayed()
        onNode(hasText("Log out") and hasClickAction()).assertIsDisplayed()
        onNodeWithText("!sr in chat").assertIsDisplayed()
        onNodeWithText("Everyone").assertIsDisplayed()
        onNodeWithText("Cheer with a Spotify link").assertIsDisplayed()
        onNodeWithText("Log in with Twitch").assertDoesNotExist()
    }

    @Test
    fun aPartnerGetsItsOwnTag() = runDesktopComposeUiTest(width = 900, height = 900) {
        show(parts(loggedIn = true, account = affiliate.copy(broadcasterType = "partner")))
        onNodeWithText("Partner").assertIsDisplayed()
    }

    @Test
    fun bitsNeedAnAffiliateOrPartner() = runDesktopComposeUiTest(width = 900, height = 900) {
        show(parts(loggedIn = true, account = affiliate.copy(broadcasterType = "")))
        onNodeWithText("Bits are only available to Twitch Affiliates and Partners.").assertIsDisplayed()
        onNode(hasContentDescription("Increase Cheer with a Spotify link")).assertIsNotEnabled()
        onNodeWithText("Affiliate").assertDoesNotExist()
    }

    @Test
    fun anAffiliateCanStepTheBitsMinimum() = runDesktopComposeUiTest(width = 900, height = 900) {
        val p = parts(loggedIn = true, account = affiliate)
        show(p)
        onNodeWithText("100").assertIsDisplayed()
        onNode(hasContentDescription("Increase Cheer with a Spotify link")).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(150, p.settings.settings.value.twitch.bitsMinimum)
        onNode(hasContentDescription("Decrease Cheer with a Spotify link")).performClick()
        waitForIdle()
        onNode(hasContentDescription("Decrease Cheer with a Spotify link")).performClick() // 100, 50, then 1
        waitForIdle()
        onNode(hasContentDescription("Decrease Cheer with a Spotify link")).performClick()
        waitForIdle()
        assertEquals(1, p.settings.settings.value.twitch.bitsMinimum)
    }

    @Test
    fun thePermissionIsPickedFromAMenu() = runDesktopComposeUiTest(width = 900, height = 900) {
        val p = parts(loggedIn = true, account = affiliate)
        show(p)
        onNodeWithText("Everyone").performClick()
        onNodeWithText("Moderators").performClick()
        waitForIdle()
        assertEquals(ChatPermission.Moderators, p.settings.settings.value.twitch.chatPermission)
    }

    @Test
    fun theCommandsExpandAndADuplicateShowsAnError() = runDesktopComposeUiTest(width = 900, height = 1400) {
        val p = parts(loggedIn = true, account = affiliate)
        show(p)
        onNodeWithText("Skip").assertDoesNotExist()
        onNodeWithText("Commands").performClick()
        onNodeWithText("Skip").assertIsDisplayed()

        onNodeWithText("Skip").performTextClearance()
        onNodeWithText("Skip").performTextInput("queue")
        waitForIdle()
        onAllNodesWithText("Another command already uses this name").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        assertEquals("skip", p.settings.settings.value.twitch.commands.skip)
    }

    @Test
    fun aDeadLoginAsksToLogInAgainAsTheSameAccount() = runDesktopComposeUiTest(width = 900, height = 900) {
        val p = parts(loggedIn = true, account = affiliate)
        p.auth.markLoginNeeded("Twitch refused the login")
        show(p)
        onNodeWithText("Your Twitch login has expired. Log in again.").assertIsDisplayed()
        onNodeWithText("Login expired").assertIsDisplayed()
        onNode(hasText("Log in again as DIMENTS") and hasClickAction()).assertIsDisplayed()
        // The account stays reachable: its settings and Log out are still there.
        onNodeWithText("!sr in chat").assertIsDisplayed()
        onNode(hasText("Log out") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Log in with Twitch") and hasClickAction()).assertIsDisplayed()
        assertEquals(null, p.settings.settings.value.twitch.account)
    }
}
