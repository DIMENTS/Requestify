package nl.diments.requestify.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
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
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.deleteTestDir

/**
 * Every screen and wizard card at the window sizes a streamer meets, for a UI audit. Runs only when `REQUESTIFY_RENDERS`
 * names a folder: `.\gradlew.bat jvmTest --tests "*AuditRenders*" --rerun`. `REQUESTIFY_AUDIT_SET=quick` renders only
 * the minimum, default and a large size in Dutch, dark.
 */
@OptIn(ExperimentalTestApi::class)
class AuditRenders {
    private val out: Path? = System.getenv("REQUESTIFY_RENDERS")?.takeIf { it.isNotBlank() }?.let { Path.of(it).createDirectories() }
    private val quick = System.getenv("REQUESTIFY_AUDIT_SET") == "quick"

    private data class Size(val name: String, val width: Int, val height: Int)

    private val min = Size("min", 720, 640)
    private val mid = Size("mid", 1000, 680)
    private val default = Size("default", 1180, 760)
    private val fhd = Size("fhd", 1920, 1080)
    private val qhd = Size("qhd", 2560, 1440)
    private val wide = Size("wide", 2560, 720)
    private val tall = Size("tall", 720, 1300)

    /** Language, dark, sizes. */
    private val combos: List<Triple<String, Boolean, List<Size>>> = if (quick) {
        listOf(Triple("nl-NL", true, listOf(min, default, fhd)))
    } else {
        listOf(
            Triple("nl-NL", true, listOf(min, mid, default, fhd, qhd, wide, tall)),
            Triple("en-US", true, listOf(min, default, fhd, tall)),
            Triple("nl-NL", false, listOf(min, default, qhd)),
            Triple("en-US", false, listOf(min)),
        )
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = out ?: return
        // The window itself, also while a menu or a dialog is open as a second root.
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", dir.resolve("$name.png").toFile())
    }

    private fun inLanguage(tag: String, block: () -> Unit) {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(tag))
        try { block() } finally { Locale.setDefault(saved) }
    }

    /** A streamer mid-stream, on every screen. */
    @Test
    fun liveScreens() {
        if (out == null) return
        for ((tag, dark, sizes) in combos) inLanguage(tag) {
            LiveFixture.FakeArt().use { art ->
                for (size in sizes) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val dir = createTempDirectory("requestify-audit")
                    LiveFixture.write(dir, art, if (dark) DarkMode.Dark else DarkMode.Light, longNames = true)
                    try {
                        runDesktopComposeUiTest(width = size.width, height = size.height) {
                            val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
                            val graph = AppGraph(
                                AppPaths(dir, updatesDir = dir.resolve("updates")), secrets,
                                LiveFixture.spotify(art, LiveFixture.LONG_TITLE, LiveFixture.LONG_ARTIST), scope,
                            )
                            val now = System.currentTimeMillis()
                            graph.blacklist.addTrack(Track("bt1", "spotify:track:bt1", LiveFixture.LONG_TITLE, listOf(LiveFixture.LONG_ARTIST), 200_000, false, art.url(3)), "kaasboer", now)
                            graph.blacklist.addTrack(Track("bt2", "spotify:track:bt2", "Baby Shark", listOf("Pinkfong"), 90_000, false, art.url(4)), null, now)
                            graph.blacklist.addArtist("a1", "Nickelback", "nachtuil", now)
                            graph.blacklist.addViewer("u1", LiveFixture.LONG_REQUESTER, "DIMENTS", now)
                            graph.start()
                            var open by mutableStateOf<Destination?>(Destination.Home)
                            setContent { AppScaffold(graph, openOn = open) }
                            waitUntil(timeoutMillis = 15_000) { graph.home.state.value.nowPlaying?.isPlaying == true }
                            Thread.sleep(2_000)
                            waitForIdle()
                            val suffix = "${tag.substringBefore('-')}-${if (dark) "dark" else "light"}-${size.name}"
                            for (destination in Destination.entries) {
                                open = destination
                                Thread.sleep(400)
                                waitForIdle()
                                capture("live-${destination.ordinal}-${destination.name.lowercase()}-$suffix")
                            }
                            graph.stop()
                        }
                    } finally {
                        scope.cancel()
                        dir.deleteTestDir()
                    }
                }
            }
        }
    }

    /**
     * The Twitch card connected (an affiliate, the bot a moderator) at the page width of the smallest
     * window (576 dp) and of the default one (760 dp), in both languages.
     */
    @Test
    fun twitchConnected() {
        if (out == null) return
        val streamer = nl.diments.requestify.core.settings.TwitchAccount("42", "supercalifragilistic_2025", "Supercalifragilistic_2025", "affiliate")
        val bot = nl.diments.requestify.core.settings.TwitchAccount("7", "mijnbot", "MijnBot")
        for (tag in listOf("nl-NL", "en-US")) inLanguage(tag) {
            for (width in listOf(576, 760)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-audit")
                try {
                    runDesktopComposeUiTest(width = width, height = 1600) {
                        val p = nl.diments.requestify.ui.setup.TwitchSetupParts(
                            dir, scope, nl.diments.requestify.core.twitch.FakeTwitch(), loggedIn = true, account = streamer, botAccount = bot,
                        )
                        p.twitch.moderatedChannels += "42"
                        kotlinx.coroutines.runBlocking { p.bot.checkModerator() }
                        setContent {
                            nl.diments.requestify.ui.theme.RequestifyTheme(nl.diments.requestify.ui.theme.BrandSeed, dark = true) {
                                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                    nl.diments.requestify.ui.setup.TwitchCard(p.vm, p.botVm)
                                }
                            }
                        }
                        Thread.sleep(500)
                        waitForIdle()
                        capture("twitch-connected-${tag.substringBefore('-')}-$width")
                    }
                } finally {
                    scope.cancel()
                    dir.deleteTestDir()
                }
            }
        }
    }

    /** A new streamer: every wizard card, then the screens with nothing set up. */
    @Test
    fun freshScreens() {
        if (out == null) return
        for ((tag, dark, sizes) in combos) inLanguage(tag) {
            for (size in sizes) {
                val suffix = "${tag.substringBefore('-')}-${if (dark) "dark" else "light"}-${size.name}"
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-audit")
                try {
                    runDesktopComposeUiTest(width = size.width, height = size.height) {
                        val graph = AppGraph(
                            AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = true,
                        )
                        graph.settings.tryUpdate { it.copy(darkMode = if (dark) DarkMode.Dark else DarkMode.Light) }
                        var open by mutableStateOf<Destination?>(null)
                        setContent { AppScaffold(graph, openOn = open) }
                        fun shot(name: String) {
                            Thread.sleep(400)
                            waitForIdle()
                            capture("$name-$suffix")
                        }
                        shot("wizard-1-welcome")
                        graph.welcome.begin()
                        shot("wizard-2-spotify")
                        graph.welcome.later()
                        shot("wizard-3-twitch")
                        graph.welcome.later()
                        shot("wizard-4-donations")
                        graph.welcome.later()
                        shot("wizard-5-done")
                        graph.welcome.finish()
                        for (destination in listOf(Destination.Home, Destination.Connections, Destination.Blacklist, Destination.Log)) {
                            open = destination
                            shot("fresh-${destination.ordinal}-${destination.name.lowercase()}")
                        }
                    }
                } finally {
                    scope.cancel()
                    dir.deleteTestDir()
                }
            }
        }
    }
}
