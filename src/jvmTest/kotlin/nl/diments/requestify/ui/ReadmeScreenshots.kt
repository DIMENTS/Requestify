package nl.diments.requestify.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.AppGraph
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
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.storage.AppJson
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.deleteTestDir

/**
 * The screenshots in the README (`docs/images/nl/`, `docs/images/en/`), made offscreen with made-up requests, requesters
 * and album art (colour gradients: no real covers). Runs only when `REQUESTIFY_SCREENSHOTS` names the folder to write
 * to: `REQUESTIFY_SCREENSHOTS=docs/images .\gradlew.bat jvmTest --tests "*ReadmeScreenshots*" --rerun`.
 */
@OptIn(ExperimentalTestApi::class)
class ReadmeScreenshots {
    private val out: Path? = System.getenv("REQUESTIFY_SCREENSHOTS")?.takeIf { it.isNotBlank() }?.let { Path.of(it).createDirectories() }

    /** The language being captured: `nl` or `en`, also the subfolder of [out]. */
    private var language = "nl"

    private fun ComposeUiTest.capture(name: String) {
        val dir = out?.resolve(language)?.createDirectories() ?: return
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", dir.resolve("$name.png").toFile())
    }

    private fun inLanguage(tag: String, block: () -> Unit) {
        val saved = Locale.getDefault()
        language = tag.substringBefore('-')
        Locale.setDefault(Locale.forLanguageTag(tag))
        try { block() } finally { Locale.setDefault(saved) }
    }

    /** Made-up album art: a two-colour gradient with a soft disc, served over local HTTP like Spotify's CDN. */
    private class FakeArt : AutoCloseable {
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

