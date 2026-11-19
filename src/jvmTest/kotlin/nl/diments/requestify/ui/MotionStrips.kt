package nl.diments.requestify.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlaying
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.ui.blacklist.BlacklistTab
import nl.diments.requestify.ui.log.LogFilter
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.LocalReducedMotion
import nl.diments.requestify.ui.theme.RequestifyTheme
import nl.diments.requestify.ui.window.TitleBar

/**
 * Frame strips of the app's transitions, for a motion review: the clock is paused and stepped, and the frames of one
 * transition are laid side by side with their time under each. Runs only when `REQUESTIFY_RENDERS` names a folder:
 * `.\gradlew.bat jvmTest --tests "*MotionStrips*" --rerun`. `REQUESTIFY_REDUCED_MOTION=1` renders them as with Windows'
 * animation effects off (file names end in `-reduced`).
 */
@OptIn(ExperimentalTestApi::class)
class MotionStrips {
    private val out: Path? = System.getenv("REQUESTIFY_RENDERS")?.takeIf { it.isNotBlank() }?.let { Path.of(it).createDirectories() }
    private val reduced = System.getenv("REQUESTIFY_REDUCED_MOTION") == "1"
    private val suffix = if (reduced) "-reduced" else ""

    /** When each frame is taken, in ms after the change. */
    private val times = listOf(0L, 16, 50, 100, 150, 200, 300, 450, 700, 1_200)

    /** A crop of the window, in px: left, top, width, height. */
    private data class Crop(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun ComposeUiTest.frame(crop: Crop?): BufferedImage {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val c = crop ?: return image
        return image.getSubimage(c.x, c.y, minOf(c.w, image.width - c.x), minOf(c.h, image.height - c.y))
    }

    /** One frame before [change], then one per [times], side by side and scaled by [scale], with their times. */
    private fun ComposeUiTest.strip(name: String, crop: Crop? = null, scale: Double = 0.5, change: () -> Unit) {
        val dir = out ?: return
        val frames = mutableListOf("before" to frame(crop))
        change()
        var at = 0L
        for (t in times) {
            mainClock.advanceTimeBy(t - at)
            at = t
            frames += "$t ms" to frame(crop)
        }
        val w = (frames.first().second.width * scale).toInt()
        val h = (frames.first().second.height * scale).toInt()
        val label = 22
        val sheet = BufferedImage(frames.size * (w + 4), h + label, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 40)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        frames.forEachIndexed { i, (time, image) ->
            g.drawImage(image, i * (w + 4), 0, w, h, null)
            g.color = Color.WHITE
            g.drawString(time, i * (w + 4) + 6, h + 16)
        }
        g.dispose()
        ImageIO.write(sheet, "png", dir.resolve("$name$suffix.png").toFile())
    }

    private fun dutch(block: () -> Unit) {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("nl-NL"))
        try { block() } finally { Locale.setDefault(saved) }
    }

    /** The whole app mid-stream: rail, tabs, filters, the queue, the requests toggle, a new song. */
    @Test
    fun appTransitions() {
        if (out == null) return
        dutch {
            LiveFixture.FakeArt().use { art ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-motion")
                LiveFixture.write(dir, art)
                var playing = Triple("np", "Midnight City", "M83")
                try {
                    runDesktopComposeUiTest(width = 1180, height = 760) {
                        val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
                        val graph = AppGraph(AppPaths(dir, updatesDir = dir.resolve("updates")), secrets, LiveFixture.spotify(art) { playing }, scope)
                        val now = System.currentTimeMillis()
                        graph.blacklist.addTrack(Track("bt1", "spotify:track:bt1", "Baby Shark", listOf("Pinkfong"), 90_000, false, art.url(4)), null, now)
                        graph.blacklist.addArtist("a1", "Nickelback", "nachtuil", now)
                        graph.start()
                        var open by mutableStateOf<Destination?>(Destination.Home)
                        setContent { CompositionLocalProvider(LocalReducedMotion provides reduced) { AppScaffold(graph, openOn = open) } }
                        waitUntil(timeoutMillis = 15_000) { graph.home.state.value.nowPlaying?.track != null }
                        Thread.sleep(2_000)
                        waitForIdle()
                        mainClock.autoAdvance = false
                        mainClock.advanceTimeBy(2_000)
                        val content = Crop(96, 40, 1084, 720)

                        strip("01-rail-home-to-log", content) { open = Destination.Log }
                        mainClock.advanceTimeBy(1_000)
                        strip("02-rail-log-to-settings", content) { open = Destination.Settings }
                        mainClock.advanceTimeBy(1_000)
                        strip("03-rail-settings-to-connections", content) { open = Destination.Connections }
                        mainClock.advanceTimeBy(1_000)
                        strip("04-rail-connections-to-home", content) { open = Destination.Home }
                        mainClock.advanceTimeBy(1_000)

                        val queue = Crop(96, 40, 660, 720)
                        strip("05-queue-tab-to-played", queue) { onNode(hasText("Gespeeld") and hasClickAction()).performClick() }
                        mainClock.advanceTimeBy(1_000)
                        onNode(hasText("Wachtrij", substring = true) and hasClickAction()).performClick()
                        mainClock.advanceTimeBy(1_000)
                        strip("06-queue-row-removed", queue) { graph.home.onRemove("r2") }
                        mainClock.advanceTimeBy(1_000)

                        strip("07-requests-toggle", Crop(900, 0, 280, 40), scale = 1.0) { graph.gate.toggle() }
                        mainClock.advanceTimeBy(1_000)
                        graph.gate.toggle()
                        mainClock.advanceTimeBy(1_000)

                        open = Destination.Blacklist
                        mainClock.advanceTimeBy(1_000)
                        strip("08-blacklist-tab-to-artists", content) { graph.blacklistScreen.onTab(BlacklistTab.Artists) }
                        mainClock.advanceTimeBy(1_000)
                        strip("09-blacklist-row-removed", content) { graph.blacklistScreen.onRemove("a1") }
                        mainClock.advanceTimeBy(1_000)

                        open = Destination.Log
                        mainClock.advanceTimeBy(1_000)
                        strip("10-log-filter-to-problems", content) { graph.logScreen.setFilter(LogFilter.Problems) }
                        mainClock.advanceTimeBy(1_000)
                        strip("11-log-filter-to-all", content) { graph.logScreen.setFilter(LogFilter.All) }
                        mainClock.advanceTimeBy(1_000)
                        val firstRow = graph.logScreen.days.value.first().rows.first().id
                        strip("12-log-details-open", null) { graph.logScreen.select(firstRow) }
                        mainClock.advanceTimeBy(1_000)
                        graph.logScreen.select(null)
                        mainClock.advanceTimeBy(1_000)

                        open = Destination.Home
                        mainClock.advanceTimeBy(1_000)
                        playing = Triple("np2", "Electric Feel", "MGMT")
                        // Wait in real time until the next poll saw the new song, without letting the UI move on.
                        val deadline = System.currentTimeMillis() + 15_000
                        while (graph.home.state.value.nowPlaying?.track?.id != "np2" && System.currentTimeMillis() < deadline) Thread.sleep(100)
                        Thread.sleep(1_000) // the new art arrives
                        strip("13-now-playing-new-song", Crop(740, 40, 440, 720)) { }

                        graph.stop()
                    }
                } finally {
                    scope.cancel()
                    dir.deleteTestDir()
                }
            }
        }
    }

