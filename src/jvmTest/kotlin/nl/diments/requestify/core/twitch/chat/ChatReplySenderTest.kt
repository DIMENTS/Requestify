package nl.diments.requestify.core.twitch.chat

import nl.diments.requestify.core.settings.setForTest
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
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
import nl.diments.requestify.core.twitch.auth.DeviceLoginResult
import nl.diments.requestify.core.twitch.auth.TWITCH_BOT_SCOPES
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.bot.TwitchBot

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class ChatReplySenderTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-chat-sender") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun sendsInOrder() = runTest {
        val twitch = RecordingTwitchApi()
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("one", "m1")
        sender.send("two", null)
        sender.send("three", "m3")

        awaitUntil("three sends") { twitch.sends.size == 3 }
        assertEquals(
            listOf(
                RecordingTwitchApi.Sent("one", "m1"),
                RecordingTwitchApi.Sent("two", null),
                RecordingTwitchApi.Sent("three", "m3"),
            ),
            twitch.sends.toList(),
        )
        awaitUntil("ids remembered") { sender.isOwnMessage("own-3") }
        assertTrue(sender.isOwnMessage("own-1"))
        assertFalse(sender.isOwnMessage("own-4"))
    }

    @Test
    fun rateLimitedWaitsAndRetries() = runTest {
        val twitch = RecordingTwitchApi()
        twitch.sendStatuses.add(429) // no Ratelimit-Reset header: the client's default wait of 5 s
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("hello", "m1")

        awaitUntil("first attempt") { twitch.sends.size == 1 }
        settle() // the 429 is answered on a real thread; its wait starts once that answer is handled
        // Step virtual time (each step lets the real threads catch up) until the retry, and see how long it waited.
        var waited = 0L
        while (twitch.sends.size < 2 && waited < 10_000) {
            advanceTimeBy(250)
            waited += 250
            settle()
        }
        assertTrue(waited in 4_750..6_000, "the retry came after the client's default wait of 5 s, not after $waited ms")
        assertEquals(listOf("hello", "hello"), twitch.sends.map { it.message })
        awaitUntil("remembered") { sender.isOwnMessage("own-1") }
    }

    @Test
    fun droppedAfterThreeTries() = runTest {
        val twitch = RecordingTwitchApi()
        repeat(5) { twitch.sendStatuses.add(429) }
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("lost", null)
        sender.send("next", null)

        awaitUntil("first attempt") { twitch.sends.size == 1 }
        // The waits between the three tries start when Twitch answered, on the mock engine's own thread: virtual time
        // moves on in steps until all is sent, so a late answer cannot leave a wait that never ends.
        awaitUntil("three tries and the next reply") {
            advanceTimeBy(1_000)
            twitch.sends.size >= 4
        }
        assertEquals(listOf("lost", "lost", "lost", "next"), twitch.sends.take(4).map { it.message })
    }

    @Test
    fun stopDropsTheRepliesNotSentYet() = runTest {
        val twitch = RecordingTwitchApi()
        twitch.sendStatuses.add(429) // the first reply waits 5 s for its next try
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("first", "m1")
        sender.send("second", "m2")
        awaitUntil("first attempt") { twitch.sends.size == 1 }
        settle()

        sender.stop() // the session ended (a logout, or the login died)
        sender.start() // logged in again later
        settle() // a reply left over would go out now
        assertEquals(listOf("first"), twitch.sends.map { it.message }, "nothing of the old session is posted")

        sender.send("fresh", "m3")
        awaitUntil("a new reply") { twitch.sends.size == 2 }
        assertEquals("fresh", twitch.sends.last().message)
    }

    @Test
    fun stoppedSenderSendsNothingUntilStarted() = runTest {
        val twitch = RecordingTwitchApi()
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.send("early", null)
        settle()
        assertEquals(0, twitch.sends.size)
        sender.start()
        awaitUntil("early reply") { twitch.sends.size == 1 }
    }

    /** A bot "7" for the streamer "b1" (the recording APIs' broadcaster), logged in unless [loggedIn] is false. */
    private inner class BotParts(scope: kotlinx.coroutines.test.TestScope, loggedIn: Boolean = true, botAccount: TwitchAccount = BOT) {
        val streamer = RecordingTwitchApi()
        val botApi = RecordingTwitchApi()
        val fake = FakeTwitch().apply { pendingPolls = 0 }
        val secrets = InMemorySecretStore().apply { if (loggedIn) put(SecretKeys.TWITCH_BOT_REFRESH_TOKEN, "b0") }
        val auth = TwitchAuth(fake.http, secrets, clock = System::currentTimeMillis, secretKey = SecretKeys.TWITCH_BOT_REFRESH_TOKEN, scopes = TWITCH_BOT_SCOPES)
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings-${UUID.randomUUID()}.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
            .apply { setForTest { it.copy(twitch = TwitchSettings(account = STREAMER, bot = botAccount)) } }
        val bot = TwitchBot(auth, botApi.api, settings, scope.backgroundScope)
        val sender = ChatReplySender(streamer.api, scope.backgroundScope, bot = bot).also { it.start() }
    }

    @Test
    fun repliesGoOutAsTheBotWhenOneIsConnected() = runTest {
        val p = BotParts(this)
        p.sender.send("hi", "m1")
        awaitUntil("the bot's reply") { p.botApi.sends.size == 1 }
        settle()
        assertEquals(RecordingTwitchApi.Sent("hi", "m1", senderId = "7"), p.botApi.sends.single())
        assertEquals(0, p.streamer.sends.size)
        awaitUntil("remembered") { p.sender.isOwnMessage("own-1") }
    }

    @Test
    fun aForbiddenBotFallsBackToTheStreamerAtOnce() = runTest {
        val p = BotParts(this)
        p.botApi.sendStatuses.add(403)
        p.sender.send("hi", "m1")
        awaitUntil("the streamer's reply") { p.streamer.sends.size == 1 }
        assertEquals(RecordingTwitchApi.Sent("hi", "m1"), p.streamer.sends.single())
        awaitUntil("blocked") { p.bot.health.value.blockedReason != null }
    }

    @Test
    fun aMessageHeldByAutoModIsNotSentAgain() = runTest {
        val p = BotParts(this)
        p.botApi.drops.add(DropReason("automod_held", "Held for review"))
        p.sender.send("hi", "m1")
        awaitUntil("the bot's try") { p.botApi.sends.size == 1 }
        settle()
        assertEquals(0, p.streamer.sends.size, "AutoMod may still let it through: no second copy")
        assertEquals("Held for review", p.bot.drops.dropping.value)
    }

    @Test
    fun aFollowersOnlyDropIsSentAgainAsTheStreamer() = runTest {
        val p = BotParts(this)
        p.botApi.drops.add(DropReason("followers_only_mode", "Followers only"))
        p.sender.send("hi", "m1")
        awaitUntil("the streamer's reply") { p.streamer.sends.size == 1 }
        assertEquals(RecordingTwitchApi.Sent("hi", "m1"), p.streamer.sends.single())
        assertEquals("Followers only", p.bot.drops.dropping.value)
    }

    @Test
    fun aBotLoginThatDiesMidStreamFallsBackAndComesBack() = runTest {
        val p = BotParts(this)
        repeat(2) { p.botApi.sendStatuses.add(401) } // refused, and refused again after a refresh
        p.sender.send("one", "m1")
        awaitUntil("reply one as the streamer") { p.streamer.sends.size == 1 }
        awaitUntil("the bot needs a login") { p.bot.health.value.loginNeeded }

        p.sender.send("two", "m2")
        awaitUntil("reply two as the streamer") { p.streamer.sends.size == 2 }
        assertEquals(2, p.botApi.sends.size, "no bot try while its login is dead")

        // The streamer logs the bot in again: no restart.
        val code = assertNotNull(kotlinx.coroutines.runBlocking { p.auth.startDeviceLogin() })
        p.fake.userId = "7"
        assertIs<DeviceLoginResult.Success>(kotlinx.coroutines.runBlocking { p.auth.awaitDeviceLogin(code.copy(intervalMs = 1)) })
        awaitUntil("healthy again") { !p.bot.health.value.loginNeeded }

        p.sender.send("three", "m3")
        awaitUntil("reply three as the bot") { p.botApi.sends.size == 3 }
        assertEquals(RecordingTwitchApi.Sent("three", "m3", senderId = "7"), p.botApi.sends.last())
        assertEquals(2, p.streamer.sends.size)
    }

    @Test
    fun aBotThatIsTheStreamerIsNotUsed() = runTest {
        val p = BotParts(this, botAccount = STREAMER)
        p.sender.send("hi", null)
        awaitUntil("the streamer's reply") { p.streamer.sends.size == 1 }
        assertEquals(0, p.botApi.sends.size)
    }

    @Test
    fun theStreamersOwnDropIsRecorded() = runTest {
        val twitch = RecordingTwitchApi()
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        twitch.drops.add(DropReason("slow_mode", "Slow mode"))
        sender.send("one", null)
        awaitUntil("dropped") { sender.streamerDrops.dropping.value == "Slow mode" }

        sender.send("two", null)
        awaitUntil("sent again") { sender.streamerDrops.dropping.value == null }
        assertEquals(2, twitch.sends.size, "the streamer's own drop is not sent again")
    }

    private companion object {
        val STREAMER = TwitchAccount(RecordingTwitchApi.BROADCASTER_ID, "diments", "DIMENTS")
        val BOT = TwitchAccount("7", "mybot", "MyBot")
    }
}
