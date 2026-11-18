package nl.diments.requestify.core.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ShortLinksTest {
    private val id = "7xtG2Kac0KPcd1ThPEGbZw"

    /** A real [KtorShortLinkResolver] on a mock engine that answers per address and records what was asked. */
    private class Mock(val answers: Map<String, Pair<HttpStatusCode, Pair<String, String?>>>) {
        val asked = mutableListOf<String>()
        val resolver = KtorShortLinkResolver(
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    asked += url
                    val (status, answer) = answers[url] ?: (HttpStatusCode.NotFound to ("" to null))
                    val (body, location) = answer
                    respond(
                        body,
                        status,
                        if (location != null) headersOf(HttpHeaders.Location, location) else headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                },
            ),
        )
    }

    @Test
    fun redirectIsReadNotFollowed() = runBlocking {
        val mock = Mock(
            mapOf(
                "https://spotify.link/abc" to (HttpStatusCode.TemporaryRedirect to ("" to "https://spotify.app.link/abc?_p=c2")),
                "https://spotify.app.link/abc?_p=c2" to (HttpStatusCode.OK to ("never asked here" to null)),
            ),
        )
        assertEquals(ShortLinkAnswer.LeadsTo("https://spotify.app.link/abc?_p=c2"), mock.resolver.resolve("https://spotify.link/abc"))
        assertEquals(listOf("https://spotify.link/abc"), mock.asked)
    }

    @Test
    fun relativeRedirectIsResolvedAgainstTheLink() = runBlocking {
        val mock = Mock(mapOf("https://spotify.link/abc" to (HttpStatusCode.Found to ("" to "/other"))))
        assertEquals(ShortLinkAnswer.LeadsTo("https://spotify.link/other"), mock.resolver.resolve("https://spotify.link/abc"))
    }

    @Test
    fun pageWithASpotifyLinkLeadsToIt() = runBlocking {
        val page = """<html><a href="https://open.spotify.com/download">x</a>
            <script>window.top.location = validateProtocol("https://open.spotify.com/track/$id?si=k&amp;utm=1");</script></html>"""
        val mock = Mock(mapOf("https://spotify.app.link/abc" to (HttpStatusCode.OK to (page to null))))
        assertEquals(
            ShortLinkAnswer.LeadsTo("https://open.spotify.com/track/$id?si=k&amp;utm=1"),
            mock.resolver.resolve("https://spotify.app.link/abc"),
        )
    }

    @Test
    fun pageWithoutASpotifyLinkIsUnresolvable() = runBlocking {
        val page = """<html><meta property="og:url" content="http://spotify.link/zzz" /><h1>Welcome to Spotify!</h1></html>"""
        val mock = Mock(mapOf("https://spotify.link/zzz" to (HttpStatusCode.OK to (page to null))))
        assertEquals(ShortLinkAnswer.Unresolvable, mock.resolver.resolve("https://spotify.link/zzz"))
        assertEquals(ShortLinkAnswer.Unresolvable, mock.resolver.resolve("https://spotify.link/unknown")) // 404
    }

    @Test
    fun serverTroubleAndNetworkErrorsAreUnreachable() = runBlocking {
        val mock = Mock(
            mapOf(
                "https://spotify.link/a" to (HttpStatusCode.ServiceUnavailable to ("" to null)),
                "https://spotify.link/b" to (HttpStatusCode.TooManyRequests to ("" to null)),
            ),
        )
        assertEquals(ShortLinkAnswer.Unreachable, mock.resolver.resolve("https://spotify.link/a"))
        assertEquals(ShortLinkAnswer.Unreachable, mock.resolver.resolve("https://spotify.link/b"))
        val offline = KtorShortLinkResolver(HttpClient(MockEngine { throw IOException("no network") }))
        assertEquals(ShortLinkAnswer.Unreachable, offline.resolve("https://spotify.link/a"))
    }

    @Test
    fun neverAsksAnyOtherHost() = runBlocking {
        val mock = Mock(emptyMap())
        assertEquals(ShortLinkAnswer.Unresolvable, mock.resolver.resolve("https://example.com/abc"))
        assertEquals(ShortLinkAnswer.Unresolvable, mock.resolver.resolve("https://open.spotify.com/track/$id"))
        assertEquals(emptyList(), mock.asked)
    }

    // follow(): hop by hop, only through the short-link hosts.

    private class Script(val answers: Map<String, ShortLinkAnswer>) : ShortLinkResolver {
        val asked = mutableListOf<String>()
        override suspend fun resolve(url: String): ShortLinkAnswer {
            asked += url
            return answers[url] ?: ShortLinkAnswer.Unresolvable
        }
    }

    @Test
    fun followsTwoHopsToTheTrack() = runBlocking {
        val script = Script(
            mapOf(
                "https://spotify.link/abc" to ShortLinkAnswer.LeadsTo("https://spotify.app.link/abc?_p=c2"),
                "https://spotify.app.link/abc?_p=c2" to ShortLinkAnswer.LeadsTo("https://open.spotify.com/track/$id?si=x"),
            ),
        )
        assertEquals(
            ShortLinkTarget.Link(SpotifyInput.TrackId(id)),
            script.follow(SpotifyInput.ShortLink("https://spotify.link/abc")),
        )
        assertEquals(listOf("https://spotify.link/abc", "https://spotify.app.link/abc?_p=c2"), script.asked)
    }

    @Test
    fun aLinkToAnAlbumIsAnAlbum() = runBlocking {
        val script = Script(mapOf("https://spotify.link/a" to ShortLinkAnswer.LeadsTo("https://open.spotify.com/album/$id")))
        assertEquals(ShortLinkTarget.Link(SpotifyInput.NotATrack("album")), script.follow(SpotifyInput.ShortLink("https://spotify.link/a")))
    }

    @Test
    fun aRedirectElsewhereIsUnresolvable() = runBlocking {
        val script = Script(
            mapOf(
                "https://spotify.link/a" to ShortLinkAnswer.LeadsTo("https://evil.test/?u=https://open.spotify.com/track/$id"),
                "https://spotify.link/b" to ShortLinkAnswer.LeadsTo("https://www.spotify.com/download"),
            ),
        )
        assertEquals(ShortLinkTarget.Unresolvable, script.follow(SpotifyInput.ShortLink("https://spotify.link/a")))
        assertEquals(ShortLinkTarget.Unresolvable, script.follow(SpotifyInput.ShortLink("https://spotify.link/b")))
        assertEquals(listOf("https://spotify.link/a", "https://spotify.link/b"), script.asked) // the other hosts never
    }

    @Test
    fun aLoopEndsAfterAFewHops() = runBlocking {
        val script = Script(
            mapOf(
                "https://spotify.link/a" to ShortLinkAnswer.LeadsTo("https://spotify.link/b"),
                "https://spotify.link/b" to ShortLinkAnswer.LeadsTo("https://spotify.link/a"),
            ),
        )
        assertEquals(ShortLinkTarget.Unresolvable, script.follow(SpotifyInput.ShortLink("https://spotify.link/a")))
        assertEquals(3, script.asked.size)
    }

    @Test
    fun unreachableStaysUnreachable() = runBlocking {
        val script = Script(mapOf("https://spotify.link/a" to ShortLinkAnswer.Unreachable))
        assertEquals(ShortLinkTarget.Unreachable, script.follow(SpotifyInput.ShortLink("https://spotify.link/a")))
    }
}