    /** The status chip in the title bar going from "Alles verbonden" to a problem and back. */
    @Test
    fun statusChip() {
        if (out == null) return
        dutch {
            runDesktopComposeUiTest(width = 720, height = 40) {
                var problems by mutableStateOf<List<ConnectionProblem>>(emptyList())
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalReducedMotion provides reduced) {
                        RequestifyTheme(BrandSeed, dark = true) {
                            Surface(color = MaterialTheme.colorScheme.surface) { TitleBar(problems) }
                        }
                    }
                }
                mainClock.advanceTimeBy(500)
                strip("14-status-chip-problem", Crop(100, 0, 420, 40), scale = 1.0) {
                    problems = listOf(ConnectionProblem.Spotify(SpotifyHealth.NoActiveDevice))
                }
                mainClock.advanceTimeBy(1_000)
                strip("15-status-chip-back", Crop(100, 0, 420, 40), scale = 1.0) { problems = emptyList() }
            }
        }
    }

    /** The Twitch card's Commands row opening. */
    @Test
    fun commandsRow() {
        if (out == null) return
        dutch {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val dir = createTempDirectory("requestify-motion")
            try {
                runDesktopComposeUiTest(width = 760, height = 1100) {
                    val streamer = nl.diments.requestify.core.settings.TwitchAccount("42", "diments", "DIMENTS", "affiliate")
                    val p = nl.diments.requestify.ui.setup.TwitchSetupParts(dir, scope, nl.diments.requestify.core.twitch.FakeTwitch(), loggedIn = true, account = streamer)
                    setContent {
                        CompositionLocalProvider(LocalReducedMotion provides reduced) {
                            RequestifyTheme(BrandSeed, dark = true) {
                                Surface(color = MaterialTheme.colorScheme.surface) { nl.diments.requestify.ui.setup.TwitchCard(p.vm, p.botVm) }
                            }
                        }
                    }
                    Thread.sleep(300)
                    waitForIdle()
                    mainClock.autoAdvance = false
                    strip("16-twitch-commands-open", Crop(0, 300, 760, 800)) {
                        onNode(hasText("Commando’s", substring = true) and hasClickAction()).performClick()
                    }
                }
            } finally {
                scope.cancel()
                dir.deleteTestDir()
            }
        }
    }

    /** Now Playing on its own: a new song while paused clocks step, the play/pause icon. */
    @Test
    fun nowPlayingPane() {
        if (out == null) return
        dutch {
            runDesktopComposeUiTest(width = 400, height = 720) {
                val a = Track("t1", "spotify:track:t1", "Midnight City", listOf("M83"), 243_000, false, null)
                val b = Track("t2", "spotify:track:t2", "Een veel langere titel die over twee regels loopt", listOf("Iemand"), 200_000, false, null)
                var np by mutableStateOf(NowPlaying(a, 60_000, isPlaying = true, request = null, sampledAtEpochMs = 0))
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalReducedMotion provides reduced) {
                        RequestifyTheme(BrandSeed, dark = true) {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                nl.diments.requestify.ui.home.NowPlayingPane(np, {}, {}, {}, {}, Modifier.width(400.dp).height(720.dp), clock = { mainClock.currentTime })
                            }
                        }
                    }
                }
                mainClock.advanceTimeBy(1_000)
                strip("17-now-playing-song-change") { np = NowPlaying(b, 0, isPlaying = true, request = null, sampledAtEpochMs = mainClock.currentTime) }
                mainClock.advanceTimeBy(1_000)
                strip("18-now-playing-pause") { np = np.copy(isPlaying = false) }
            }
        }
    }
}
