package nl.diments.requestify.ui

import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import nl.diments.requestify.core.log.LogConnectionState
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.log.LogService
import nl.diments.requestify.core.log.LogStep
import nl.diments.requestify.core.log.LogStreamChange
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.storage.AppJson
import nl.diments.requestify.core.storage.JsonFileStore

/**
 * A streamer mid-stream, for offscreen renders and layout tests: made-up requests, requesters and album art (colour
 * gradients served over local HTTP like Spotify's CDN: no real covers), and Spotify as the app sees it.
 */
internal object LiveFixture {
    /** The longest names the app meets: a long remaster title, several artists, a 25-character Twitch login. */
    const val LONG_TITLE = "Don't Stop Me Now (feat. A Very Long Guest Artist Name) - Remastered 2011 Deluxe Edition"
    const val LONG_ARTIST = "Earth, Wind & Fire, The Emotions, Kool & The Gang"
    const val LONG_REQUESTER = "Supercalifragilistic_2025"

    /** Made-up album art: a two-colour gradient with a soft disc. */
    class FakeArt : AutoCloseable {
        private val palettes = listOf(
            Color(0x7C3AED) to Color(0xEC4899), Color(0xF97316) to Color(0xDC2626), Color(0x06B6D4) to Color(0x2563EB),
            Color(0x84CC16) to Color(0x0D9488), Color(0xFACC15) to Color(0xF43F5E), Color(0x312E81) to Color(0x0EA5E9),
            Color(0xA855F7) to Color(0x14B8A6), Color(0xFB7185) to Color(0xF59E0B), Color(0x1E293B) to Color(0x6366F1),
            Color(0x10B981) to Color(0x3B82F6),
        )
        private val images = palettes.mapIndexed { i, (a, b) -> png(a, b, i) }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/art/") { exchange ->
                val index = exchange.requestURI.path.substringAfterLast('/').substringBefore('.').toIntOrNull()
                val bytes = index?.let { images.getOrNull(it) }
                if (bytes == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }

        fun url(index: Int) = "http://127.0.0.1:${server.address.port}/art/$index.png"

        override fun close() = server.stop(0)

        private fun png(a: Color, b: Color, seed: Int): ByteArray {
            val size = 320
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, a, size.toFloat(), size.toFloat(), b)
            g.fillRect(0, 0, size, size)
            g.color = Color(255, 255, 255, 46)
            val d = 150 + (seed * 37) % 120
            g.fillOval((seed * 53) % 170, (seed * 71) % 170, d, d)
            g.color = Color(0, 0, 0, 40)
            g.fillOval(200 - (seed * 29) % 120, 190 - (seed * 43) % 110, 110, 110)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
    }

    private fun trackJson(id: String, name: String, artist: String, ms: Long, art: String) =
        """{"id":"$id","uri":"spotify:track:$id","name":"$name","artists":["$artist"],"durationMs":$ms,"explicit":false,"albumArtUrl":"$art"}"""

    private fun requestJson(id: String, track: String, who: String, source: RequestSource, payment: String?, minutesAgo: Long) =
        """{"id":"$id","track":$track,"requester":{"displayName":"$who","login":"${who.lowercase()}"},"source":"$source",""" +
            """"payment":${payment ?: "null"},"createdAtEpochMs":${System.currentTimeMillis() - minutesAgo * 60_000},"sourceRef":null}"""

