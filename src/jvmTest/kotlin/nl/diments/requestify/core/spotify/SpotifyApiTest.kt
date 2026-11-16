package nl.diments.requestify.core.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.core.model.Track

class SpotifyApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/spotify/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }

    private fun MockRequestHandleScope.fixtureOk(name: String) = respond(fixture(name), HttpStatusCode.OK, json)

    private fun api(
        token: suspend (Boolean) -> String? = { "tok" },
        requestTimeoutMs: Long = 15_000,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SpotifyApi(
        http = HttpClient(MockEngine(handler)),
        token = token,
        baseUrl = "https://spotify.test/v1",
        requestTimeoutMs = requestTimeoutMs,
    )

    private fun <T> SpotifyResult<T>.ok(): T = assertIs<SpotifyResult.Ok<T>>(this).value

    @Test
    fun parsesCurrentlyPlaying() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val result = api { req -> seen = req; fixtureOk("currently-playing.json") }.currentlyPlaying()
        val playing = checkNotNull(result.ok())
        val track = checkNotNull(playing.track)
        assertEquals("1B75hgRqe7A4fwee3g3Wmu", track.id)
        assertEquals("spotify:track:1B75hgRqe7A4fwee3g3Wmu", track.uri)
        assertEquals("Midnight City", track.name)
        assertEquals(listOf("M83"), track.artists)
        assertEquals(243000L, track.durationMs)
        assertEquals(141000L, playing.progressMs)
        assertTrue(playing.isPlaying)
        assertEquals("/v1/me/player/currently-playing", seen!!.url.encodedPath)
        assertEquals("Bearer tok", seen.headers[HttpHeaders.Authorization])
    }

    @Test
    fun noContentIsOkNull() = runBlocking<Unit> {
        val result = api { respond("", HttpStatusCode.NoContent) }.currentlyPlaying()
        assertNull(result.ok())
    }

    @Test
    fun emptyBodyIsOkNull() = runBlocking<Unit> {
        val result = api { respond("", HttpStatusCode.OK, json) }.currentlyPlaying()
        assertNull(result.ok())
    }

    @Test
    fun adsAndEpisodesHaveNullTrack() = runBlocking<Unit> {
        val ad = api { respond("""{"progress_ms":null,"is_playing":true,"item":null}""", HttpStatusCode.OK, json) }
            .currentlyPlaying().ok()
        assertNull(checkNotNull(ad).track)
        assertEquals(0L, ad.progressMs)
        val episode = api {
            respond(
                """{"progress_ms":5,"is_playing":false,"item":{"id":"e","type":"episode","name":"Ep"}}""",
                HttpStatusCode.OK,
                json,
            )
        }.currentlyPlaying().ok()
        assertNull(checkNotNull(episode).track)
        assertEquals(5L, episode.progressMs)
    }

    @Test
    fun userQueueKeepsOnlyTrackIdsInOrder() = runBlocking<Unit> {
        val result = api { fixtureOk("queue.json") }.userQueueTrackIds()
        assertEquals(listOf("q1", "q2"), result.ok())
    }

    @Test
    fun unauthorizedRefreshesAndRetriesOnce() = runBlocking<Unit> {
        val forced = mutableListOf<Boolean>()
        val auths = mutableListOf<String?>()
        val api = api(token = { force -> forced += force; if (force) "new" else "old" }) { req ->
            auths += req.headers[HttpHeaders.Authorization]
            if (auths.size == 1) respond("", HttpStatusCode.Unauthorized) else fixtureOk("track.json")
        }
        val track = api.track("1B75hgRqe7A4fwee3g3Wmu").ok()
        assertEquals("Midnight City", track.name)
        assertEquals(listOf(false, true), forced)
        assertEquals(listOf<String?>("Bearer old", "Bearer new"), auths)
    }

    @Test
    fun unauthorizedTwiceIsLoginNeeded() = runBlocking<Unit> {
        var calls = 0
        val result = api { calls++; respond("", HttpStatusCode.Unauthorized) }.pause()
        assertEquals(SpotifyResult.LoginNeeded, result)
        assertEquals(2, calls)
    }

    @Test
    fun nullTokenIsLoginNeededWithoutRequest() = runBlocking<Unit> {
        var calls = 0
        val result = api(token = { null }) { calls++; respond("", HttpStatusCode.OK) }.pause()
        assertEquals(SpotifyResult.LoginNeeded, result)
        assertEquals(0, calls)
    }

    @Test
    fun nullTokenAfterRefreshIsLoginNeeded() = runBlocking<Unit> {
        val result = api(token = { force -> if (force) null else "old" }) { respond("", HttpStatusCode.Unauthorized) }
            .pause()
        assertEquals(SpotifyResult.LoginNeeded, result)
    }

    @Test
    fun premiumRequired() = runBlocking<Unit> {
        val result = api {
            respond(
                """{"error":{"status":403,"message":"Premium required","reason":"PREMIUM_REQUIRED"}}""",
                HttpStatusCode.Forbidden,
                json,
            )
        }.skipNext()
        assertEquals(SpotifyResult.PremiumRequired, result)
    }

    @Test
    fun forbiddenWithoutReasonIsFailed() = runBlocking<Unit> {
        val result = api { respond("not json", HttpStatusCode.Forbidden) }.skipNext()
        assertIs<SpotifyResult.Failed>(result)
    }

    @Test
    fun noActiveDevice() = runBlocking<Unit> {
        val result = api {
            respond(
                """{"error":{"status":404,"message":"No active device found","reason":"NO_ACTIVE_DEVICE"}}""",
                HttpStatusCode.NotFound,
                json,
            )
        }.resume()
        assertEquals(SpotifyResult.NoActiveDevice, result)
    }

    @Test
    fun notFoundWithoutReasonIsFailed() = runBlocking<Unit> {
        val result = api { respond("""{"error":{"status":404,"message":"Not found"}}""", HttpStatusCode.NotFound, json) }
            .track("nope")
        assertIs<SpotifyResult.Failed>(result)
    }

    @Test
    fun rateLimitedUsesRetryAfter() = runBlocking<Unit> {
        val result = api {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
        }.addToQueue("spotify:track:x")
        assertEquals(SpotifyResult.RateLimited(7000), result)
    }

    @Test
    fun rateLimitedDefault() = runBlocking<Unit> {
        val result = api { respond("", HttpStatusCode.TooManyRequests) }.addToQueue("spotify:track:x")
        assertEquals(SpotifyResult.RateLimited(5000), result)
    }

    @Test
    fun rateLimitedUnparseableHeaderUsesDefault() = runBlocking<Unit> {
        val result = api {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "soon"))
        }.addToQueue("spotify:track:x")
        assertEquals(SpotifyResult.RateLimited(5000), result)
    }

    @Test
    fun serverErrorIsFailed() = runBlocking<Unit> {
        val result = api { respond("", HttpStatusCode.BadGateway) }.pause()
        assertIs<SpotifyResult.Failed>(result)
    }

    @Test
    fun ioErrorIsFailed() = runBlocking<Unit> {
        val result = api { throw IOException("boom") }.pause()
        assertEquals("boom", assertIs<SpotifyResult.Failed>(result).message)
    }

    @Test
    fun malformedJsonIsFailed() = runBlocking<Unit> {
        val result = api { respond("{oops", HttpStatusCode.OK, json) }.currentlyPlaying()
        assertIs<SpotifyResult.Failed>(result)
    }

    @Test
    fun timeoutIsFailed() = runBlocking<Unit> {
        val result = api(requestTimeoutMs = 50) { awaitCancellation() }.pause()
        assertEquals(SpotifyResult.Failed("timeout"), result)
    }

    @Test
    fun cancellationIsRethrown() {
        val outcome = runCatching {
            runBlocking { api { throw CancellationException("cancelled") }.pause() }
        }
        assertIs<CancellationException>(outcome.exceptionOrNull())
    }

    @Test
    fun searchLimitCappedAt10() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val result = api { req -> seen = req; fixtureOk("search.json") }.searchTracks("midnight city", limit = 25)
        val params = seen!!.url.parameters
        assertEquals("10", params["limit"])
        assertEquals("track", params["type"])
        assertEquals("midnight city", params["q"])
        assertEquals("/v1/search", seen.url.encodedPath)
        assertEquals(listOf("One", "Two"), result.ok().map(Track::name))
    }

    @Test
    fun searchLimitFlooredAtOne() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        api { req -> seen = req; fixtureOk("search.json") }.searchTracks("x", limit = 0)
        assertEquals("1", seen!!.url.parameters["limit"])
    }

    @Test
    fun addToQueueSendsUri() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val result = api { req -> seen = req; respond("", HttpStatusCode.NoContent) }
            .addToQueue("spotify:track:1B75hgRqe7A4fwee3g3Wmu")
        assertEquals(Unit, result.ok())
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/v1/me/player/queue", seen.url.encodedPath)
        assertTrue(seen.url.encodedQuery.contains("uri=spotify%3Atrack%3A1B75hgRqe7A4fwee3g3Wmu"))
    }

    @Test
    fun commandsUseExpectedEndpoints() = runBlocking<Unit> {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val api = api { req -> seen += req.method to req.url.encodedPath; respond("", HttpStatusCode.OK) }
        api.skipNext().ok()
        api.pause().ok()
        api.resume().ok()
        assertEquals(
            listOf(
                HttpMethod.Post to "/v1/me/player/next",
                HttpMethod.Put to "/v1/me/player/pause",
                HttpMethod.Put to "/v1/me/player/play",
            ),
            seen,
        )
    }

    @Test
    fun isPremiumTrueWhenProductPremium() = runBlocking<Unit> {
        assertEquals(true, api { fixtureOk("me-premium.json") }.isPremium().ok())
    }

    @Test
    fun isPremiumFalseForOtherProduct() = runBlocking<Unit> {
        val result = api { respond("""{"product":"free"}""", HttpStatusCode.OK, json) }.isPremium()
        assertEquals(false, result.ok())
    }

    @Test
    fun isPremiumNullWhenProductMissing() = runBlocking<Unit> {
        assertNull(api { fixtureOk("me-no-product.json") }.isPremium().ok())
    }

    @Test
    fun albumArtClosestTo300() = runBlocking<Unit> {
        val track = checkNotNull(api { fixtureOk("currently-playing.json") }.currentlyPlaying().ok()).track!!
        assertEquals("https://i.scdn.co/image/medium", track.albumArtUrl)
    }

    @Test
    fun albumArtNullWithoutImages() = runBlocking<Unit> {
        val tracks = api { fixtureOk("search.json") }.searchTracks("x").ok()
        assertNull(tracks[0].albumArtUrl)
        assertEquals("https://i.scdn.co/image/b2", tracks[1].albumArtUrl)
    }
}
