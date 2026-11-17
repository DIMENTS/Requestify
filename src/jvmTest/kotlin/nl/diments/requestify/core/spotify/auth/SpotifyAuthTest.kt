package nl.diments.requestify.core.spotify.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.spotify.TokenResult

class SpotifyAuthTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun form(req: HttpRequestData): Parameters =
        (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()

    private fun MockRequestHandleScope.ok(access: String, refresh: String? = null, expiresIn: Int = 3600) =
        respond(
            buildString {
                append("{\"access_token\":\"$access\",\"token_type\":\"Bearer\",\"expires_in\":$expiresIn")
                if (refresh != null) append(",\"refresh_token\":\"$refresh\"")
                append("}")
            },
            HttpStatusCode.OK,
            json,
        )

    private fun auth(
        secrets: SecretStore,
        clock: () -> Long = { 0L },
        openBrowser: (String) -> Unit = {},
        receiverFactory: () -> LoopbackReceiver = { LoopbackReceiver(0) },
        requestTimeoutMs: Long = 15_000,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SpotifyAuth(
        http = HttpClient(MockEngine(handler)),
        secrets = secrets,
        clientId = { "cid" },
        clock = clock,
        openBrowser = openBrowser,
        receiverFactory = receiverFactory,
        requestTimeoutMs = requestTimeoutMs,
    )

    @Test
    fun authorizeUrlHasExactParams() {
        val url = auth(InMemorySecretStore()) { error("no http") }.authorizeUrl("CHAL", "ST")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue("client_id=cid" in url)
        assertTrue("response_type=code" in url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A43821%2Fcallback" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("code_challenge=CHAL" in url)
        assertTrue("state=ST" in url)
        assertEquals(
            "user-read-playback-state user-modify-playback-state user-read-currently-playing user-read-private " +
                "user-read-recently-played",
            Url(url).parameters["scope"],
        )
    }

    @Test
    fun authorizeUrlIncludesRecentlyPlayedScope() {
        val url = auth(InMemorySecretStore()) { error("no http") }.authorizeUrl("CHAL", "ST")
        assertTrue("user-read-recently-played" in checkNotNull(Url(url).parameters["scope"]).split(" "))
    }

    @Test
    fun loginExchangesCodeAndStoresRefreshToken() = runBlocking {
        val secrets = InMemorySecretStore()
        var tokenForm: Parameters? = null
        var receiver: LoopbackReceiver? = null
        val auth = auth(
            secrets,
            openBrowser = { url ->
                val state = Url(url).parameters["state"]!!
                val port = receiver!!.boundPort
                Thread {
                    java.net.http.HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/callback?code=c1&state=$state")).build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                }.start()
            },
            receiverFactory = { LoopbackReceiver(0).also { receiver = it } },
        ) { req ->
            assertEquals("/api/token", req.url.encodedPath)
            tokenForm = form(req)
            ok("a1", "r1")
        }
        assertEquals(LoginResult.Success, auth.login())
        val f = tokenForm!!
        assertEquals("authorization_code", f["grant_type"])
        assertEquals("c1", f["code"])
        assertEquals("cid", f["client_id"])
        assertEquals(SPOTIFY_REDIRECT_URI, f["redirect_uri"])
        assertEquals(64, f["code_verifier"]!!.length)
        assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedIn, auth.status.value)
        assertEquals("a1", auth.accessToken()?.value)
    }

    @Test
    fun loginWithoutClientIdFails() = runBlocking {
        val a = SpotifyAuth(
            HttpClient(MockEngine { error("no http") }), InMemorySecretStore(), { " " }, { 0L }, {}, { LoopbackReceiver(0) },
        )
        assertEquals(LoginResult.Failed("no client id"), a.login())
    }

    @Test
    fun receiverThatCannotLoadIsAFailedLoginNotAHang() = runBlocking {
        // A packaged runtime without jdk.httpserver throws an Error, not an Exception, when the receiver is made.
        val a = auth(InMemorySecretStore(), receiverFactory = { throw NoClassDefFoundError("com/sun/net/httpserver/HttpServer") }) {
            error("no http")
        }
        assertTrue(a.login() is LoginResult.Failed)
        assertEquals(SpotifyAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun refreshPersistsRotatedTokenBeforeReturning() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        var now = 0L
        var call = 0
        val a = auth(secrets, clock = { now }) { req ->
            call++
            if (call == 1) {
                assertEquals("refresh_token", form(req)["grant_type"])
                assertEquals("r1", form(req)["refresh_token"])
                assertEquals("cid", form(req)["client_id"])
                ok("a1", "r2", expiresIn = 3600)
            } else {
                assertEquals("r2", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
                assertEquals("r2", form(req)["refresh_token"])
                ok("a2", "r3")
            }
        }
        val t1 = a.accessToken()
        assertEquals("a1", t1?.value)
        assertEquals(3_600_000L, t1?.expiresAtEpochMs)
        assertEquals("r2", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals("a1", a.accessToken()?.value)
        assertEquals(1, call)
        now = 3_600_000L - 59_000L
        assertEquals("a2", a.accessToken()?.value)
        assertEquals("r3", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    @Test
    fun refreshWithoutNewRefreshTokenKeepsOld() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { ok("a1") }
        assertEquals("a1", a.accessToken()?.value)
        assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    @Test
    fun invalidGrantLogsOut() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { respond("{\"error\":\"invalid_grant\"}", HttpStatusCode.BadRequest, json) }
        assertNull(a.accessToken())
        assertNull(secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertTrue(a.status.value is SpotifyAuthStatus.LoginNeeded)
    }

    @Test
    fun networkErrorDuringRefreshKeepsLogin() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { throw IOException("offline") }
        assertNull(a.accessToken())
        assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun serverErrorKeepsLogin() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { respond("", HttpStatusCode.ServiceUnavailable) }
        assertNull(a.accessToken())
        assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun neverLoggedInIsLoggedOutWithoutRequest() = runBlocking {
        var calls = 0
        val a = auth(InMemorySecretStore()) { calls++; ok("a1") }
        assertEquals(TokenResult.LoggedOut, a.token())
        assertEquals(0, calls)
        assertEquals(SpotifyAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun tokenIsOkAfterARefresh() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { ok("a1") }
        assertEquals(TokenResult.Ok("a1"), a.token())
    }

    @Test
    fun networkErrorIsUnavailableNotLoginNeeded() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { throw IOException("offline") }
        assertEquals(TokenResult.Unavailable, a.token())
        assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun hungRefreshIsUnavailable() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets, requestTimeoutMs = 50) { awaitCancellation() }
        assertEquals(TokenResult.Unavailable, a.token())
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
    }

    @Test
    fun rateLimitAndServerErrorsAreUnavailableAndKeepTheLogin() = runBlocking {
        for (code in listOf(408, 429, 500, 502, 503)) {
            val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
            val a = auth(secrets) { respond("{\"error\":\"server_error\"}", HttpStatusCode.fromValue(code), json) }
            assertEquals(TokenResult.Unavailable, a.token(), "HTTP $code")
            assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN), "HTTP $code")
            assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value, "HTTP $code")
        }
    }

    @Test
    fun invalidClientNeedsLoginAndIsNotRetried() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        var calls = 0
        val a = auth(secrets) {
            calls++
            respond("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client\"}", HttpStatusCode.BadRequest, json)
        }
        assertEquals(TokenResult.LoginNeeded, a.token())
        assertNull(secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertTrue(a.status.value is SpotifyAuthStatus.LoginNeeded)
        assertEquals(TokenResult.LoginNeeded, a.token(forceRefresh = true))
        assertEquals(1, calls, "a dead login is not sent to Spotify again")
    }

    @Test
    fun everyOtherClientErrorNeedsLogin() = runBlocking {
        for (code in listOf(400, 401, 403, 404)) {
            val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
            val a = auth(secrets) { respond("", HttpStatusCode.fromValue(code)) }
            assertEquals(TokenResult.LoginNeeded, a.token(), "HTTP $code")
            assertNull(secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN), "HTTP $code")
            assertTrue(a.status.value is SpotifyAuthStatus.LoginNeeded, "HTTP $code")
        }
    }

    @Test
    fun deadLoginWhoseSecretCannotBeRemovedStillNeedsLogin() = runBlocking {
        val stored = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val secrets = object : SecretStore by stored {
            override fun remove(key: String) {
                throw IOException("secrets file in use")
            }
        }
        var calls = 0
        val a = auth(secrets) { calls++; respond("{\"error\":\"invalid_grant\"}", HttpStatusCode.BadRequest, json) }
        assertEquals(TokenResult.LoginNeeded, a.token())
        assertTrue(a.status.value is SpotifyAuthStatus.LoginNeeded)
        assertEquals(TokenResult.LoginNeeded, a.token())
        assertEquals(1, calls)
    }

    @Test
    fun aNewLoginAfterADeadOneWorksAgain() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "old") }
        var receiver: LoopbackReceiver? = null
        val a = auth(
            secrets,
            openBrowser = { url ->
                val state = Url(url).parameters["state"]!!
                val port = receiver!!.boundPort
                Thread {
                    java.net.http.HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/callback?code=c1&state=$state")).build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                }.start()
            },
            receiverFactory = { LoopbackReceiver(0).also { receiver = it } },
        ) { req ->
            if (form(req)["grant_type"] == "refresh_token" && form(req)["refresh_token"] == "old") {
                respond("{\"error\":\"invalid_grant\"}", HttpStatusCode.BadRequest, json)
            } else {
                ok("a2", "new")
            }
        }
        assertEquals(TokenResult.LoginNeeded, a.token())
        assertEquals(LoginResult.Success, withContext(Dispatchers.Default) { a.login() })
        assertEquals(TokenResult.Ok("a2"), a.token())
    }

    @Test
    fun concurrentCallersShareOneRefresh() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        var calls = 0
        val a = auth(secrets) { delay(100); calls++; ok("a1", "r2") }
        val results = withContext(Dispatchers.Default) {
            (1..5).map { async { a.accessToken() } }.awaitAll()
        }
        assertEquals(1, calls)
        assertTrue(results.all { it?.value == "a1" })
    }

    @Test
    fun logoutClearsEverything() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val a = auth(secrets) { ok("a1") }
        a.accessToken()
        a.logout()
        assertNull(secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedOut, a.status.value)
        assertNull(a.accessToken())
    }

    @Test
    fun logoutThatCannotRemoveTheSecretChangesNothing() = runBlocking {
        val stored = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val secrets = object : SecretStore by stored {
            override fun remove(key: String) {
                throw IOException("secrets file in use")
            }
        }
        val inFlight = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = auth(secrets) {
            inFlight.complete(Unit)
            release.await()
            ok("a1")
        }
        val refresh = async(Dispatchers.Default) { a.accessToken() }
        inFlight.await()

        assertFailsWith<IOException> { a.logout() }
        release.complete(Unit)
        assertEquals("a1", refresh.await()?.value, "a refresh in flight is still applied")
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
        assertEquals("r1", stored.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    @Test
    fun hungRefreshTimesOutTransientlyAndReleasesLock() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        var calls = 0
        val a = auth(secrets, requestTimeoutMs = 100) {
            if (calls++ == 0) awaitCancellation() else ok("a1")
        }
        assertNull(a.accessToken())
        assertEquals("r1", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
        // lock was released: a later caller can refresh
        assertEquals("a1", a.accessToken(forceRefresh = true)?.value)
    }

    @Test
    fun hungExchangeFailsWithTimeout() = runBlocking {
        val secrets = InMemorySecretStore()
        var receiver: LoopbackReceiver? = null
        val a = auth(
            secrets,
            requestTimeoutMs = 100,
            openBrowser = { url ->
                val state = Url(url).parameters["state"]!!
                val port = receiver!!.boundPort
                Thread {
                    java.net.http.HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/callback?code=c1&state=$state")).build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                }.start()
            },
            receiverFactory = { LoopbackReceiver(0).also { receiver = it } },
        ) { awaitCancellation() }
        assertEquals(LoginResult.Failed("timeout"), a.login())
        assertNull(secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    @Test
    fun refreshInFlightDuringLogoutIsDiscarded() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = auth(secrets) { entered.complete(Unit); release.await(); ok("a1", "r2") }
        val pending = async(Dispatchers.Default) { a.accessToken() }
        entered.await()
        a.logout()
        release.complete(Unit)
        assertNull(pending.await())
        assertNull(secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedOut, a.status.value)
    }

    @Test
    fun lateInvalidGrantAfterNewLoginKeepsNewSecret() = runBlocking {
        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "old") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var receiver: LoopbackReceiver? = null
        val a = auth(
            secrets,
            openBrowser = { url ->
                val state = Url(url).parameters["state"]!!
                val port = receiver!!.boundPort
                Thread {
                    java.net.http.HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/callback?code=c1&state=$state")).build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                }.start()
            },
            receiverFactory = { LoopbackReceiver(0).also { receiver = it } },
        ) { req ->
            if (form(req)["grant_type"] == "refresh_token") {
                entered.complete(Unit)
                release.await()
                respond("{\"error\":\"invalid_grant\"}", HttpStatusCode.BadRequest, json)
            } else {
                ok("a2", "new")
            }
        }
        val pending = async(Dispatchers.Default) { a.accessToken() }
        entered.await()
        assertEquals(LoginResult.Success, withContext(Dispatchers.Default) { a.login() })
        release.complete(Unit)
        assertNull(pending.await())
        assertEquals("new", secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals(SpotifyAuthStatus.LoggedIn, a.status.value)
    }
}