    /**
     * A streamer mid-stream: Spotify logged in and playing, six requests waiting, two played, a Log of today. With
     * [longNames] one more request has the longest title, artists and requester the app meets.
     */
    fun write(dir: Path, art: FakeArt, darkMode: DarkMode = DarkMode.Dark, longNames: Boolean = false) {
        JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }
            .write(Settings(spotifyClientId = "0123456789abcdef0123456789abcdef", darkMode = darkMode, welcomeDone = true))
        val queue = buildList {
            add(requestJson("r1", trackJson("t1", "Levitating", "Dua Lipa", 203_000, art.url(1)), "PixelPanda", RequestSource.BITS, """{"type":"bits","count":500}""", 9))
            if (longNames) {
                add(requestJson("rl", trackJson("tl", LONG_TITLE, LONG_ARTIST, 3_754_000, art.url(7)), LONG_REQUESTER, RequestSource.STREAMELEMENTS, """{"type":"money","amountCents":123456,"currency":"EUR"}""", 8))
            }
            add(requestJson("r2", trackJson("t2", "Mr. Brightside", "The Killers", 222_000, art.url(2)), "kaasboer", RequestSource.DONOLINK, """{"type":"money","amountCents":500,"currency":"EUR"}""", 7))
            add(requestJson("r3", trackJson("t3", "Dreams", "Fleetwood Mac", 257_000, art.url(3)), "lofi_lisa", RequestSource.CHANNEL_POINTS, null, 6))
            add(requestJson("r4", trackJson("t4", "Take On Me", "a-ha", 225_000, art.url(4)), "nachtuil", RequestSource.CHAT, null, 4))
            add(requestJson("r5", trackJson("t5", "September", "Earth, Wind & Fire", 215_000, art.url(5)), "GamerGrietje", RequestSource.CHAT, null, 3))
            add(requestJson("r6", trackJson("t6", "Bohemian Rhapsody", "Queen", 354_000, art.url(6)), "SpeedrunSander", RequestSource.STREAMELEMENTS, """{"type":"money","amountCents":250,"currency":"EUR"}""", 1))
        }
        val history = listOf(
            """{"request":${requestJson("h1", trackJson("t7", "Africa", "TOTO", 295_000, art.url(7)), "Bassbeer", RequestSource.CHAT, null, 30)},"playedAtEpochMs":${System.currentTimeMillis() - 20 * 60_000}}""",
            """{"request":${requestJson("h2", trackJson("t8", "Feel Good Inc.", "Gorillaz", 222_000, art.url(8)), "kaasboer", RequestSource.BITS, """{"type":"bits","count":200}""", 26)},"playedAtEpochMs":${System.currentTimeMillis() - 15 * 60_000}}""",
        )
        dir.resolve("queue.json").writeText("""{"queue":{"items":[${queue.joinToString(",")}]},"handedOver":null,"playing":null,"history":[${history.joinToString(",")}]}""")

