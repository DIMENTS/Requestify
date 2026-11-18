package nl.diments.requestify.core.donations.donolink

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class DonoLinkApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/donolink/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }

    private fun api(
        key: String? = "dl_live_secret",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = DonoLinkApi(HttpClient(MockEngine(handler)), key = { key }, baseUrl = "https://donolink.test/api/v1")

    private fun <T> DonoLinkResult<T>.ok(): T = assertIs<DonoLinkResult.Ok<T>>(this).value

    @Test
    fun meReadsOwnerAndExpiry() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val me = api { req -> seen = req; respond(fixture("me.json"), HttpStatusCode.OK, json) }.me().ok()

        assertEquals(DonoLinkMe("DIMENTS", Instant.parse("2026-12-06T12:00:00Z").toEpochMilli()), me)
        assertEquals("/api/v1/me", checkNotNull(seen).url.encodedPath)
        assertEquals("Bearer dl_live_secret", checkNotNull(seen).headers[HttpHeaders.Authorization])
    }

    @Test
    fun activitySendsTheFiltersAndKey() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val page = api { req -> seen = req; respond(fixture("activity-page1.json"), HttpStatusCode.OK, json) }
            .activity(since = "2026-11-18T19:00:00.000Z", cursor = null).ok()

        val url = checkNotNull(seen).url
        assertEquals("/api/v1/activity", url.encodedPath)
        assertEquals("donolink", url.parameters["platform"])
        assertEquals("donation", url.parameters["type"])
        assertEquals("asc", url.parameters["order"])
        assertEquals("100", url.parameters["limit"])
        assertEquals("2026-11-18T19:00:00.000Z", url.parameters["since"])
        assertEquals(null, url.parameters["cursor"])
        assertEquals("Bearer dl_live_secret", checkNotNull(seen).headers[HttpHeaders.Authorization])
        assertEquals(1, page.items.size)
        assertEquals(true, page.hasMore)
        assertEquals("c2", page.nextCursor)
    }

    @Test
    fun followsTheCursorParameter() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val page = api { req -> seen = req; respond(fixture("activity-page2.json"), HttpStatusCode.OK, json) }
            .activity(since = "2026-11-18T19:00:00.000Z", cursor = "c2").ok()

        assertEquals("c2", checkNotNull(seen).url.parameters["cursor"])
        assertEquals("2026-11-18T19:00:00.000Z", checkNotNull(seen).url.parameters["since"], "the same since on every page")
        assertEquals(false, page.hasMore)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun refusedKeyMapsToKeyRefused() = runBlocking<Unit> {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val answer = api { respond("""{"error":{"code":"invalid_api_key"}}""", status, json) }.activity("x", null)
            assertEquals(DonoLinkResult.KeyRefused(status.value), answer)
        }
        assertIs<DonoLinkResult.KeyRefused>(api(key = null) { error("no call without a key") }.me())
    }

    @Test
    fun rateLimitHonoursRetryAfterWithCap() = runBlocking<Unit> {
        fun limited(status: HttpStatusCode, retryAfter: String?) = runBlocking {
            api {
                val headers = if (retryAfter == null) json else headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf(retryAfter))
                respond("""{"error":{"code":"rate_limit_exceeded"}}""", status, headers)
            }.activity("x", null)
        }
        assertEquals(DonoLinkResult.RateLimited(7_000), limited(HttpStatusCode.TooManyRequests, "7"))
        assertEquals(DonoLinkResult.RateLimited(300_000), limited(HttpStatusCode.TooManyRequests, "900"))
        assertEquals(DonoLinkResult.RateLimited(30_000), limited(HttpStatusCode.TooManyRequests, null))
        assertEquals(DonoLinkResult.RateLimited(60_000), limited(HttpStatusCode.ServiceUnavailable, "60"))
    }

    @Test
    fun otherFailuresAreFailed() = runBlocking<Unit> {
        assertIs<DonoLinkResult.Failed>(api { respond("oops", HttpStatusCode.InternalServerError) }.activity("x", null))
        assertIs<DonoLinkResult.Failed>(api { respond("not json", HttpStatusCode.OK, json) }.activity("x", null))
        assertIs<DonoLinkResult.Failed>(api { throw java.io.IOException("offline") }.me())
    }
}
