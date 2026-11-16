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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys

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
        secrets: InMemorySecretStore,
        clock: () -> Long = { 0L },
        openBrowser: (String) -> Unit = {},
        receiverFactory: () -> LoopbackReceiver = { LoopbackReceiver(0) },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SpotifyAuth(
        http = HttpClient(MockEngine(handler)),
        secrets = secrets,
        clientId = { "cid" },
        clock = clock,
        openBrowser = openBrowser,
        receiverFactory = receiverFactory,
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
            "user-read-playback-state user-modify-playback-state user-read-currently-playing user-read-private",
            Url(url).parameters["scope"],
        )
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
}