        // Today's Log: a stream went live, requests came in (one turned away), Spotify was briefly gone.
        val now = System.currentTimeMillis()
        fun ago(minutes: Long) = now - minutes * 60_000
        fun track(id: String, name: String, artist: String, image: Int) =
            Track(id, "spotify:track:$id", name, listOf(artist), durationMs = 200_000, explicit = false, albumArtUrl = art.url(image))
        val records = buildList {
            add(LogRecord.StreamChanged("stream:${ago(48)}", ago(48), LogStreamChange.Live))
            add(LogRecord.ConnectionChanged("conn:spotify:${ago(41)}", ago(41), LogService.Spotify, LogConnectionState.Lost))
            add(LogRecord.ConnectionChanged("conn:spotify:${ago(41)}", ago(40), LogService.Spotify, LogConnectionState.Restored))
            fun request(id: String, minutes: Long, source: RequestSource, who: String, payment: Payment?, t: Track, steps: List<LogStep>) {
                add(LogRecord.RequestReceived(id, ago(minutes), source, Requester(who, who.lowercase()), "https://open.spotify.com/track/${t.id}", payment))
                add(LogRecord.RequestStep(id, ago(minutes), LogStep.Resolved(t)))
                steps.forEachIndexed { i, step -> add(LogRecord.RequestStep(id, ago(minutes) + i * 1_000, step)) }
            }
            request("h1", 30, RequestSource.CHAT, "Bassbeer", null, track("t7", "Africa", "TOTO", 7), listOf(LogStep.Queued(1), LogStep.HandedOver, LogStep.Played))
            request("h2", 26, RequestSource.BITS, "kaasboer", Payment.Bits(200), track("t8", "Feel Good Inc.", "Gorillaz", 8), listOf(LogStep.Queued(1), LogStep.HandedOver, LogStep.Played))
            request("x1", 12, RequestSource.CHAT, "nachtuil", null, track("t2", "Mr. Brightside", "The Killers", 2), listOf(LogStep.Rejected(RejectReason.Duplicate, listed = false)))
            request("r1", 9, RequestSource.BITS, "PixelPanda", Payment.Bits(500), track("t1", "Levitating", "Dua Lipa", 1), listOf(LogStep.Queued(1)))
            if (longNames) {
                request("rl", 8, RequestSource.STREAMELEMENTS, LONG_REQUESTER, Payment.Money(123456, "EUR"), track("tl", LONG_TITLE, LONG_ARTIST, 7), listOf(LogStep.Queued(2)))
            }
            request("r2", 7, RequestSource.DONOLINK, "kaasboer", Payment.Money(500, "EUR"), track("t2", "Mr. Brightside", "The Killers", 2), listOf(LogStep.Queued(2)))
            request("r3", 6, RequestSource.CHANNEL_POINTS, "lofi_lisa", null, track("t3", "Dreams", "Fleetwood Mac", 3), listOf(LogStep.Queued(3), LogStep.Fulfilled))
            request("r4", 4, RequestSource.CHAT, "nachtuil", null, track("t4", "Take On Me", "a-ha", 4), listOf(LogStep.Queued(4)))
        }
        val logDir = dir.resolve("log").createDirectories()
        logDir.resolve("${LocalDate.now(ZoneId.systemDefault())}.jsonl")
            .writeText(records.joinToString("\n", postfix = "\n") { AppJson.instance.encodeToString(LogRecord.serializer(), it) })
    }

    /**
     * Spotify as the app sees it while [nowPlaying] plays (by M83, or [LONG_ARTIST] with a long title), with two tracks
     * queued in Spotify itself.
     */
    fun spotify(art: FakeArt, nowPlaying: String = "Midnight City", nowPlayingArtist: String = "M83") = MockEngine { request ->
        val json = headersOf("Content-Type", "application/json")
        fun dto(id: String, name: String, artist: String, ms: Long, image: Int) =
            """{"id":"$id","uri":"spotify:track:$id","name":"$name","type":"track","duration_ms":$ms,"explicit":false,""" +
                """"artists":[{"id":"a$id","name":"$artist"}],"album":{"images":[{"url":"${art.url(image)}","width":300,"height":300}]},"is_playable":true}"""
        val path = request.url.encodedPath
        when {
            request.url.host == "accounts.spotify.com" -> respond("""{"access_token":"sp","token_type":"Bearer","expires_in":3600}""", HttpStatusCode.OK, json)
            request.url.host != "api.spotify.com" -> respondError(HttpStatusCode.ServiceUnavailable)
            path == "/v1/me/player/currently-playing" ->
                respond("""{"progress_ms":97000,"is_playing":true,"item":${dto("np", nowPlaying, nowPlayingArtist, 243_000, 0)}}""", HttpStatusCode.OK, json)
            path == "/v1/me/player/queue" ->
                respond("""{"queue":[${dto("q1", "Electric Feel", "MGMT", 229_000, 9)},${dto("q2", "Breezeblocks", "alt-J", 227_000, 5)}]}""", HttpStatusCode.OK, json)
            path == "/v1/me" -> respond("""{"product":"premium"}""", HttpStatusCode.OK, json)
            path == "/v1/me/player/devices" -> respond("""{"devices":[{"id":"d1","is_active":true,"name":"Streaming-pc","type":"Computer"}]}""", HttpStatusCode.OK, json)
            path == "/v1/me/player/recently-played" -> respond("""{"items":[]}""", HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }
}
