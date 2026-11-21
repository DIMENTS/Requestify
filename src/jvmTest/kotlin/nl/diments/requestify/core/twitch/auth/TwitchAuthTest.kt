package nl.diments.requestify.core.twitch.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore

class TwitchAuthTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun form(req: HttpRequestData): Parameters =
        rawBody(req).parseUrlEncodedParameters(plusIsSpace = true)

    private fun rawBody(req: HttpRequestData): String =
        (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun MockRequestHandleScope.tokens(access: String, refresh: String) = respond(
        """{"access_token":"$access","expires_in":14000,"refresh_token":"$refresh","scope":["user:read:chat"],"token_type":"bearer"}""",
        HttpStatusCode.OK,
        json,
    )

    private fun MockRequestHandleScope.pending() =
        respond("""{"status":400,"message":"authorization_pending"}""", HttpStatusCode.BadRequest, json)

    private fun MockRequestHandleScope.valid(login: String = "diments", userId: String = "42") = respond(
        """{"client_id":"cid","login":"$login","scopes":["user:read:chat"],"user_id":"$userId","expires_in":5000}""",
        HttpStatusCode.OK,
        json,
    )

    private fun MockRequestHandleScope.invalidToken() =
        respond("""{"status":401,"message":"invalid access token"}""", HttpStatusCode.Unauthorized, json)

    private fun MockRequestHandleScope.deadRefresh() = respond(
        """{"error":"Bad Request","status":400,"message":"Invalid refresh token"}""",
        HttpStatusCode.BadRequest,
        json,
    )

    private fun auth(
        secrets: SecretStore,
        clock: () -> Long = { 0L },
        requestTimeoutMs: Long = 15_000,
        dispatcher: CoroutineDispatcher? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = TwitchAuth(
        http = HttpClient(MockEngine(MockEngineConfig().apply { addHandler(handler); this.dispatcher = dispatcher })),
        secrets = secrets,
        clock = clock,
        requestTimeoutMs = requestTimeoutMs,
    )

    /** Virtual time: the mock answers on the test dispatcher, so waiting for it never advances the clock. */
    private fun virtual(
        secrets: SecretStore,
        scope: TestScope,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = auth(
        secrets,
        clock = { scope.currentTime },
        dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        handler = handler,
    )

    private fun secretsWith(refresh: String = "r1") =
        InMemorySecretStore().apply { put(SecretKeys.TWITCH_REFRESH_TOKEN, refresh) }

    @Test
    fun startDeviceLoginSendsClientIdAndScopes() = runBlocking {
        var sent: Parameters? = null
        var path = ""
        val a = auth(InMemorySecretStore(), clock = { 1_000L }) { req ->
            sent = form(req)
            path = req.url.encodedPath
            respond(
                """{"device_code":"dc","user_code":"ABCDEFGH","verification_uri":"https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH","expires_in":1800,"interval":5}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val code = assertNotNull(a.startDeviceLogin())
        assertEquals("/oauth2/device", path)
        assertEquals("wu2hg6lvxw5lqb2qbs046ou19nupnp", sent!!["client_id"])
        assertEquals(
            "user:read:chat user:write:chat channel:manage:redemptions bits:read moderator:read:followers",
            sent!!["scopes"],
        )
        assertEquals("dc", code.deviceCode)
        assertEquals("ABCDEFGH", code.userCode)
        assertEquals("https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH", code.verificationUri)
        assertEquals(1_000L + 1_800_000L, code.expiresAtEpochMs)
        assertEquals(5_000L, code.intervalMs)
    }

    @Test
    fun startDeviceLoginIsNullWhenTwitchCannotBeReached() = runBlocking {
        assertEquals(null, auth(InMemorySecretStore()) { throw IOException("offline") }.startDeviceLogin())
        assertEquals(null, auth(InMemorySecretStore()) { respond("", HttpStatusCode.InternalServerError) }.startDeviceLogin())
    }

    @Test
    fun pollingContinuesOnAuthorizationPendingThenSucceeds() = runTest {
        val secrets = InMemorySecretStore()
        var polls = 0
        val pollTimes = mutableListOf<Long>()
        var pollForm: Parameters? = null
        val a = virtual(secrets, this) { req ->
            when (req.url.encodedPath) {
                "/oauth2/token" -> {
                    pollTimes += currentTime
                    pollForm = form(req)
                    if (polls++ < 2) pending() else tokens("a1", "r1")
                }
                "/oauth2/validate" -> {
                    assertEquals("OAuth a1", req.headers[HttpHeaders.Authorization])
                    valid()
                }
                else -> error("unexpected ${req.url}")
            }
        }
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        val result = a.awaitDeviceLogin(code)
        assertEquals(DeviceLoginResult.Success("42", "diments"), result)
        assertEquals(listOf(5_000L, 10_000L, 15_000L), pollTimes)
        assertEquals("r1", secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals("dc", pollForm!!["device_code"])
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", pollForm!!["grant_type"])
        assertEquals("wu2hg6lvxw5lqb2qbs046ou19nupnp", pollForm!!["client_id"])
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
        assertEquals(TokenResult.Ok("a1"), a.token())
    }

    @Test
    fun expiredCodeEndsWithExpired() = runTest {
        val secrets = InMemorySecretStore()
        val a = virtual(secrets, this) { pending() }
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 12_000, intervalMs = 5_000)
        assertEquals(DeviceLoginResult.Expired, a.awaitDeviceLogin(code))
        assertEquals(null, secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun otherErrorEndsWithFailed() = runTest {
        var polls = 0
        val a = virtual(InMemorySecretStore(), this) {
            polls++
            respond("""{"status":400,"message":"invalid device code"}""", HttpStatusCode.BadRequest, json)
        }
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertIs<DeviceLoginResult.Failed>(a.awaitDeviceLogin(code))
        assertEquals(1, polls)
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun refreshPersistsRotatedTokenBeforeReturning() = runBlocking {
        val stored = secretsWith("r+1/=")
        val events = mutableListOf<String>()
        val secrets = object : SecretStore by stored {
            override fun put(key: String, value: String) {
                events += "put $value"
                stored.put(key, value)
            }
        }
        var body = ""
        var sent: Parameters? = null
        val a = auth(secrets) { req ->
            body = rawBody(req)
            sent = form(req)
            events += "respond"
            tokens("a2", "r2")
        }
        assertEquals(TokenResult.Ok("a2"), a.token())
        assertEquals(listOf("respond", "put r2"), events)
        assertEquals("r2", stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals("refresh_token", sent!!["grant_type"])
        assertEquals("wu2hg6lvxw5lqb2qbs046ou19nupnp", sent!!["client_id"])
        assertEquals("r+1/=", sent!!["refresh_token"])
        assertFalse("r+1/=" in body, "the refresh token is URL-encoded")
        assertFalse("client_secret" in body)
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun invalidRefreshTokenMeansLoginNeeded() = runBlocking {
        for (dead in listOf<MockRequestHandleScope.() -> HttpResponseData>(
            { deadRefresh() },
            { respond("", HttpStatusCode.Unauthorized) },
        )) {
            val secrets = secretsWith()
            var calls = 0
            val a = auth(secrets) { calls++; dead() }
            assertEquals(TokenResult.LoginNeeded, a.token())
            assertEquals(null, secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
            assertIs<TwitchAuthStatus.LoginNeeded>(a.status.value)
            assertEquals(TokenResult.LoginNeeded, a.token(forceRefresh = true))
            assertEquals(1, calls, "a dead login is not sent to Twitch again")
        }
    }

    @Test
    fun networkErrorDuringRefreshKeepsLogin() = runBlocking {
        val secrets = secretsWith()
        val a = auth(secrets) { throw IOException("offline") }
        assertEquals(TokenResult.Unavailable(), a.token())
        assertEquals("r1", secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun serverTroubleDuringRefreshKeepsLogin() = runBlocking {
        for (code in listOf(408, 429, 500, 503)) {
            val secrets = secretsWith()
            val a = auth(secrets) { respond("", HttpStatusCode.fromValue(code)) }
            assertEquals(TokenResult.Unavailable(), a.token(), "HTTP $code")
            assertEquals("r1", secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN), "HTTP $code")
            assertEquals(TwitchAuthStatus.LoggedIn, a.status.value, "HTTP $code")
        }
    }

    @Test
    fun neverLoggedInIsLoggedOutWithoutRequest() = runBlocking {
        var calls = 0
        val a = auth(InMemorySecretStore()) { calls++; tokens("a1", "r1") }
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
        assertEquals(TokenResult.LoggedOut, a.token())
        assertEquals(0, calls)
    }

    @Test
    fun concurrentTokenCallsRefreshOnce() = runBlocking {
        var calls = 0
        val a = auth(secretsWith()) { delay(100); calls++; tokens("a1", "r2") }
        val results = withContext(Dispatchers.Default) { (1..5).map { async { a.token() } }.awaitAll() }
        assertEquals(1, calls)
        assertTrue(results.all { it == TokenResult.Ok("a1") })
    }

    @Test
    fun validateRefreshesOnceOn401() = runBlocking {
        val secrets = secretsWith()
        val validations = mutableListOf<String?>()
        var refreshes = 0
        val a = auth(secrets) { req ->
            when (req.url.encodedPath) {
                "/oauth2/token" -> tokens("a${++refreshes}", "r${refreshes + 1}")
                "/oauth2/validate" -> {
                    validations += req.headers[HttpHeaders.Authorization]
                    if (validations.size == 1) invalidToken() else valid()
                }
                else -> error("unexpected ${req.url}")
            }
        }
        assertEquals(TokenResult.Ok("a2"), a.validate())
        assertEquals<List<String?>>(listOf("OAuth a1", "OAuth a2"), validations)
        assertEquals(2, refreshes)
        assertEquals("r3", secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun validateThatIsStillRefusedAfterARefreshNeedsLogin() = runBlocking {
        var validations = 0
        val a = auth(secretsWith()) { req ->
            when (req.url.encodedPath) {
                "/oauth2/token" -> tokens("a", "r")
                else -> { validations++; invalidToken() }
            }
        }
        assertEquals(TokenResult.LoginNeeded, a.validate())
        assertEquals(2, validations, "one refresh and one retry, no more")
        assertTrue(a.status.value is TwitchAuthStatus.LoginNeeded)
    }

    @Test
    fun validateWithoutNetworkIsUnavailableAndKeepsLogin() = runBlocking {
        val secrets = secretsWith()
        val a = auth(secrets) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r2") else throw IOException("offline")
        }
        assertEquals(TokenResult.Unavailable(), a.validate())
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
        assertEquals("r2", secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun markLoginNeededForgetsTheLogin() = runBlocking {
        val secrets = secretsWith()
        val a = auth(secrets) { tokens("a1", "r2") }
        assertEquals(TokenResult.Ok("a1"), a.token())
        a.markLoginNeeded("revoked")
        assertEquals(TwitchAuthStatus.LoginNeeded("revoked"), a.status.value)
        assertEquals(null, secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TokenResult.LoginNeeded, a.token())
    }

    @Test
    fun aSavedLoginThatCannotBeReadForAMomentIsNoLogout() = runBlocking {
        val stored = secretsWith()
        var unreadable = false
        // Like the DPAPI store while another program holds the file, or it cannot be decrypted: get() finds nothing.
        val secrets = object : SecretStore by stored {
            override fun get(key: String): String? = if (unreadable) null else stored.get(key)
        }
        val a = auth(secrets) { tokens("a1", "r2") }
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)

        unreadable = true
        assertEquals(TokenResult.Unavailable(), a.token(), "a read failure: try again later")
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value, "the login stays")

        unreadable = false
        assertEquals(TokenResult.Ok("a1"), a.token())
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun logoutRemovesSecretFirst() = runBlocking {
        val stored = secretsWith()
        lateinit var a: TwitchAuth
        var statusWhenRemoved: TwitchAuthStatus? = null
        val secrets = object : SecretStore by stored {
            override fun remove(key: String) {
                statusWhenRemoved = a.status.value
                stored.remove(key)
            }
        }
        a = auth(secrets) { tokens("a1", "r2") }
        a.token()
        a.logout()
        assertEquals(TwitchAuthStatus.LoggedIn, statusWhenRemoved, "the secret goes before the state changes")
        assertEquals(null, stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
        assertEquals(TokenResult.LoggedOut, a.token())
    }

    @Test
    fun logoutThatCannotRemoveTheSecretChangesNothing() = runBlocking {
        val stored = secretsWith()
        val secrets = object : SecretStore by stored {
            override fun remove(key: String): Unit = throw IOException("secrets file in use")
        }
        val a = auth(secrets) { tokens("a1", "r2") }
        a.token()
        assertFailsWith<IOException> { a.logout() }
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
        assertEquals("r2", stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    /** A store whose put and remove fail while the matching flag is set, like a secrets file held by another program. */
    private class FlakyStore(private val inner: SecretStore) : SecretStore by inner {
        @Volatile var failPut = false
        @Volatile var failRemove = false

        override fun put(key: String, value: String) {
            if (failPut) throw IOException("secrets file in use")
            inner.put(key, value)
        }

        override fun remove(key: String) {
            if (failRemove) throw IOException("secrets file in use")
            inner.remove(key)
        }
    }

    private val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = Long.MAX_VALUE / 2, intervalMs = 1)

    @Test
    fun refreshThatCannotSaveTheRotatedTokenKeepsTheSessionAndUsesIt() = runBlocking {
        val stored = secretsWith("r1")
        val secrets = FlakyStore(stored).apply { failPut = true }
        val sent = mutableListOf<String?>()
        var n = 0
        val a = auth(secrets) { req ->
            sent += form(req)["refresh_token"]
            n++
            tokens("a$n", "r${n + 1}")
        }
        assertEquals(TokenResult.Ok("a1"), a.token())
        assertEquals("r1", stored.get(SecretKeys.TWITCH_REFRESH_TOKEN), "the stale token is all the store has")
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
        secrets.failPut = false
        assertEquals(TokenResult.Ok("a2"), a.token(forceRefresh = true))
        assertEquals(listOf<String?>("r1", "r2"), sent, "the rotated token is used, not the stale stored one")
        assertEquals("r3", stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun unsavedRotatedTokenIsSavedAgainEvenWhenTheNextRefreshFails() = runBlocking {
        val stored = secretsWith("r1")
        val secrets = FlakyStore(stored).apply { failPut = true }
        var n = 0
        val a = auth(secrets) { if (n++ == 0) tokens("a1", "r2") else respond("", HttpStatusCode.ServiceUnavailable) }
        assertEquals(TokenResult.Ok("a1"), a.token())
        secrets.failPut = false
        assertEquals(TokenResult.Unavailable(), a.token(forceRefresh = true))
        assertEquals("r2", stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun loginThatCannotBeSavedFailsAndChangesNothing() = runTest {
        val secrets = FlakyStore(InMemorySecretStore()).apply { failPut = true }
        var validations = 0
        val a = virtual(secrets, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r1") else { validations++; valid() }
        }
        assertIs<DeviceLoginResult.Failed>(a.awaitDeviceLogin(code))
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
        // The account is looked up before anything is saved.
        assertEquals(1, validations)
        assertEquals(TokenResult.LoggedOut, a.token())
    }

    @Test
    fun loginWhoseAccountCannotBeReadIsRolledBack() = runTest {
        val stored = InMemorySecretStore()
        val a = virtual(stored, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r1") else respond("", HttpStatusCode.InternalServerError)
        }
        assertIs<DeviceLoginResult.Failed>(a.awaitDeviceLogin(code))
        assertEquals(null, stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
        assertEquals(TokenResult.LoggedOut, a.token())
    }

    @Test
    fun anAccountThatCannotBeReadNeedsNoRollback() = runTest {
        val secrets = FlakyStore(InMemorySecretStore()).apply { failRemove = true }
        val a = virtual(secrets, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r1") else respond("", HttpStatusCode.InternalServerError)
        }
        assertIs<DeviceLoginResult.Failed>(a.awaitDeviceLogin(code))
        // Nothing was saved before the account was looked up, so there is nothing to remove.
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun rollbackLeavesANewerStateAlone() = runBlocking {
        val stored = InMemorySecretStore()
        val validating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = auth(stored) { req ->
            if (req.url.encodedPath == "/oauth2/token") {
                tokens("a1", "r1")
            } else {
                validating.complete(Unit)
                release.await()
                respond("", HttpStatusCode.InternalServerError)
            }
        }
        val login = async(Dispatchers.Default) { a.awaitDeviceLogin(code) }
        validating.await()
        a.markLoginNeeded("revoked meanwhile")
        release.complete(Unit)
        assertIs<DeviceLoginResult.Failed>(login.await())
        assertEquals(TwitchAuthStatus.LoginNeeded("revoked meanwhile"), a.status.value)
    }

    @Test
    fun refreshFinishingAfterLogoutIsDropped() = runBlocking {
        val stored = secretsWith()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = auth(stored) { entered.complete(Unit); release.await(); tokens("a1", "r2") }
        val pending = async(Dispatchers.Default) { a.token() }
        entered.await()
        a.logout()
        release.complete(Unit)
        assertEquals(TokenResult.LoggedOut, pending.await())
        assertEquals(null, stored.get(SecretKeys.TWITCH_REFRESH_TOKEN), "the stale answer is not saved")
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun refreshFinishingAfterMarkLoginNeededIsDropped() = runBlocking {
        val stored = secretsWith()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = auth(stored) { entered.complete(Unit); release.await(); tokens("a1", "r2") }
        val pending = async(Dispatchers.Default) { a.token() }
        entered.await()
        a.markLoginNeeded("revoked")
        release.complete(Unit)
        assertEquals(TokenResult.LoginNeeded, pending.await())
        assertEquals(null, stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoginNeeded("revoked"), a.status.value)
    }

    @Test
    fun pollingSurvivesANetworkError() = runTest {
        var polls = 0
        val a = virtual(InMemorySecretStore(), this) { req ->
            if (req.url.encodedPath == "/oauth2/token") {
                if (polls++ == 0) throw IOException("offline") else tokens("a1", "r1")
            } else {
                valid()
            }
        }
        val c = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertEquals(DeviceLoginResult.Success("42", "diments"), a.awaitDeviceLogin(c))
        assertEquals(2, polls)
    }

    @Test
    fun slowDownAddsFiveSecondsToTheInterval() = runTest {
        val pollTimes = mutableListOf<Long>()
        val a = virtual(InMemorySecretStore(), this) { req ->
            if (req.url.encodedPath == "/oauth2/token") {
                pollTimes += currentTime
                if (pollTimes.size == 1) {
                    respond("""{"status":400,"message":"slow_down"}""", HttpStatusCode.BadRequest, json)
                } else {
                    tokens("a1", "r1")
                }
            } else {
                valid()
            }
        }
        val c = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertIs<DeviceLoginResult.Success>(a.awaitDeviceLogin(c))
        assertEquals(listOf(5_000L, 15_000L), pollTimes)
    }

    @Test
    fun loginCancelledWhilePollingIsFailedAndNotSaved() = runTest {
        val stored = InMemorySecretStore()
        lateinit var a: TwitchAuth
        a = virtual(stored, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") {
                a.markLoginNeeded("revoked")
                tokens("a1", "r1")
            } else {
                valid()
            }
        }
        val c = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertEquals(DeviceLoginResult.Failed("cancelled"), a.awaitDeviceLogin(c))
        assertEquals(null, stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoginNeeded("revoked"), a.status.value)
    }

    private fun botAuth(secrets: SecretStore, scope: TestScope, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        TwitchAuth(
            http = HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        addHandler(handler)
                        dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                    },
                ),
            ),
            secrets = secrets,
            clock = { scope.currentTime },
            secretKey = SecretKeys.TWITCH_BOT_REFRESH_TOKEN,
            scopes = TWITCH_BOT_SCOPES,
        )

    @Test
    fun aBotLoginUsesItsOwnSecretAndScopes() = runTest {
        val secrets = InMemorySecretStore()
        var deviceForm: Parameters? = null
        val a = botAuth(secrets, this) { req ->
            when (req.url.encodedPath) {
                "/oauth2/device" -> {
                    deviceForm = form(req)
                    respond(
                        """{"device_code":"dc","user_code":"ABCDEFGH","verification_uri":"https://x","expires_in":1800,"interval":5}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
                "/oauth2/token" -> tokens("a1", "r1")
                else -> valid(login = "mybot", userId = "7")
            }
        }
        val code = assertNotNull(a.startDeviceLogin())
        assertEquals("user:write:chat user:read:moderated_channels", deviceForm!!["scopes"])
        assertEquals(DeviceLoginResult.Success("7", "mybot"), a.awaitDeviceLogin(code))
        assertEquals("r1", secrets.get(SecretKeys.TWITCH_BOT_REFRESH_TOKEN))
        assertEquals(null, secrets.get(SecretKeys.TWITCH_REFRESH_TOKEN))
    }

    @Test
    fun aRefusedAccountIsNotSaved() = runTest {
        val secrets = InMemorySecretStore()
        val a = botAuth(secrets, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r1") else valid()
        }
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertEquals(DeviceLoginResult.WrongAccount("42", "diments"), a.awaitDeviceLogin(code, accept = { it != "42" }))
        assertEquals(null, secrets.get(SecretKeys.TWITCH_BOT_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
        assertEquals(TokenResult.LoggedOut, a.token())
    }

    @Test
    fun aRefusedAccountLeavesAnEarlierLoginAlone() = runTest {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.TWITCH_BOT_REFRESH_TOKEN, "r0") }
        val a = botAuth(secrets, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r1") else valid()
        }
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertIs<DeviceLoginResult.WrongAccount>(a.awaitDeviceLogin(code, accept = { false }))
        assertEquals("r0", secrets.get(SecretKeys.TWITCH_BOT_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun anAccountThatCannotBeReadSavesNothing() = runTest {
        var puts = 0
        val stored = InMemorySecretStore()
        val secrets = object : SecretStore by stored {
            override fun put(key: String, value: String) {
                puts++
                stored.put(key, value)
            }
        }
        val a = virtual(secrets, this) { req ->
            if (req.url.encodedPath == "/oauth2/token") tokens("a1", "r1") else respond("", HttpStatusCode.InternalServerError)
        }
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertIs<DeviceLoginResult.Failed>(a.awaitDeviceLogin(code))
        assertEquals(0, puts)
        assertEquals(null, stored.get(SecretKeys.TWITCH_REFRESH_TOKEN))
        assertEquals(TwitchAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun aRefusedAccountIsRevokedAtTwitch() = runTest {
        var revoked: Parameters? = null
        val a = botAuth(InMemorySecretStore(), this) { req ->
            when (req.url.encodedPath) {
                "/oauth2/token" -> tokens("a1", "r1")
                "/oauth2/revoke" -> {
                    revoked = form(req)
                    respond("", HttpStatusCode.OK)
                }
                else -> valid()
            }
        }
        val code = DeviceCode("dc", "ABCDEFGH", "https://x", expiresAtEpochMs = 1_800_000, intervalMs = 5_000)
        assertIs<DeviceLoginResult.WrongAccount>(a.awaitDeviceLogin(code, accept = { false }))
        assertEquals("a1", revoked?.get("token"), "the streamer's grant to the bot's login is withdrawn")
        assertEquals("wu2hg6lvxw5lqb2qbs046ou19nupnp", revoked?.get("client_id"))
    }
}
