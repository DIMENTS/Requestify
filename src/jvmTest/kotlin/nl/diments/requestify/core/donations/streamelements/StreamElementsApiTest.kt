package nl.diments.requestify.core.donations.streamelements

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class StreamElementsApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/streamelements/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }

    /** A JWT whose payload is [payload] (the signature is not checked). */
    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    @Test
    fun channelReadsIdAndName() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val api = StreamElementsApi(HttpClient(MockEngine { req -> seen = req; respond(fixture("channel-me.json"), HttpStatusCode.OK, json) }), jwt = { "tok" })

        val channel = assertIs<SeResult.Ok<SeChannel>>(api.channel()).value
        assertEquals(SeChannel("5b2e2007760aeb7729487dab", "DIMENTS"), channel)
        assertEquals("/kappa/v2/channels/me", checkNotNull(seen).url.encodedPath)
        assertEquals("Bearer tok", checkNotNull(seen).headers[HttpHeaders.Authorization])
    }

    @Test
    fun theChannelGivesItsCurrency() = runBlocking<Unit> {
        var path: String? = null
        val api = StreamElementsApi(HttpClient(MockEngine { req -> path = req.url.encodedPath; respond("""{"currency":"eur","enabled":true}""", HttpStatusCode.OK, json) }), jwt = { "tok" })
        assertEquals(SeResult.Ok("EUR"), api.channelCurrency("ch1"))
        assertEquals("/kappa/v2/tipping/ch1", path)
        val without = StreamElementsApi(HttpClient(MockEngine { respond("""{"enabled":true}""", HttpStatusCode.OK, json) }), jwt = { "tok" })
        assertEquals(SeResult.Ok(null), without.channelCurrency("ch1"), "no such field: unknown")
    }

    @Test
    fun aRefusedTokenSaysRefused() = runBlocking<Unit> {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val api = StreamElementsApi(HttpClient(MockEngine { respond("""{"message":"Not allowed"}""", status, json) }), jwt = { "tok" })
            assertEquals(SeResult.Refused, api.channel())
        }
        assertEquals(SeResult.Refused, StreamElementsApi(HttpClient(MockEngine { error("no call") }), jwt = { null }).channel())
        assertIs<SeResult.Failed>(StreamElementsApi(HttpClient(MockEngine { respond("x", HttpStatusCode.BadGateway) }), jwt = { "t" }).channel())
    }

    @Test
    fun theChannelIdIsReadFromTheJwt() {
        assertEquals("5b2e2007760aeb7729487dab", channelIdFromJwt(jwt("""{"channel":"5b2e2007760aeb7729487dab","role":"owner"}""")))
        assertNull(channelIdFromJwt(jwt("""{"role":"owner"}""")))
        assertNull(channelIdFromJwt("not a jwt"))
    }
}
