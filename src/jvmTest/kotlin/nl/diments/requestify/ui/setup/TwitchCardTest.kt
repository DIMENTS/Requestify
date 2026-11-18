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
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.settings.ChannelPointsSettings
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

    @Test
    fun anAccountWhoseLoginIsGoneIsNotConnected() = runDesktopComposeUiTest(width = 900, height = 900) {
        // The account is set up, but there is no working login (the saved one could not be read, or was rolled back).
        show(parts(loggedIn = false, account = affiliate))
        onNodeWithText("Login expired").assertIsDisplayed()
        onNodeWithText("Connected").assertDoesNotExist()
        onNode(hasText("Log in again as DIMENTS") and hasClickAction()).assertIsDisplayed()
    }

    private val own = ChannelPointsSettings(
        enabled = true,
        rewardId = "rw-1",
        managed = true,
        title = "Song request",
        cost = 500,
        prompt = "Type a song",
    )

    private fun pointsParts(points: ChannelPointsSettings, account: TwitchAccount = affiliate) =
        TwitchSetupParts(dir, scope, FakeTwitch(), loggedIn = true, account = account, points = points)

    private fun ComposeUiTest.awaitText(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun nonAffiliateRowDisabled() = runDesktopComposeUiTest(width = 900, height = 1200) {
        show(pointsParts(ChannelPointsSettings(), account = affiliate.copy(broadcasterType = "")))
        onNodeWithText("Channel Points are only available to Twitch Affiliates and Partners.").assertIsDisplayed()
        onNode(hasContentDescription("Channel Points reward")).assertIsNotEnabled()
    }

    @Test
    fun anAffiliateSwitchesOnAndCreatesTheRewardFromTheSheet() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(ChannelPointsSettings())
        show(p)
        onNodeWithText("Viewers spend Channel Points on a song request").assertIsDisplayed()
        onNode(hasContentDescription("Channel Points reward")).performClick()
        awaitText("Create new reward")
        onNode(hasText("Create") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { p.settings.settings.value.twitch.channelPoints.rewardId != null }
        assertEquals(1, p.helix.creates.size)
        assertEquals(true, p.settings.settings.value.twitch.channelPoints.managed)
        awaitText("Open while requests are open")
        onAllNodesWithText("Create new reward").fetchSemanticsNodes().let { assertEquals(0, it.size, "the sheet is closed") }
        onNodeWithText("Song request").assertIsDisplayed()
    }

    @Test
    fun theSheetGroupsOwnAndForeignRewards() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(ChannelPointsSettings())
        p.helix.rewards += p.helix.reward("rw-1")
        p.helix.manageable += "rw-1"
        p.helix.rewards += p.helix.reward("rw-2", title = "Hydrate", cost = 100)
        show(p)
        onNode(hasContentDescription("Channel Points reward")).performClick()
        awaitText("Made elsewhere")
        onNodeWithText("Made by Requestify").assertIsDisplayed()
        onNodeWithText("Hydrate").assertIsDisplayed()
        onNodeWithText("Recommended").assertIsDisplayed()
        onNode(hasText("Use") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { p.settings.settings.value.twitch.channelPoints.rewardId == "rw-1" }
        assertEquals(emptyList(), p.helix.creates, "no duplicate")
        assertEquals(true, p.settings.settings.value.twitch.channelPoints.managed)
    }

    @Test
    fun foreignLinkShowsWarningFirst() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(ChannelPointsSettings())
        p.helix.rewards += p.helix.reward("rw-2", title = "Hydrate", cost = 100)
        show(p)
        onNode(hasContentDescription("Channel Points reward")).performClick()
        awaitText("Made elsewhere")
        onNode(hasText("Link directly") and hasClickAction()).performClick()

        onNodeWithText("Link without management?").assertIsDisplayed()
        onNodeWithText("No refunds and no fulfilling", substring = true).assertIsDisplayed()
        onNodeWithText("Not paused when requests close", substring = true).assertIsDisplayed()
        onNodeWithText("No catch-up after a restart", substring = true).assertIsDisplayed()
        onNodeWithText("read-only here", substring = true).assertIsDisplayed()
        assertEquals(null, p.settings.settings.value.twitch.channelPoints.rewardId, "nothing is linked before the user agrees")

        onNode(hasText("Link anyway") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { p.settings.settings.value.twitch.channelPoints.rewardId == "rw-2" }
        assertEquals(false, p.settings.settings.value.twitch.channelPoints.managed)
        awaitText("Linked without management: Requestify cannot refund points")
        onNode(hasText("Copy as Requestify reward") and hasClickAction()).assertIsDisplayed()
        assertEquals(emptyList(), p.helix.patches + p.helix.creates)
    }

    @Test
    fun aCopyAsksForTheNameAndReportsADuplicate() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(ChannelPointsSettings())
        p.helix.rewards += p.helix.reward("rw-2", title = "Hydrate", cost = 100)
        p.helix.createStatus = 400
        show(p)
        onNode(hasContentDescription("Channel Points reward")).performClick()
        awaitText("Made elsewhere")
        onNode(hasText("Copy as Requestify reward") and hasClickAction()).performClick()
        onNode(hasText("Copy") and hasClickAction()).performClick()
        awaitText("Another reward on your channel already has this name.")

        p.helix.createStatus = 200
        onNode(hasText("Name")).performTextClearance()
        onNode(hasText("Name")).performTextInput("Hydrate 2")
        onNode(hasText("Copy") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { p.settings.settings.value.twitch.channelPoints.rewardId != null }
        assertEquals("Hydrate 2", p.settings.settings.value.twitch.channelPoints.title)
        assertEquals(100, p.settings.settings.value.twitch.channelPoints.cost)
    }

    @Test
    fun anOwnRewardShowsItsSettingsAndPausesWithTheRequests() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(own)
        p.helix.rewards += p.helix.reward("rw-1")
        p.helix.manageable += "rw-1"
        show(p)
        onNodeWithText("Song request").assertIsDisplayed()
        onNodeWithText("Type a song").assertIsDisplayed()
        onNodeWithText("500").assertIsDisplayed()
        onNodeWithText("Open while requests are open").assertIsDisplayed()
        onNodeWithText("Change this in the Twitch dashboard").assertDoesNotExist()

        p.requestsOpen.value = false
        onNodeWithText("Paused: stream is offline").assertIsDisplayed()

        onNode(hasContentDescription("Increase Cost")).performClick()
        waitUntil(timeoutMillis = 10_000) { p.settings.settings.value.twitch.channelPoints.cost == 550 }
    }

    @Test
    fun aLinkedForeignRewardIsReadOnly() = runDesktopComposeUiTest(width = 900, height = 1200) {
        show(pointsParts(own.copy(managed = false)))
        onNodeWithText("Linked without management: Requestify cannot refund points").assertIsDisplayed()
        onNodeWithText("Change this in the Twitch dashboard").assertIsDisplayed()
        onNodeWithText("500 points").assertIsDisplayed()
        onAllNodesWithText("Increase Cost").fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun theCardSaysWhileTwitchDoesNotMatch() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(own)
        p.helix.rewards += p.helix.reward("rw-1")
        p.helix.manageable += "rw-1"
        p.helix.updateStatus = 500
        show(p)
        p.rewards.start()
        p.requestsOpen.value = false
        awaitText("Twitch still has the old setting. Requestify keeps trying.")
        p.helix.updateStatus = 200
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Twitch still has the old setting. Requestify keeps trying.").fetchSemanticsNodes().isEmpty()
        }
        p.rewards.stop()
    }

    @Test
    fun aRemovedRewardOffersCreateAgain() = runDesktopComposeUiTest(width = 900, height = 1200) {
        val p = pointsParts(own.copy(rewardRemoved = true))
        show(p)
        onNodeWithText("The reward was removed on Twitch").assertIsDisplayed()
        onNode(hasText("Create again") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { p.settings.settings.value.twitch.channelPoints.rewardId == "new-1" }
        assertEquals(false, p.settings.settings.value.twitch.channelPoints.rewardRemoved)
        assertEquals("Song request", p.helix.creates.single().body!!.getValue("title").jsonPrimitive.content)
    }
}
