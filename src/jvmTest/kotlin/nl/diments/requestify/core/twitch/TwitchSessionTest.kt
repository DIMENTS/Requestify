package nl.diments.requestify.core.twitch

import java.io.IOException
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubConnector
import nl.diments.requestify.core.twitch.eventsub.EventSubSocket
import nl.diments.requestify.core.twitch.eventsub.EventSubState

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class TwitchSessionTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-twitch-session") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private class FakeSocket : EventSubSocket {
        private val channel = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<String> get() = channel
        override suspend fun close() { channel.close() }
        fun push(text: String) = check(channel.trySend(text).isSuccess)
    }

    /** A connector whose connections get a welcome at once; while [down] every connect fails. */
    private class FakeConnector : EventSubConnector {
        var attempts = 0
        var down = false
        val sockets = mutableListOf<FakeSocket>()

        override suspend fun connect(url: String): EventSubSocket {
            attempts++
            if (down) throw IOException("Twitch cannot be reached")
            return FakeSocket().also {
                sockets += it
                it.push(welcome())
            }
        }
    }

    private inner class Harness(scope: TestScope, loggedIn: Boolean = true, account: TwitchAccount? = ACCOUNT) {
        val twitch = FakeTwitch(
            now = { scope.currentTime },
            dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )
        val secrets = InMemorySecretStore().apply { if (loggedIn) put(SecretKeys.TWITCH_REFRESH_TOKEN, "r0") }
        val auth = TwitchAuth(twitch.http, secrets, clock = { scope.currentTime })
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings-${UUID.randomUUID()}.json"), Settings.serializer()) { Settings() })
            .apply { update { it.copy(twitch = TwitchSettings(account = account)) } }
        val connector = FakeConnector()
        private lateinit var created: TwitchSession
        val api = TwitchApi(
            twitch.http,
            token = { force -> auth.token(force) },
            broadcasterId = { settings.settings.value.twitch.account?.userId },
        )
        val eventSub = EventSubClient(
            connector = connector,
            api = api,
            broadcasterId = { settings.settings.value.twitch.account?.userId },
            rewardId = { null },
            onAuthorizationRevoked = { created.onAuthorizationRevoked() },
            scope = scope.backgroundScope,
            clock = { scope.currentTime },
        )

        init {
            created = TwitchSession(auth, api, eventSub, settings, scope.backgroundScope, clock = { scope.currentTime })
        }

        val session: TwitchSession get() = created
    }

    @Test
    fun startsEventSubOnlyWhileLoggedIn() = runTest {
        val h = Harness(this)
        h.session.start()
        runCurrent()
        assertEquals(1, h.connector.attempts)
        assertEquals(EventSubState.Connected, h.eventSub.state.value)
        assertTrue(h.session.connected.value)

        h.auth.logout()
        runCurrent()
        assertEquals(EventSubState.Stopped, h.eventSub.state.value, "logged out")
        assertFalse(h.session.connected.value)
    }

    @Test
    fun aDeadLoginStopsEventSub() = runTest {
        val h = Harness(this)
        h.session.start()
        runCurrent()
        assertEquals(EventSubState.Connected, h.eventSub.state.value)

        h.auth.markLoginNeeded("Twitch refused the login")
        runCurrent()
        assertEquals(EventSubState.Stopped, h.eventSub.state.value)
        assertTrue(h.session.health.value.loginNeeded)
        assertFalse(h.session.connected.value)
    }

    @Test
    fun withoutAnAccountEventSubStaysStopped() = runTest {
        val noAccount = Harness(this, loggedIn = true, account = null)
        noAccount.session.start()
        runCurrent()
        assertEquals(0, noAccount.connector.attempts)
        assertEquals(EventSubState.Stopped, noAccount.eventSub.state.value)

        val loggedOut = Harness(this, loggedIn = false)
        loggedOut.session.start()
        runCurrent()
        assertEquals(0, loggedOut.connector.attempts)
    }

    @Test
    fun validatesHourly() = runTest {
        // No account: EventSub stays off, so the only traffic is the validations.
        val h = Harness(this, account = null)
        h.session.start()
        runCurrent()
        advanceTimeBy(60.minutes)
        runCurrent()
        advanceTimeBy(60.minutes)
        runCurrent()
        assertEquals(listOf(0L, 60.minutes.inWholeMilliseconds, 120.minutes.inWholeMilliseconds), h.twitch.validations)
    }

    @Test
    fun doesNotValidateWhileLoggedOut() = runTest {
        val h = Harness(this, loggedIn = false, account = null)
        h.session.start()
        runCurrent()
        advanceTimeBy(120.minutes)
        runCurrent()
        assertEquals(emptyList(), h.twitch.validations)
    }

    @Test
    fun validationKeepsTheBroadcasterTypeCurrent() = runTest {
        val h = Harness(this, account = ACCOUNT.copy(displayName = "diments", broadcasterType = ""))
        h.twitch.broadcasterType = "partner"
        h.session.start()
        runCurrent()
        assertEquals(TwitchAccount("42", "diments", "DIMENTS", "partner"), h.settings.settings.value.twitch.account)
    }

    @Test
    fun revokedAuthorizationMeansLoginNeeded() = runTest {
        val h = Harness(this)
        h.session.start()
        runCurrent()
        assertEquals(EventSubState.Connected, h.eventSub.state.value)

        h.connector.sockets.last().push(revocation("authorization_revoked"))
        runCurrent()
        assertIs<TwitchAuthStatus.LoginNeeded>(h.auth.status.value)
        assertTrue(h.session.health.value.loginNeeded)
        assertEquals(EventSubState.Stopped, h.eventSub.state.value)
    }

    @Test
    fun disconnectedShowsOnlyAfter30s() = runTest {
        val h = Harness(this)
        h.connector.down = true
        h.session.start()
        runCurrent()
        assertIs<EventSubState.Disconnected>(h.eventSub.state.value)
        assertFalse(h.session.health.value.disconnected, "a blip is not shown")

        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(h.session.health.value.disconnected)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(h.session.health.value.disconnected)

        h.connector.down = false
        advanceTimeBy(33.seconds) // the next retry connects
        runCurrent()
        assertEquals(EventSubState.Connected, h.eventSub.state.value)
        assertFalse(h.session.health.value.disconnected)
    }

    @Test
    fun aRemovedRewardShowsInTheHealthOnlyWhileChannelPointsAreUsed() = runTest {
        val h = Harness(this)
        assertFalse(h.session.health.value.rewardRemoved)
        fun points(enabled: Boolean, removed: Boolean) = h.settings.update { s ->
            s.copy(twitch = s.twitch.copy(channelPoints = s.twitch.channelPoints.copy(enabled = enabled, rewardRemoved = removed)))
        }
        points(enabled = true, removed = true)
        runCurrent()
        assertTrue(h.session.health.value.rewardRemoved)

        points(enabled = false, removed = true) // Channel Points switched off: nothing to fix
        runCurrent()
        assertFalse(h.session.health.value.rewardRemoved)

        points(enabled = true, removed = true)
        h.settings.update { s -> s.copy(twitch = s.twitch.copy(account = null)) } // logged out of Twitch
        runCurrent()
        assertFalse(h.session.health.value.rewardRemoved)
    }

    @Test
    fun anAccountWithoutAWorkingLoginNeedsALogin() = runTest {
        // The account is set up, but the saved login is gone (unreadable, or never saved): not "all connected".
        val h = Harness(this, loggedIn = false)
        assertTrue(h.session.health.value.loginNeeded)
        h.session.start()
        runCurrent()
        assertTrue(h.session.health.value.loginNeeded)

        val noAccount = Harness(this, loggedIn = false, account = null)
        assertFalse(noAccount.session.health.value.loginNeeded, "Twitch is not set up: nothing to log in to")
    }

    @Test
    fun stopStopsEventSub() = runTest {
        val h = Harness(this)
        h.session.start()
        runCurrent()
        h.session.stop()
        assertEquals(EventSubState.Stopped, h.eventSub.state.value)
    }

    private companion object {
        val ACCOUNT = TwitchAccount("42", "diments", "DIMENTS", "affiliate")
        const val TIMESTAMP = "2026-11-17T19:47:10.123456789Z"

        fun welcome() =
            """{"metadata":{"message_id":"${UUID.randomUUID()}","message_type":"session_welcome","message_timestamp":"$TIMESTAMP"},""" +
                """"payload":{"session":{"id":"S1","status":"connected","connected_at":"$TIMESTAMP",""" +
                """"keepalive_timeout_seconds":30,"reconnect_url":null}}}"""

        fun revocation(status: String) =
            """{"metadata":{"message_id":"${UUID.randomUUID()}","message_type":"revocation","message_timestamp":"$TIMESTAMP",""" +
                """"subscription_type":"channel.chat.message","subscription_version":"1"},""" +
                """"payload":{"subscription":{"id":"sub-1","status":"$status","type":"channel.chat.message","version":"1"}}}"""
    }
}
