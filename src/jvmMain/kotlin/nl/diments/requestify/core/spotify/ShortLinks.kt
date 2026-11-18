package nl.diments.requestify.core.spotify

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readString

/** What a short-link host answered for one link. */
sealed interface ShortLinkAnswer {
    /** The address the link leads to: its redirect, or the `open.spotify.com` link on the page it shows. */
    data class LeadsTo(val url: String) : ShortLinkAnswer

    /** It leads nowhere usable: an unknown code, or a page without a Spotify link. */
    data object Unresolvable : ShortLinkAnswer

    /** It could not be asked now (network, timeout, a server error): asking again later can work. */
    data object Unreachable : ShortLinkAnswer
}

/** Asks a short-link host where one link leads, without following any redirect itself. */
fun interface ShortLinkResolver {
    suspend fun resolve(url: String): ShortLinkAnswer
}

/** What a [SpotifyInput.ShortLink] turned out to be. */
sealed interface ShortLinkTarget {
    /** A Spotify link: [input] is a [SpotifyInput.TrackId], [SpotifyInput.ArtistId] or [SpotifyInput.NotATrack]. */
    data class Link(val input: SpotifyInput) : ShortLinkTarget

    /** Not a Spotify link: the viewer is told to use the open.spotify.com link. */
    data object Unresolvable : ShortLinkTarget

    /** The short-link host could not be asked now. */
    data object Unreachable : ShortLinkTarget
}

/** How many redirects one short link may take (spotify.link → spotify.app.link → open.spotify.com is two). */
private const val MAX_HOPS = 3

private val SHORT_LINK_HOSTS = setOf("spotify.link", "spotify.app.link")

/**
 * Follows [link] one redirect at a time, only ever asking the short-link hosts, until it reaches a Spotify link
 * (an `open.spotify.com` address or a `spotify:` URI). Anything else it leads to is [ShortLinkTarget.Unresolvable].
 */
suspend fun ShortLinkResolver.follow(link: SpotifyInput.ShortLink): ShortLinkTarget {
    var url = link.url
    repeat(MAX_HOPS) {
        val next = when (val answer = resolve(url)) {
            is ShortLinkAnswer.LeadsTo -> answer.url
            ShortLinkAnswer.Unresolvable -> return ShortLinkTarget.Unresolvable
            ShortLinkAnswer.Unreachable -> return ShortLinkTarget.Unreachable
        }
        val hop = shortLinkUrl(next)
        if (hop == null) return spotifyTarget(next)
        url = hop
    }
    return ShortLinkTarget.Unresolvable
}

/** [url] as an https short-link address (query kept), or null when it is not on a short-link host. */
internal fun shortLinkUrl(url: String): String? {
    val uri = parse(url) ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.lowercase() !in SHORT_LINK_HOSTS) return null
    return "https://${uri.host.lowercase()}${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
}

/** The Spotify link [url] is, when it is one on Spotify's own web address or a `spotify:` URI. */
private fun spotifyTarget(url: String): ShortLinkTarget {
    val uri = parse(url) ?: return ShortLinkTarget.Unresolvable
    val onSpotify = uri.scheme?.lowercase() == "spotify" ||
        (uri.scheme?.lowercase() in setOf("http", "https") && uri.host?.lowercase() == "open.spotify.com")
    if (!onSpotify) return ShortLinkTarget.Unresolvable
    val input = classifySpotifyInput(url)
    return if (isItemLink(input)) ShortLinkTarget.Link(input) else ShortLinkTarget.Unresolvable
}

/** A link to a track, artist or other Spotify item (not search text, and not a further short link). */
private fun isItemLink(input: SpotifyInput) =
    input is SpotifyInput.TrackId || input is SpotifyInput.ArtistId || input is SpotifyInput.NotATrack

private fun parse(url: String): URI? = try {
    URI(url.trim())
} catch (_: Exception) {
    null
}

/**
 * Asks spotify.link / spotify.app.link where a link leads with one GET that follows no redirect: the redirect's
 * `Location`, or else the first `open.spotify.com` link on the page it serves (Spotify's link pages send browsers on
 * with a script). Never asks any other host.
 */
class KtorShortLinkResolver(http: HttpClient, private val timeoutMs: Long = 8_000) : ShortLinkResolver {
    private val client = http.config { followRedirects = false }

    override suspend fun resolve(url: String): ShortLinkAnswer {
        val address = shortLinkUrl(url) ?: return ShortLinkAnswer.Unresolvable
        return try {
            withTimeoutOrNull(timeoutMs) { client.prepareGet(address).execute { answer(address, it) } }
                ?: ShortLinkAnswer.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ShortLinkAnswer.Unreachable
        }
    }

    private suspend fun answer(address: String, response: HttpResponse): ShortLinkAnswer {
        val status = response.status.value
        return when {
            status in 300..399 -> response.headers[HttpHeaders.Location]
                ?.let { location -> parse(address)?.let { base -> runCatching { base.resolve(location.trim()) }.getOrNull() } }
                ?.let { ShortLinkAnswer.LeadsTo(it.toString()) }
                ?: ShortLinkAnswer.Unresolvable
            status in 200..299 -> {
                val page = response.bodyAsChannel().readRemaining(MAX_PAGE_BYTES).readString()
                PAGE_LINK.findAll(page).map { it.value }.firstOrNull { isItemLink(classifySpotifyInput(it)) }
                    ?.let(ShortLinkAnswer::LeadsTo)
                    ?: ShortLinkAnswer.Unresolvable
            }
            status == 429 || status >= 500 -> ShortLinkAnswer.Unreachable
            else -> ShortLinkAnswer.Unresolvable
        }
    }

    private companion object {
        /** Spotify's link pages are about 10 KB; a page is read no further than this. */
        const val MAX_PAGE_BYTES = 256L * 1024
        val PAGE_LINK = Regex("""https://open\.spotify\.com/[^\s"'<>\\]+""")
    }
}
