package nl.diments.requestify.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.AppGraph
import nl.diments.requestify.MinimumWindowSize
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.health.DonationProblem
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.stream.GateStatus
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.about_open_wizard
import nl.diments.requestify.resources.blacklist_tab_artists
import nl.diments.requestify.resources.connection_all_connected
import nl.diments.requestify.resources.connection_nothing_connected
import nl.diments.requestify.resources.donations_none
import nl.diments.requestify.resources.home_played_tab
import nl.diments.requestify.resources.home_rejected_tab
import nl.diments.requestify.resources.log_filter_problems
import nl.diments.requestify.resources.nav_add
import nl.diments.requestify.resources.now_playing_block
import nl.diments.requestify.resources.now_playing_pause
import nl.diments.requestify.resources.now_playing_previous
import nl.diments.requestify.resources.now_playing_skip
import nl.diments.requestify.resources.requests_open
import nl.diments.requestify.resources.rules_increase
import nl.diments.requestify.resources.settings_theme_dark
import nl.diments.requestify.resources.setup_logout
import nl.diments.requestify.resources.setup_open_dashboard
import nl.diments.requestify.resources.twitch_login
import nl.diments.requestify.resources.wizard_lets_go
import nl.diments.requestify.resources.wizard_later
import nl.diments.requestify.resources.wizard_open
import nl.diments.requestify.resources.wizard_skip
import nl.diments.requestify.ui.LayoutChecks.clippedTexts
import nl.diments.requestify.ui.LayoutChecks.inScrollingContainer
import nl.diments.requestify.ui.LayoutChecks.wholeInWindow
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme
import nl.diments.requestify.ui.window.RequestsToggle
import nl.diments.requestify.ui.window.TitleBar
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The guard that keeps every screen fitting: each screen and each welcome-wizard card, in English and Dutch, at the
 * smallest window, the default one and a maximized Full HD one, with the longest names the app meets. Nothing may be
 * cut off (text that ends in "…" is shortened on purpose and passes), and each screen's main actions must show whole,
 * or be scrolled to whole. A failure lists every problem at once, with the screen, the size and the language.
 */
@OptIn(ExperimentalTestApi::class)
class LayoutGuardTest {
    private data class Size(val name: String, val width: Int, val height: Int)

    private val sizes = listOf(
        Size("smallest", MinimumWindowSize.width.value.toInt(), MinimumWindowSize.height.value.toInt()),
        Size("default", 1180, 760),
        Size("Full HD", 1920, 1080),
    )
    private val languages = listOf("en-US", "nl-NL")

