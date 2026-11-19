package nl.diments.requestify.core.twitch.bot

import nl.diments.requestify.core.settings.setForTest
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.twitch.DropReason
import nl.diments.requestify.core.twitch.FakeTwitch
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.auth.TWITCH_BOT_SCOPES
import nl.diments.requestify.core.twitch.auth.TwitchAuth

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class TwitchBotTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-twitch-bot") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private inner class Harness(
        scope: TestScope,
        botLoggedIn: Boolean = true,
        account: TwitchAccount? = STREAMER,
        bot: TwitchAccount? = BOT,
    ) {
        val twitch = FakeTwitch(
            now = { scope.currentTime },
            dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        val secrets = InMemorySecretStore().apply { if (botLoggedIn) put(SecretKeys.TWITCH_BOT_REFRESH_TOKEN, "b0") }
        val auth = TwitchAuth(
            twitch.http,
            secrets,
            clock = { scope.currentTime },
            secretKey = SecretKeys.TWITCH_BOT_REFRESH_TOKEN,
            scopes = TWITCH_BOT_SCOPES,
        )
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings-${UUID.randomUUID()}.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
            .apply { setForTest { it.copy(twitch = TwitchSettings(account = account, bot = bot)) } }
        val api = TwitchApi(
            twitch.http,
            token = { force -> auth.token(force) },
            broadcasterId = { settings.settings.value.twitch.account?.userId },
        )
        val bot = TwitchBot(auth, api, settings, scope.backgroundScope)
    }

    private val followersOnly = DropReason("followers_only_mode", "Followers only")

    @Test
    fun noSenderWithoutABot() = runTest {
        val h = Harness(this, bot = null)
        assertEquals(null, h.bot.sender())
        assertEquals(TwitchBotHealth(), h.bot.health.value)
    }

    @Test
    fun theBotIsTheSenderWhileLoggedIn() = runTest {
        val h = Harness(this)
        assertEquals(BOT, h.bot.sender())
    }

    @Test
    fun aBotThatIsTheStreamerIsNeverTheSender() = runTest {
        val h = Harness(this, bot = STREAMER)
        assertEquals(null, h.bot.sender())
    }

    @Test
    fun aBotWithoutAStreamerIsNotUsed() = runTest {
        val h = Harness(this, account = null)
        assertEquals(null, h.bot.sender())
        assertEquals(TwitchBotHealth(), h.bot.health.value, "nothing to warn about while the streamer is logged out")
    }

    @Test
    fun aRefusedBotLoginNeedsALogin() = runTest {
        val h = Harness(this)
        h.bot.onLoginRefused()
        runCurrent()
        assertTrue(h.bot.health.value.loginNeeded)
        assertEquals(null, h.bot.sender())
    }

    @Test
    fun threeDropsInARowBlockTheBot() = runTest {
        val h = Harness(this)
        repeat(2) { h.bot.onDropped(followersOnly) }
        runCurrent()
        assertEquals(null, h.bot.health.value.blockedReason, "two drops are no block yet")

        h.bot.onDropped(followersOnly)
        runCurrent()
        assertEquals("Followers only", h.bot.health.value.blockedReason)

        h.bot.onSent()
        runCurrent()
        assertEquals(null, h.bot.health.value.blockedReason)
    }

    @Test
    fun heldByAutoModNeverBlocks() = runTest {
        val h = Harness(this)
        repeat(5) { h.bot.onDropped(DropReason("automod_held", "")) }
        runCurrent()
        assertEquals(null, h.bot.health.value.blockedReason)
    }

    @Test
    fun aForbiddenBotIsBlockedAtOnce() = runTest {
        val h = Harness(this)
        h.bot.onForbidden("banned")
        runCurrent()
        assertEquals("banned", h.bot.health.value.blockedReason)
        assertEquals("banned", h.bot.drops.dropping.value, "the Log shows it too")

        h.bot.onSent()
        runCurrent()
        assertEquals(null, h.bot.health.value.blockedReason)
    }

    @Test
    fun theModeratorCheckReadsTwitch() = runTest {
        val h = Harness(this)
        h.bot.checkModerator()
        assertEquals(false, h.bot.moderator.value)

        h.twitch.moderatedChannels += "42"
        h.bot.checkModerator()
        assertEquals(true, h.bot.moderator.value)
    }

    @Test
    fun resetForgetsWhatWasSeen() = runTest {
        val h = Harness(this)
        h.twitch.moderatedChannels += "42"
        h.bot.checkModerator()
        h.bot.onForbidden("banned")
        h.bot.reset()
        runCurrent()
        assertEquals(null, h.bot.moderator.value)
        assertEquals(null, h.bot.drops.dropping.value)
        assertEquals(TwitchBotHealth(), h.bot.health.value)
    }

    @Test
    fun theBotIsValidatedEveryHour() = runTest {
        val h = Harness(this)
        h.twitch.moderatedChannels += "42"
        h.bot.start()
        runCurrent()
        assertEquals(listOf(0L), h.twitch.validations)
        assertEquals(true, h.bot.moderator.value, "a good validation also looks at the moderator status")

        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(listOf(0L, 3_600_000L), h.twitch.validations)
        h.bot.stop()
    }

    @Test
    fun aBotThatIsNotLoggedInIsNotValidated() = runTest {
        val h = Harness(this, botLoggedIn = false)
        h.bot.start()
        runCurrent()
        assertEquals(emptyList(), h.twitch.validations)
        h.bot.stop()
    }

    private companion object {
        val STREAMER = TwitchAccount("42", "diments", "DIMENTS")
        val BOT = TwitchAccount("7", "mybot", "MyBot")
    }

    @Test
    fun theBlockedReasonIsNeverAnAutoModHold() = runTest {
        val h = Harness(this)
        repeat(3) { h.bot.onDropped(followersOnly) }
        h.bot.onDropped(DropReason("automod_held", "Held for review"))
        runCurrent()
        assertEquals("Followers only", h.bot.health.value.blockedReason)
    }
}
