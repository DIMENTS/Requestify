package nl.diments.requestify.ui.setup

import nl.diments.requestify.core.settings.setForTest
import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.twitch.FakeTwitch
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.auth.TWITCH_BOT_SCOPES
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.bot.TwitchBot

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class TwitchBotSetupViewModelTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-bot-setup") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private inner class Parts(scope: TestScope, botAccount: TwitchAccount? = null) {
        val twitch = FakeTwitch(
            now = { scope.currentTime },
            dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        val secrets = InMemorySecretStore().apply {
            put(SecretKeys.TWITCH_REFRESH_TOKEN, "r0") // the streamer's own login
            if (botAccount != null) put(SecretKeys.TWITCH_BOT_REFRESH_TOKEN, "b0")
        }
        val auth = TwitchAuth(twitch.http, secrets, clock = { scope.currentTime }, secretKey = SecretKeys.TWITCH_BOT_REFRESH_TOKEN, scopes = TWITCH_BOT_SCOPES)
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
            .apply { setForTest { it.copy(twitch = TwitchSettings(account = STREAMER, bot = botAccount)) } }
        val api = TwitchApi(twitch.http, token = { force -> auth.token(force) }, broadcasterId = { settings.settings.value.twitch.account?.userId })
        val bot = TwitchBot(auth, api, settings, scope.backgroundScope)
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val vm = TwitchBotSetupViewModel(bot, settings, scope.backgroundScope, openBrowser = { opened += it }, copyToClipboard = { copied += it })
    }

    private fun FakeTwitch.isTheBot() {
        userId = "7"
        login = "mybot"
        displayName = "MyBot"
    }

    @Test
    fun connectingSavesTheBotAccount() = runTest {
        val p = Parts(this)
        p.twitch.isTheBot()
        p.twitch.moderatedChannels += "42"
        p.vm.onConnect()
        runCurrent()
        assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value)
        advanceTimeBy(30_000) // two polls of 5 s each, then the account
        runCurrent()

        assertEquals(TwitchLoginState.Idle, p.vm.login.value)
        assertEquals(TwitchAccount("7", "mybot", "MyBot"), p.settings.settings.value.twitch.bot)
        assertEquals("r1", p.secrets.get(SecretKeys.TWITCH_BOT_REFRESH_TOKEN))
        assertEquals("r0", p.secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(true, p.vm.moderator.value, "looked at right after connecting")
    }

    @Test
    fun ownAccountIsRefusedAndTheStreamersLoginIsUntouched() = runTest {
        val p = Parts(this) // the browser is logged in as the streamer: Twitch answers with user 42
        p.vm.onConnect()
        advanceTimeBy(30_000) // two polls of 5 s each, then the account
        runCurrent()

        assertEquals(TwitchLoginState.OwnAccount, p.vm.login.value)
        assertEquals(null, p.settings.settings.value.twitch.bot)
        assertEquals(STREAMER, p.settings.settings.value.twitch.account)
        assertEquals(null, p.secrets.get(SecretKeys.TWITCH_BOT_REFRESH_TOKEN))
        assertEquals("r0", p.secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun disconnectForgetsTheBot() = runTest {
        val p = Parts(this, botAccount = BOT)
        p.vm.onDisconnect()
        runCurrent()
        assertEquals(null, p.settings.settings.value.twitch.bot)
        assertEquals(null, p.secrets.get(SecretKeys.TWITCH_BOT_REFRESH_TOKEN))
        assertEquals("r0", p.secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun copyLinkAndModCommand() = runTest {
        val p = Parts(this, botAccount = BOT)
        p.vm.onConnect()
        runCurrent()
        val code = assertIs<TwitchLoginState.ShowingCode>(p.vm.login.value).code
        p.vm.onCopyLink()
        p.vm.onCopyModCommand()
        assertEquals(listOf(code.verificationUri, "/mod mybot"), p.copied)
        p.vm.onOpenBrowser()
        assertEquals(listOf(code.verificationUri), p.opened)
        p.vm.onCancel()
        assertEquals(TwitchLoginState.Idle, p.vm.login.value)
    }

    private companion object {
        val STREAMER = TwitchAccount("42", "diments", "DIMENTS", "affiliate")
        val BOT = TwitchAccount("7", "mybot", "MyBot")
    }
}
