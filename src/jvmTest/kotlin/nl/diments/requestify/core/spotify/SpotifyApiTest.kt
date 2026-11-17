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
        token: suspend (Boolean) -> TokenResult = { TokenResult.Ok("tok") },
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
        val api = api(token = { force -> forced += force; TokenResult.Ok(if (force) "new" else "old") }) { req ->
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
    fun deadLoginIsLoginNeededWithoutRequest() = runBlocking<Unit> {
        var calls = 0
        val result = api(token = { TokenResult.LoginNeeded }) { calls++; respond("", HttpStatusCode.OK) }.pause()
        assertEquals(SpotifyResult.LoginNeeded, result)
        assertEquals(0, calls)
    }

    @Test
    fun loggedOutIsLoggedOutWithoutRequest() = runBlocking<Unit> {
        var calls = 0
        val result = api(token = { TokenResult.LoggedOut }) { calls++; respond("", HttpStatusCode.OK) }.pause()
        assertEquals(SpotifyResult.LoggedOut, result)
        assertEquals(0, calls)
    }

    @Test
    fun unavailableTokenIsAFailureNotALogin() = runBlocking<Unit> {
        var calls = 0
        val result = api(token = { TokenResult.Unavailable }) { calls++; respond("", HttpStatusCode.OK) }.pause()
        assertEquals(SpotifyResult.Failed("token unavailable"), result)
        assertEquals(0, calls)
    }

    @Test
    fun deadLoginAfterRefreshIsLoginNeeded() = runBlocking<Unit> {
        val result = api(token = { force -> if (force) TokenResult.LoginNeeded else TokenResult.Ok("old") }) {
            respond("", HttpStatusCode.Unauthorized)
        }.pause()
        assertEquals(SpotifyResult.LoginNeeded, result)
    }

    @Test
    fun unavailableTokenAfterRefreshIsAFailure() = runBlocking<Unit> {
        val result = api(token = { force -> if (force) TokenResult.Unavailable else TokenResult.Ok("old") }) {
            respond("", HttpStatusCode.Unauthorized)
        }.pause()
        assertEquals(SpotifyResult.Failed("token unavailable"), result)
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
    fun notFoundFor400And404WithoutDeviceReason() = runBlocking<Unit> {
        val notFound = api { respond("""{"error":{"status":404,"message":"Not found"}}""", HttpStatusCode.NotFound, json) }
            .track("nope")
        assertEquals(SpotifyResult.NotFound, notFound)
        val bad = api { respond("""{"error":{"status":400,"message":"Invalid id"}}""", HttpStatusCode.BadRequest, json) }
            .track("nope")
        assertEquals(SpotifyResult.NotFound, bad)
    }

    @Test
    fun trackParsesArtistIdsAndIsPlayable() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val body = """{"id":"t","uri":"spotify:track:t","name":"T","type":"track","artists":[{"id":"a1","name":"A"},{"id":"a2","name":"B"}],"is_playable":false}"""
        val track = api { req -> seen = req; respond(body, HttpStatusCode.OK, json) }.track("t").ok()
        assertEquals(listOf("a1", "a2"), track.artistIds)
        assertEquals(false, track.isPlayable)
        assertEquals("from_token", seen!!.url.parameters["market"])
        val plain = api { fixtureOk("track.json") }.track("x").ok()
        assertEquals(true, plain.isPlayable)
        assertEquals(emptyList(), plain.artistIds)
        var searched: HttpRequestData? = null
        api { req -> searched = req; fixtureOk("search.json") }.searchTracks("x")
        assertEquals("from_token", searched!!.url.parameters["market"])
    }

    @Test
    fun searchArtistsUsesTypeArtistAndLimitAtMost10() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val body = """{"artists":{"items":[{"id":"a1","name":"M83","images":[{"url":"https://i/big","width":640},{"url":"https://i/small","width":160}]},null,{"id":"a2","name":"No Image"}]}}"""
        val result = api { req -> seen = req; respond(body, HttpStatusCode.OK, json) }.searchArtists("m83", limit = 50)
        assertEquals("artist", seen!!.url.parameters["type"])
        assertEquals("10", seen.url.parameters["limit"])
        assertEquals("m83", seen.url.parameters["q"])
        assertEquals(listOf(ArtistRef("a1", "M83", "https://i/small"), ArtistRef("a2", "No Image", null)), result.ok())
    }

    @Test
    fun artistById() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val result = api { req -> seen = req; respond("""{"id":"a1","name":"M83","images":[]}""", HttpStatusCode.OK, json) }.artist("a1")
        assertEquals("/v1/artists/a1", seen!!.url.encodedPath)
        assertEquals(ArtistRef("a1", "M83", null), result.ok())
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

    private val localFile =
        """{"id":null,"uri":"spotify:local:a:b:c:1","name":"Local","type":"track","is_local":true,"artists":[{"name":null}],"album":{"images":[]}}"""

    @Test
    fun localFileCurrentlyPlayingHasNullTrack() = runBlocking<Unit> {
        val result = api { respond("""{"progress_ms":9,"is_playing":true,"item":$localFile}""", HttpStatusCode.OK, json) }
            .currentlyPlaying().ok()
        assertNull(checkNotNull(result).track)
        assertEquals(9L, result.progressMs)
    }

    @Test
    fun trackMissingUriHasNullTrack() = runBlocking<Unit> {
        val body = """{"is_playing":true,"item":{"id":"x","name":"N","type":"track"}}"""
        val result = api { respond(body, HttpStatusCode.OK, json) }.currentlyPlaying().ok()
        assertNull(checkNotNull(result).track)
    }

    @Test
    fun queueDropsNullAndMissingIds() = runBlocking<Unit> {
        val body = """{"queue":[{"id":"q1","type":"track"},$localFile,{"type":"track"},{"id":"q2","type":"track"}]}"""
        assertEquals(listOf("q1", "q2"), api { respond(body, HttpStatusCode.OK, json) }.userQueueTrackIds().ok())
    }

    @Test
    fun searchDropsUnusableItems() = runBlocking<Unit> {
        val body = """{"tracks":{"items":[$localFile,null,{"id":"a","uri":"spotify:track:a","name":"A","artists":[]}]}}"""
        val tracks = api { respond(body, HttpStatusCode.OK, json) }.searchTracks("x").ok()
        assertEquals(listOf("A"), tracks.map(Track::name))
    }

    @Test
    fun trackWithoutUsableIdIsFailed() = runBlocking<Unit> {
        val result = api { respond(localFile, HttpStatusCode.OK, json) }.track("whatever")
        assertIs<SpotifyResult.Failed>(result)
    }

    @Test
    fun albumArtNullWithoutImages()= runBlocking<Unit> {
        val tracks = api { fixtureOk("search.json") }.searchTracks("x").ok()
        assertNull(tracks[0].albumArtUrl)
        assertEquals("https://i.scdn.co/image/b2", tracks[1].albumArtUrl)
    }

    @Test
    fun previousPostsToPlayerPrevious() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val result = api { req -> seen = req; respond("", HttpStatusCode.NoContent) }.skipPrevious()
        assertEquals(Unit, result.ok())
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/v1/me/player/previous", seen.url.encodedPath)
    }

    @Test
    fun userQueueMapsTracksAndDropsUnusable() = runBlocking<Unit> {
        val body = """{"queue":[{"id":"q1","uri":"spotify:track:q1","name":"Q1","type":"track","artists":[{"name":"A"}]},$localFile,""" +
            """{"id":"ep1","uri":"spotify:episode:ep1","name":"Ep","type":"episode"},""" +
            """{"id":"q2","uri":"spotify:track:q2","name":"Q2","type":"track","artists":[]}]}"""
        val tracks = api { respond(body, HttpStatusCode.OK, json) }.userQueue().ok()
        assertEquals(listOf("Q1", "Q2"), tracks.map(Track::name))
        assertEquals(listOf("q1", "q2"), tracks.map(Track::id))
    }

    @Test
    fun recentlyPlayedMapsTrackAndPlayedAt() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        val recent = checkNotNull(api { req -> seen = req; fixtureOk("recently-played.json") }.recentlyPlayed().ok())
        assertEquals("Midnight City", recent.track.name)
        assertEquals(1794907800000L, recent.playedAtEpochMs)
        assertEquals("/v1/me/player/recently-played", seen!!.url.encodedPath)
        assertEquals("1", seen.url.parameters["limit"])
    }

    @Test
    fun recentlyPlayedSinceSendsAfterAndLimit50() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        fun track(id: String) = """{"id":"$id","uri":"spotify:track:$id","name":"$id","type":"track"}"""
        val body = """{"items":[""" +
            """{"played_at":"2026-11-17T09:35:00.000Z","track":${track("b")}},""" +
            """{"played_at":"2026-11-17T09:34:00.000Z","track":$localFile},""" +
            """{"played_at":"yesterday","track":${track("c")}},""" +
            """{"played_at":"2026-11-17T09:30:00.000Z","track":${track("a")}}]}"""
        val recent = api { req -> seen = req; respond(body, HttpStatusCode.OK, json) }
            .recentlyPlayedSince(afterEpochMs = 1_794_907_000_000)
            .ok()
        assertEquals(
            listOf("b" to 1_794_908_100_000L, "a" to 1_794_907_800_000L), // unusable track or time: dropped
            recent.map { it.track.id to it.playedAtEpochMs },
        )
        assertEquals(HttpMethod.Get, seen!!.method)
        assertEquals("/v1/me/player/recently-played", seen.url.encodedPath)
        assertEquals("50", seen.url.parameters["limit"])
        assertEquals("1794907000000", seen.url.parameters["after"])
    }

    @Test
    fun recentlyPlayedEmptyIsOkNull() = runBlocking<Unit> {
        assertNull(api { respond("""{"items":[]}""", HttpStatusCode.OK, json) }.recentlyPlayed().ok())
    }

    @Test
    fun recentlyPlayedWithBadDateOrTrackIsOkNull() = runBlocking<Unit> {
        val badDate = """{"items":[{"played_at":"yesterday","track":{"id":"a","uri":"spotify:track:a","name":"A"}}]}"""
        assertNull(api { respond(badDate, HttpStatusCode.OK, json) }.recentlyPlayed().ok())
        val badTrack = """{"items":[{"played_at":"2026-11-17T09:30:00.000Z","track":$localFile}]}"""
        assertNull(api { respond(badTrack, HttpStatusCode.OK, json) }.recentlyPlayed().ok())
    }

    @Test
    fun insufficientScopeIsMissingPermission() = runBlocking<Unit> {
        val result = api {
            respond("""{"error":{"status":403,"message":"Insufficient client scope"}}""", HttpStatusCode.Forbidden, json)
        }.recentlyPlayed()
        assertEquals(SpotifyResult.MissingPermission, result)
    }

    @Test
    fun premiumRequiredStaysPremiumRequired() = runBlocking<Unit> {
        val result = api {
            respond(
                """{"error":{"status":403,"message":"Premium required, scope irrelevant","reason":"PREMIUM_REQUIRED"}}""",
                HttpStatusCode.Forbidden,
                json,
            )
        }.skipPrevious()
        assertEquals(SpotifyResult.PremiumRequired, result)
    }
}