    private fun inLanguage(tag: String, block: () -> Unit) {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(tag))
        try { block() } finally { Locale.setDefault(saved) }
    }

    private fun text(resource: StringResource, vararg args: Any): String = runBlocking { getString(resource, *args) }

    /** A control with [resource] (formatted with [args]) as its text that takes clicks. */
    private fun button(resource: StringResource, vararg args: Any): Pair<String, SemanticsMatcher> =
        text(resource, *args).let { it to (hasText(it) and hasClickAction()) }

    /** A control with [resource] as its content description (an icon button) that takes clicks. */
    private fun icon(resource: StringResource): Pair<String, SemanticsMatcher> =
        text(resource).let { it to (hasContentDescription(it) and hasClickAction()) }

    /** A control whose content description holds [resource] formatted without its argument ("Increase …"). */
    private fun iconWith(resource: StringResource): Pair<String, SemanticsMatcher> {
        val fixed = text(resource, "").trim()
        return fixed to (
            SemanticsMatcher("content description holds \"$fixed\"") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { fixed in it } == true
            } and hasClickAction()
            )
    }

    private val field: Pair<String, SemanticsMatcher> = "a text field" to hasSetTextAction()

    /** What every screen must offer: the rail's Add button and destinations, the status chip and the requests toggle. */
    private fun everywhere(chip: StringResource): List<Pair<String, SemanticsMatcher>> =
        listOf(icon(Res.string.nav_add), button(chip), button(Res.string.requests_open)) +
            Destination.entries.map { button(it.label) }

    private fun keyActions(destination: Destination): List<Pair<String, SemanticsMatcher>> = when (destination) {
        Destination.Home -> listOf(
            field,
            button(Res.string.home_played_tab),
            button(Res.string.home_rejected_tab),
            icon(Res.string.now_playing_previous),
            icon(Res.string.now_playing_pause),
            icon(Res.string.now_playing_skip),
            icon(Res.string.now_playing_block),
        )
        Destination.Connections -> listOf(
            button(Res.string.setup_open_dashboard),
            button(Res.string.setup_logout),
            button(Res.string.twitch_login),
            button(Res.string.donations_none),
        )
        Destination.Rules -> listOf(iconWith(Res.string.rules_increase))
        Destination.Blacklist -> listOf(field, button(Res.string.blacklist_tab_artists, 1))
        Destination.Log -> listOf(field, button(Res.string.log_filter_problems))
        Destination.Settings -> listOf(button(Res.string.settings_theme_dark), button(Res.string.about_open_wizard))
    }

    /** The [actions] that are missing, cut off, or out of view even after scrolling to them. */
    private fun ComposeUiTest.unreachable(actions: List<Pair<String, SemanticsMatcher>>): List<String> {
        val window = onRoot().fetchSemanticsNode().boundsInRoot
        return actions.mapNotNull { (name, matcher) ->
            val found = onAllNodes(matcher).fetchSemanticsNodes()
            when {
                found.isEmpty() -> "\"$name\" is missing"
                found.first().inScrollingContainer() -> {
                    onAllNodes(matcher)[0].performScrollTo()
                    waitForIdle()
                    if (wholeInWindow(onAllNodes(matcher).fetchSemanticsNodes().first(), window)) null else "\"$name\" cannot be scrolled into view whole"
                }
                wholeInWindow(found.first(), window) -> null
                else -> "\"$name\" is cut off or outside the window"
            }
        }
    }

    private fun ComposeUiTest.problemsHere(where: String, actions: List<Pair<String, SemanticsMatcher>>): List<String> {
        val window = onRoot().fetchSemanticsNode().boundsInRoot
        return (clippedTexts(window) + unreachable(actions)).map { "$where: $it" }
    }

    @Test
    fun everyScreenFitsAtEverySizeInBothLanguages() {
        val problems = mutableListOf<String>()
        LiveFixture.FakeArt().use { art ->
            for (tag in languages) inLanguage(tag) {
                for (size in sizes) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val dir = createTempDirectory("requestify-layout-guard")
                    LiveFixture.write(dir, art, longNames = true)
                    try {
                        runDesktopComposeUiTest(width = size.width, height = size.height) {
                            val secrets = InMemorySecretStore().apply { put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "r1") }
                            val graph = AppGraph(
                                AppPaths(dir, updatesDir = dir.resolve("updates")), secrets,
                                LiveFixture.spotify(art, LiveFixture.LONG_TITLE, LiveFixture.LONG_ARTIST), scope,
                            )
                            graph.blacklist.addArtist("a1", LiveFixture.LONG_ARTIST, LiveFixture.LONG_REQUESTER, 0L)
                            graph.start()
                            var open by mutableStateOf<Destination?>(Destination.Home)
                            setContent { AppScaffold(graph, openOn = open) }
                            waitUntil(timeoutMillis = 15_000) { graph.home.state.value.nowPlaying?.track != null }
                            waitForIdle()
                            for (destination in Destination.entries) {
                                open = destination
                                waitForIdle()
                                val where = "${destination.name}, ${size.name} ${size.width}×${size.height}, $tag"
                                problems += problemsHere(where, everywhere(Res.string.connection_all_connected) + keyActions(destination))
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
        assertTrue(problems.isEmpty(), "${problems.size} layout problems:\n" + problems.joinToString("\n"))
    }

    @Test
    fun everyWizardCardFitsAtEverySizeInBothLanguages() {
        val problems = mutableListOf<String>()
        for (tag in languages) inLanguage(tag) {
            for (size in sizes) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val dir = createTempDirectory("requestify-layout-guard")
                try {
                    runDesktopComposeUiTest(width = size.width, height = size.height) {
                        val graph = AppGraph(
                            AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                            MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope, welcomeWizard = true,
                        )
                        setContent { AppScaffold(graph) }
                        fun card(name: String, vararg actions: Pair<String, SemanticsMatcher>) {
                            waitForIdle()
                            val chip = listOf(button(Res.string.connection_nothing_connected), button(Res.string.requests_open))
                            problems += problemsHere("wizard $name, ${size.name} ${size.width}×${size.height}, $tag", chip + actions)
                        }
                        card("welcome", button(Res.string.wizard_lets_go), button(Res.string.wizard_skip))
                        graph.welcome.begin()
                        card("Spotify", button(Res.string.wizard_later), button(Res.string.setup_open_dashboard))
                        graph.welcome.later()
                        card("Twitch", button(Res.string.wizard_later), button(Res.string.twitch_login))
                        graph.welcome.later()
                        card("donations", button(Res.string.wizard_later), button(Res.string.donations_none))
                        graph.welcome.later()
                        card("done", button(Res.string.wizard_open))
                    }
                } finally {
                    scope.cancel()
                    dir.deleteTestDir()
                }
            }
        }
        assertTrue(problems.isEmpty(), "${problems.size} layout problems:\n" + problems.joinToString("\n"))
    }

    /**
     * The title bar at the smallest window, with the room Windows' caption buttons take: every status the chip can
     * show beside every label of the requests toggle. The toggle always shows whole; a long status may end in "…".
     */
    @Test
    fun theTitleBarFitsTheSmallestWindowWithEveryStatus() {
        val statuses: List<ConnectionProblem?> = listOf(
            null,
            ConnectionProblem.Spotify(SpotifyHealth.RateLimited(0)),
            ConnectionProblem.Spotify(SpotifyHealth.NoActiveDevice),
            ConnectionProblem.Spotify(SpotifyHealth.Unreachable("down")),
            ConnectionProblem.Spotify(SpotifyHealth.PremiumRequired),
            ConnectionProblem.TwitchDisconnected,
            ConnectionProblem.TwitchBotLoginNeeded,
            ConnectionProblem.TwitchBotBlocked("followers only"),
            ConnectionProblem.RewardRemoved,
            ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, daysLeft = 13),
            ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, daysLeft = 0),
            ConnectionProblem.Donation(DonationSourceKind.StreamElements, DonationProblem.KeyRefused),
            ConnectionProblem.Donation(DonationSourceKind.StreamElements, DonationProblem.Disconnected),
        )
        val gates = listOf(true, false).flatMap { live -> listOf(true, false).map { open -> GateStatus(open, twitch = true, live = live, manual = false, clearAtEpochMs = null, clearPostponed = false) } }
        val problems = mutableListOf<String>()
        for (tag in languages) inLanguage(tag) {
            runDesktopComposeUiTest(width = MinimumWindowSize.width.value.toInt(), height = 120) {
                var status by mutableStateOf<ConnectionProblem?>(null)
                var gate by mutableStateOf(gates.first())
                setContent {
                    RequestifyTheme(BrandSeed, dark = true) {
                        TitleBar(
                            problems = listOfNotNull(status),
                            captionInset = WindowsCaptionButtonsWidth,
                            requestsSwitch = { RequestsToggle(gate, onToggle = {}, modifier = Modifier.testTag("toggle")) },
                        )
                    }
                }
                for (s in statuses) for (g in gates) {
                    status = s
                    gate = g
                    waitForIdle()
                    val window = onRoot().fetchSemanticsNode().boundsInRoot
                    val where = "title bar with ${s ?: "no problem"}, ${if (g.live) "live" else "offline"} ${if (g.open) "open" else "closed"}, $tag"
                    problems += clippedTexts(window).map { "$where: $it" }
                    val toggle = onNodeWithTag("toggle", useUnmergedTree = true).fetchSemanticsNode()
                    if (!wholeInWindow(toggle, window) || toggle.boundsInRoot.right > window.right - WindowsCaptionButtonsWidth.value) {
                        problems += "$where: the requests toggle is cut off or under the caption buttons (${toggle.boundsInRoot})"
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "${problems.size} layout problems:\n" + problems.joinToString("\n"))
    }

    private companion object {
        /** Windows 11's minimize, maximize and close buttons at 100 %. */
        val WindowsCaptionButtonsWidth = 141.dp
    }
}
