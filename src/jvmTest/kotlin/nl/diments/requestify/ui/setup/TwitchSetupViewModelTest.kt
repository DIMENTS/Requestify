package nl.diments.requestify.ui.setup

import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.ChannelPointsSettings
import nl.diments.requestify.core.settings.ChatCommandNames
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.FakeTwitch
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.core.twitch.points.FakeRewardsHelix

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class TwitchSetupViewModelTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-twitch-setup") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val account = TwitchAccount("42", "diments", "DIMENTS", "affiliate")

    private fun TestScope.parts(
        loggedIn: Boolean = false,
        account: TwitchAccount? = null,
        configure: FakeTwitch.() -> Unit = {},
    ): TwitchSetupParts {
        val twitch = FakeTwitch(
            now = { currentTime },
            dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        ).apply(configure)
        return TwitchSetupParts(dir, backgroundScope, twitch, loggedIn, account, clock = { currentTime })
    }

    @Test
    fun loginShowsCodeThenSavesAccount() = runTest {
        val p = parts()
        assertEquals(TwitchLoginState.Idle, p.vm.login.value)

        p.vm.onLogin()
        runCurrent()
        val shown = assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value)
        assertEquals("ABCDEFGH", shown.code.userCode)

        p.vm.onOpenBrowser()
        p.vm.onCopyCode()
        assertEquals(listOf(shown.code.verificationUri), p.opened)
        assertEquals(listOf("ABCDEFGH"), p.copied)

        advanceTimeBy(5.seconds) // the first poll: still pending
        runCurrent()
        assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value)
        assertNull(p.settings.settings.value.twitch.account)

        advanceTimeBy(5.seconds) // the user confirmed
        runCurrent()
        assertEquals(TwitchLoginState.Idle, p.vm.login.value)
        assertEquals(TwitchAuthStatus.LoggedIn, p.auth.status.value)
        assertEquals(TwitchAccount("42", "diments", "DIMENTS", "affiliate"), p.settings.settings.value.twitch.account)
    }

    @Test
    fun expiredCodeShowsExpired() = runTest {
        val p = parts { pendingPolls = Int.MAX_VALUE }
        p.vm.onLogin()
        runCurrent()
        assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value)

        advanceTimeBy(1_801.seconds)
        runCurrent()
        assertEquals(TwitchLoginState.Expired, p.vm.login.value)
        assertEquals(TwitchAuthStatus.LoggedOut, p.auth.status.value)
        assertNull(p.settings.settings.value.twitch.account)
    }

    @Test
    fun twitchBeingDownIsUnreachable() = runTest {
        val p = parts { deviceDown = true }
        p.vm.onLogin()
        runCurrent()
        assertEquals(TwitchLoginState.Unreachable, p.vm.login.value)
    }

    @Test
    fun cancellingALoginGoesBackToIdleAndAllowsANewOne() = runTest {
        val p = parts { pendingPolls = Int.MAX_VALUE }
        p.vm.onLogin()
        runCurrent()
        assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value)

        p.vm.onCancelLogin()
        runCurrent()
        assertEquals(TwitchLoginState.Idle, p.vm.login.value)

        p.vm.onLogin()
        runCurrent()
        assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value)
    }

    @Test
    fun loginKeepsTheAccountWhenTheProfileCannotBeRead() = runTest {
        // The id server's answer is enough to know who logged in; the profile only adds the display name and type.
        val p = parts { usersDown = true }
        p.vm.onLogin()
        runCurrent()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(TwitchLoginState.Idle, p.vm.login.value)
        assertEquals(TwitchAccount("42", "diments", "diments", ""), p.settings.settings.value.twitch.account)
    }

    @Test
    fun logoutClearsAccount() = runTest {
        val p = parts(loggedIn = true, account = account)
        p.vm.onLogout()
        runCurrent()
        assertNull(p.settings.settings.value.twitch.account)
        assertEquals(TwitchAuthStatus.LoggedOut, p.auth.status.value)
        assertNull(p.secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun chatAndBitsSettingsAreSaved() = runTest {
        val p = parts(loggedIn = true, account = account)
        p.vm.onChatEnabled(false)
        p.vm.onChatPermission(ChatPermission.Moderators)
        p.vm.onBitsEnabled(false)
        p.vm.onBitsMinimum(250)
        val twitch = p.settings.settings.value.twitch
        assertEquals(false, twitch.chatEnabled)
        assertEquals(ChatPermission.Moderators, twitch.chatPermission)
        assertEquals(false, twitch.bitsEnabled)
        assertEquals(250, twitch.bitsMinimum)
        assertEquals(twitch, p.vm.settings.value.twitch)
    }

    @Test
    fun theBitsMinimumStaysBetween1And10000() = runTest {
        val p = parts(loggedIn = true, account = account)
        p.vm.onBitsMinimum(0)
        assertEquals(1, p.settings.settings.value.twitch.bitsMinimum)
        p.vm.onBitsMinimum(50_000)
        assertEquals(10_000, p.settings.settings.value.twitch.bitsMinimum)
    }

    @Test
    fun commandNamesAreNormalisedAndDuplicatesRefused() = runTest {
        val p = parts(loggedIn = true, account = account)

        p.vm.onCommandName { it.copy(song = "  !NowPlaying ") }
        assertEquals("nowplaying", p.settings.settings.value.twitch.commands.song)
        assertTrue(p.vm.commandErrors.value.isEmpty())

        p.vm.onCommandName { it.copy(skip = "queue") } // taken by !queue
        assertEquals("skip", p.settings.settings.value.twitch.commands.skip, "a duplicate is not saved")
        assertEquals(CommandError.Duplicate, p.vm.commandErrors.value[CommandField.Skip])
        assertEquals(CommandError.Duplicate, p.vm.commandErrors.value[CommandField.Queue])
        assertEquals("queue", p.vm.commands.value.skip, "the field still shows what was typed")

        p.vm.onCommandName { it.copy(skip = "!  ") } // nothing left once trimmed
        assertEquals("skip", p.settings.settings.value.twitch.commands.skip, "a blank name is not saved")
        assertEquals(mapOf(CommandField.Skip to CommandError.Blank), p.vm.commandErrors.value)

        p.vm.onCommandName { it.copy(skip = "next") } // fixed: saved, errors gone
        assertEquals("next", p.settings.settings.value.twitch.commands.skip)
        assertTrue(p.vm.commandErrors.value.isEmpty())
    }

    @Test
    fun aCommandNameWithASpaceIsRefused() = runTest {
        val p = parts(loggedIn = true, account = account)
        p.vm.onCommandName { it.copy(request = "song request") } // could never match: a command is the first word
        assertEquals("sr", p.settings.settings.value.twitch.commands.request, "not saved")
        assertEquals(mapOf(CommandField.Request to CommandError.Whitespace), p.vm.commandErrors.value)
        assertEquals(mapOf(CommandField.Song to CommandError.Whitespace), commandNameErrors(ChatCommandNames(song = "now\tplaying")))
    }

    @Test
    fun cancellingWhileTheAccountIsLookedUpStillFinishesTheLogin() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = parts { usersGate = gate }
        p.vm.onLogin()
        runCurrent()
        advanceTimeBy(10.seconds) // approved; the profile request is now waiting
        runCurrent()

        p.vm.onCancelLogin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(TwitchAuthStatus.LoggedIn, p.auth.status.value)
        assertEquals("DIMENTS", p.settings.settings.value.twitch.account?.displayName, "never logged in without an account")
    }

    @Test
    fun cancellingWhileTwitchIsAskedWhoLoggedInLeavesNoHalfLogin() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = parts { validateGate = gate }
        p.vm.onLogin()
        runCurrent()
        advanceTimeBy(10.seconds) // the tokens are saved; TwitchAuth is asking who logged in
        runCurrent()

        p.vm.onCancelLogin()
        runCurrent()
        assertEquals(TwitchAuthStatus.LoggedOut, p.auth.status.value)
        assertNull(p.secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertNull(p.settings.settings.value.twitch.account)
    }

    @Test
    fun aLogoutWhoseSettingsCannotBeSavedYetStillLogsOut() = runTest {
        val p = parts(loggedIn = true, account = account)
        // Writing the settings fails while a non-empty directory sits where the file goes.
        val file = dir.resolve("twitch-settings.json")
        file.deleteExisting()
        file.createDirectory()
        file.resolve("keep").writeText("x")

        p.vm.onLogout()
        runCurrent()
        assertEquals(false, p.vm.saveFailed.value)
        assertEquals(null, p.settings.settings.value.twitch.account, "applied at once, saving retried")
        assertEquals(null, p.secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun logoutPausesTheOwnRewardFirst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val helix = FakeRewardsHelix().apply {
                rewards += reward("rw-1")
                manageable += "rw-1"
            }
            val points = ChannelPointsSettings(enabled = true, rewardId = "rw-1", managed = true, title = "Song request", cost = 500)
            val p = TwitchSetupParts(dir, scope, FakeTwitch(), loggedIn = true, account = account, points = points, helix = helix)
            p.vm.onLogout()
            withTimeout(10.seconds) { while (p.auth.status.value != TwitchAuthStatus.LoggedOut) delay(10) }
            val pause = helix.patches.single()
            assertEquals("rw-1", pause.query["id"])
            assertEquals(true, pause.body!!["is_paused"]?.jsonPrimitive?.boolean, "paused, so nobody redeems it unheard")
            assertNull(p.settings.settings.value.twitch.account)
        } finally {
            scope.cancel()
        }
    }
}