    /** A streamer mid-stream: Spotify logged in and playing, six requests waiting, two played, a Log of today. */
    private fun liveSetup(dir: Path, art: FakeArt) {
        JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }
            .write(Settings(spotifyClientId = "0123456789abcdef0123456789abcdef", darkMode = DarkMode.Dark, welcomeDone = true))
        val queue = listOf(
            requestJson("r1", trackJson("t1", "Levitating", "Dua Lipa", 203_000, art.url(1)), "PixelPanda", RequestSource.BITS, """{"type":"bits","count":500}""", 9),
            requestJson("r2", trackJson("t2", "Mr. Brightside", "The Killers", 222_000, art.url(2)), "kaasboer", RequestSource.DONOLINK, """{"type":"money","amountCents":500,"currency":"EUR"}""", 7),
            requestJson("r3", trackJson("t3", "Dreams", "Fleetwood Mac", 257_000, art.url(3)), "lofi_lisa", RequestSource.CHANNEL_POINTS, null, 6),
            requestJson("r4", trackJson("t4", "Take On Me", "a-ha", 225_000, art.url(4)), "nachtuil", RequestSource.CHAT, null, 4),
            requestJson("r5", trackJson("t5", "September", "Earth, Wind & Fire", 215_000, art.url(5)), "GamerGrietje", RequestSource.CHAT, null, 3),
            requestJson("r6", trackJson("t6", "Bohemian Rhapsody", "Queen", 354_000, art.url(6)), "SpeedrunSander", RequestSource.STREAMELEMENTS, """{"type":"money","amountCents":250,"currency":"EUR"}""", 1),
        )
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
            request("r2", 7, RequestSource.DONOLINK, "kaasboer", Payment.Money(500, "EUR"), track("t2", "Mr. Brightside", "The Killers", 2), listOf(LogStep.Queued(2)))
            request("r3", 6, RequestSource.CHANNEL_POINTS, "lofi_lisa", null, track("t3", "Dreams", "Fleetwood Mac", 3), listOf(LogStep.Queued(3), LogStep.Fulfilled))
            request("r4", 4, RequestSource.CHAT, "nachtuil", null, track("t4", "Take On Me", "a-ha", 4), listOf(LogStep.Queued(4)))
        }
        val logDir = dir.resolve("log").createDirectories()
        logDir.resolve("${LocalDate.now(ZoneId.systemDefault())}.jsonl")
            .writeText(records.joinToString("\n", postfix = "\n") { AppJson.instance.encodeToString(LogRecord.serializer(), it) })
    }

    /** Spotify as the app sees it while "Midnight City" plays, with two tracks queued in Spotify itself. */
    private fun spotify(art: FakeArt) = MockEngine { request ->
        val json = headersOf("Content-Type", "application/json")
        fun dto(id: String, name: String, artist: String, ms: Long, image: Int) =
            """{"id":"$id","uri":"spotify:track:$id","name":"$name","type":"track","duration_ms":$ms,"explicit":false,""" +
                """"artists":[{"id":"a$id","name":"$artist"}],"album":{"images":[{"url":"${art.url(image)}","width":300,"height":300}]},"is_playable":true}"""
        val path = request.url.encodedPath
        when {
            request.url.host == "accounts.spotify.com" -> respond("""{"access_token":"sp","token_type":"Bearer","expires_in":3600}""", HttpStatusCode.OK, json)
            request.url.host != "api.spotify.com" -> respondError(HttpStatusCode.ServiceUnavailable)
            path == "/v1/me/player/currently-playing" ->
                respond("""{"progress_ms":97000,"is_playing":true,"item":${dto("np", "Midnight City", "M83", 243_000, 0)}}""", HttpStatusCode.OK, json)
            path == "/v1/me/player/queue" ->
                respond("""{"queue":[${dto("q1", "Electric Feel", "MGMT", 229_000, 9)},${dto("q2", "Breezeblocks", "alt-J", 227_000, 5)}]}""", HttpStatusCode.OK, json)
            path == "/v1/me" -> respond("""{"product":"premium"}""", HttpStatusCode.OK, json)
            path == "/v1/me/player/devices" -> respond("""{"devices":[{"id":"d1","is_active":true,"name":"Streaming-pc","type":"Computer"}]}""", HttpStatusCode.OK, json)
            path == "/v1/me/player/recently-played" -> respond("""{"items":[]}""", HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    @Test
    fun readmeScreens() {
        if (out == null) return
        for (tag in listOf("nl-NL", "en-US")) inLanguage(tag) {
            FakeArt().use { art ->
                // Home and the Log of a streamer mid-stream.
                for ((name, screen) in listOf("home" to Destination.Home, "log" to Destination.Log)) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val dir = createTempDirectory("requestify-readme")
                    liveSetup(dir, art)
                    try {
                        runDesktopComposeUiTest(width = 1280, height = 800) {
                            val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
                            val graph = AppGraph(AppPaths(dir, updatesDir = dir.resolve("updates")), secrets, spotify(art), scope)
                            graph.start()
                            setContent { AppScaffold(graph, openOn = screen) }
                            waitUntil(timeoutMillis = 15_000) { graph.home.state.value.nowPlaying?.track != null }
                            Thread.sleep(2_500) // the album art arrives and the colours follow it
                            waitForIdle()
                            capture(name)
                            graph.stop()
                        }
                    } finally {
                        scope.cancel()
                        dir.deleteTestDir()
                    }
                }
                // A new streamer: the welcome wizard, then the Connections page.
                for ((name, wizard) in listOf("wizard" to true, "connections" to false)) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val dir = createTempDirectory("requestify-readme")
                    JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }.write(Settings(darkMode = DarkMode.Dark))
                    try {
                        runDesktopComposeUiTest(width = 1280, height = 800) {
                            val graph = AppGraph(
                                AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                                MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = wizard,
                            )
                            setContent { AppScaffold(graph, openOn = if (wizard) null else Destination.Connections) }
                            Thread.sleep(1_200)
                            waitForIdle()
                            capture(name)
                        }
                    } finally {
                        scope.cancel()
                        dir.deleteTestDir()
                    }
                }
                // The wizard celebrating a connection (paused mid-confetti).
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-readme")
                JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }.write(Settings(darkMode = DarkMode.Dark))
                val spotifyConnected = MutableStateFlow(false)
                try {
                    runDesktopComposeUiTest(width = 1280, height = 800) {
                        val graph = AppGraph(AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)
                        val vm = nl.diments.requestify.ui.welcome.WelcomeWizardViewModel(graph.settings, spotifyConnected, MutableStateFlow(false), MutableStateFlow(false), scope)
                        vm.begin()
                        mainClock.autoAdvance = false
                        setContent {
                            nl.diments.requestify.ui.theme.RequestifyTheme(nl.diments.requestify.ui.theme.BrandSeed, dark = true) {
                                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                    nl.diments.requestify.ui.welcome.WelcomeWizard(vm, graph.spotifySetup, graph.twitchSetup, graph.donationsSetup)
                                }
                            }
                        }
                        mainClock.advanceTimeBy(1_500)
                        spotifyConnected.value = true
                        mainClock.advanceTimeBy(380)
                        capture("wizard-celebration")
                    }
                } finally {
                    scope.cancel()
                    dir.deleteTestDir()
                }
            }
        }
    }
}
