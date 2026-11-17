package nl.diments.requestify.ui.home

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import kotlin.math.abs
import kotlin.test.assertTrue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlaying
import nl.diments.requestify.core.playback.NowPlayingKind
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class NowPlayingPaneTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    private val midnightCity = Track("t1", "spotify:track:t1", "Midnight City", listOf("M83"), 243_000, false, null)

    /** As on Home: the pane is 320 dp wide. [clock] is the wall time the pane reads. */
    @Composable
    private fun Pane(
        nowPlaying: NowPlaying?,
        clock: () -> Long,
        modifier: Modifier = Modifier.width(320.dp),
        compact: Boolean = false,
    ) {
        RequestifyTheme(BrandSeed, dark = true) {
            NowPlayingPane(
                nowPlaying, onPrevious = {}, onTogglePause = {}, onSkip = {},
                modifier = modifier, compact = compact, clock = clock,
            )
        }
    }

    /** Polled just now, by the test's main clock. */
    private fun ComposeUiTest.polled(progressMs: Long, request: Request? = null) =
        NowPlaying(midnightCity, progressMs, isPlaying = true, request = request, sampledAtEpochMs = mainClock.currentTime)

    @Test
    fun ownMusicWhenNoRequest() = runComposeUiTest {
        setContent { Pane(polled(60_000), clock = { mainClock.currentTime }) }
        onNodeWithText("Midnight City").assertIsDisplayed()
        onNodeWithText("Your own music").assertIsDisplayed()
    }

    /** Spotify's own recently played song: whether it was a request is not known. */
    private val lastPlayedOnSpotify = NowPlaying(
        midnightCity, 0, isPlaying = false, request = null, sampledAtEpochMs = 0L,
        kind = NowPlayingKind.LastPlayed, requesterKnown = false,
    )

    @Test
    fun spotifysRecentlyPlayedSongLeavesTheRequesterRowEmpty() = runComposeUiTest {
        setContent { Pane(lastPlayedOnSpotify, clock = { 0L }) }
        onNodeWithText("Midnight City").assertIsDisplayed()
        onNodeWithText("LAST PLAYED").assertIsDisplayed()
        onNodeWithText("Your own music").assertDoesNotExist()
    }

    @Test
    fun bitsRequestShowsChipAndRequester() = runComposeUiTest {
        val request = Request("r1", midnightCity, Requester("streamfan"), RequestSource.BITS, Payment.Bits(500), createdAtEpochMs = 1L)
        setContent { Pane(polled(60_000, request), clock = { mainClock.currentTime }) }
        onNodeWithText("500 Bits").assertIsDisplayed()
        onNodeWithText("streamfan").assertIsDisplayed()
    }

    @Test
    fun progressRunsOnBetweenPollsOnlyWhilePlaying() = runComposeUiTest {
        var nowPlaying by mutableStateOf(polled(60_000))
        mainClock.autoAdvance = false
        setContent { Pane(nowPlaying, clock = { mainClock.currentTime }) }
        mainClock.advanceTimeBy(5_500) // the position ticks every 250 ms
        onNodeWithText("1:05").assertExists()

        nowPlaying = nowPlaying.copy(progressMs = 90_000, isPlaying = false)
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("1:30").assertExists()
    }

    @Test
    fun progressRunsOnFromWhenSpotifySampledItAndStopsWherePaused() = runComposeUiTest {
        var wallMs = 105_000L
        // Spotify sampled 1:00 five seconds ago (a poll that took a while, or the pane opening later).
        var nowPlaying by mutableStateOf(
            NowPlaying(midnightCity, 60_000, isPlaying = true, request = null, sampledAtEpochMs = 100_000),
        )
        mainClock.autoAdvance = false
        setContent { Pane(nowPlaying, clock = { wallMs }) }
        onNodeWithText("1:05").assertExists()

        wallMs = 108_000
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("1:08").assertExists()

        // Paused where it was; ten seconds later it still shows that, without jumping back to the last poll.
        nowPlaying = NowPlaying(midnightCity, 68_000, isPlaying = false, request = null, sampledAtEpochMs = 108_000)
        wallMs = 118_000
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("1:08").assertExists()
    }

    private val longTitle = "Een heel erg lange titel die zeker over twee regels heen loopt in de kaart"
    private val bitsRequest by lazy {
        Request("r1", midnightCity, Requester("streamfan"), RequestSource.BITS, Payment.Bits(500), createdAtEpochMs = 1L)
    }

    /** The wide card as on Home in a 900×560 window: 320 dp wide, 472 dp high. */
    private fun ComposeUiTest.wideCard(fontScale: Float) {
        val nowPlaying = NowPlaying(
            midnightCity.copy(name = longTitle), 60_000, isPlaying = true, request = bitsRequest, sampledAtEpochMs = 0L,
        )
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Pane(nowPlaying, clock = { 0L }, modifier = Modifier.width(320.dp).height(472.dp))
            }
        }
    }

    @Test
    fun aShortWideCardWithATwoLineTitleKeepsItsControlsInsideIt() = runComposeUiTest {
        wideCard(fontScale = 1f)
        val card = onNodeWithTag(NOW_PLAYING_PANE_TAG).getBoundsInRoot()
        val skip = onNodeWithContentDescription("Skip").getBoundsInRoot()
        val requester = onNodeWithText("streamfan").getBoundsInRoot()

        assertTrue(skip.bottom <= card.bottom, "skip ${skip.bottom} below the card's bottom ${card.bottom}")
        assertTrue(requester.top >= card.top)
    }

    @Test
    fun whenTheDetailsNeedMoreThanTheCardHasTheCardScrollsToTheControls() = runComposeUiTest {
        wideCard(fontScale = 1.6f)
        onNodeWithContentDescription("Skip").performScrollTo()
        val card = onNodeWithTag(NOW_PLAYING_PANE_TAG).getBoundsInRoot()
        val skip = onNodeWithContentDescription("Skip").getBoundsInRoot()

        assertTrue(skip.bottom <= card.bottom && skip.top >= card.top, "skip $skip outside the card $card after scrolling")
    }

    /** Every state the card can be in, from the one with the most text to the one with the least. */
    private val everyState by lazy {
        listOf(
            "a request with a two-line title" to
                NowPlaying(midnightCity.copy(name = longTitle), 60_000, isPlaying = true, request = bitsRequest, sampledAtEpochMs = 0L),
            "own music with a one-line title" to NowPlaying(midnightCity, 60_000, isPlaying = true, request = null, sampledAtEpochMs = 0L),
            "paused" to NowPlaying(midnightCity, 60_000, isPlaying = false, request = null, sampledAtEpochMs = 0L),
            "last played" to NowPlaying(
                midnightCity, 60_000, isPlaying = false, request = null, sampledAtEpochMs = 0L, kind = NowPlayingKind.LastPlayed,
            ),
            "last played on Spotify, requester unknown" to lastPlayedOnSpotify,
            "nothing playing" to null,
        )
    }

    private val isProgressBar = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    /** Where the progress bar and each of the three buttons are. */
    private fun ComposeUiTest.controlBounds(): Map<String, DpRect> = mapOf(
        "progress" to onNode(isProgressBar).getBoundsInRoot(),
        "previous" to onNodeWithContentDescription("Previous").getBoundsInRoot(),
        "play/pause" to onNode(hasContentDescription("Pause") or hasContentDescription("Play")).getBoundsInRoot(),
        "skip" to onNodeWithContentDescription("Skip").getBoundsInRoot(),
    )

    /** 100 % and 150 % Windows scaling, and large text: rounding to whole pixels differs at each. */
    private val densities = listOf(Density(1f, 1f), Density(1.5f, 1f), Density(1f, 1.6f))

    /**
     * Shows each of [everyState] in turn in one card of one size, at each of [densities], and checks that no control
     * moved and that the track details always fit the room kept for them (they never reach above it).
     */
    private fun assertControlsKeepTheirPlace(size: Modifier, compact: Boolean) {
        val problems = densities.flatMap { density -> problemsAt(density, size, compact) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private fun problemsAt(density: Density, size: Modifier, compact: Boolean): List<String> {
        val problems = mutableListOf<String>()
        // Room for the 320×640 dp card at 150 %.
        runDesktopComposeUiTest(width = 1200, height = 1200) {
            var nowPlaying by mutableStateOf(everyState.first().second)
            setContent {
                CompositionLocalProvider(LocalDensity provides density) {
                    Pane(nowPlaying, clock = { 0L }, modifier = size, compact = compact)
                }
            }
            val placed = everyState.map { (name, state) ->
                nowPlaying = state
                waitForIdle()
                val block = onNodeWithTag(NOW_PLAYING_DETAILS_TAG, useUnmergedTree = true).fetchSemanticsNode()
                // An empty node (an empty requester row) takes no room; its bounds collapse to the root's corner.
                val contentTop = block.children.filter { it.size.height > 0 }.minOf { it.boundsInRoot.top }
                if (contentTop < block.boundsInRoot.top) {
                    problems += "$density, $name: the details reach ${block.boundsInRoot.top - contentTop} px above their room"
                }
                name to controlBounds()
            }
            val (firstName, first) = placed.first()
            for ((name, bounds) in placed.drop(1)) {
                for ((control, rect) in bounds) {
                    val expected = first.getValue(control)
                    val off = listOf(rect.left - expected.left, rect.top - expected.top, rect.right - expected.right, rect.bottom - expected.bottom)
                        .maxOf { abs(it.value) }
                    if (off > 0.5f) problems += "$density: the $control is at $rect for $name but at $expected for $firstName"
                }
            }
        }
        return problems
    }

    @Test
    fun controlsKeepTheirPlaceInEveryStateWide() =
        assertControlsKeepTheirPlace(Modifier.width(320.dp).height(640.dp), compact = false)

    @Test
    fun controlsKeepTheirPlaceInEveryStateNarrow() = assertControlsKeepTheirPlace(Modifier.width(700.dp), compact = true)

    @Test
    fun buttonsArePreviousPlayNextInOrder() {
        for (compact in listOf(false, true)) {
            runComposeUiTest {
                setContent { Pane(polled(60_000), clock = { mainClock.currentTime }, compact = compact) }
                val previous = onNodeWithContentDescription("Previous").getBoundsInRoot()
                val playPause = onNodeWithContentDescription("Pause").getBoundsInRoot()
                val skip = onNodeWithContentDescription("Skip").getBoundsInRoot()

                val order = "compact = $compact: previous $previous, pause $playPause, skip $skip"
                assertTrue(previous.right <= playPause.left && playPause.right <= skip.left, order)
            }
        }
    }

    @Test
    fun labelFollowsTheState() = runComposeUiTest {
        var nowPlaying by mutableStateOf<NowPlaying?>(null)
        setContent { Pane(nowPlaying, clock = { mainClock.currentTime }) }
        onNodeWithText("NOW PLAYING").assertIsDisplayed()

        nowPlaying = polled(60_000)
        waitForIdle()
        onNodeWithText("NOW PLAYING").assertIsDisplayed()

        nowPlaying = polled(60_000).copy(isPlaying = false)
        waitForIdle()
        onNodeWithText("PAUSED").assertIsDisplayed()
        onNodeWithText("NOW PLAYING").assertDoesNotExist()

        nowPlaying = polled(60_000).copy(isPlaying = false, kind = NowPlayingKind.LastPlayed)
        waitForIdle()
        onNodeWithText("LAST PLAYED").assertIsDisplayed()
        onNodeWithText("PAUSED").assertDoesNotExist()
    }

    @Test
    fun idleDisablesControls() = runComposeUiTest {
        var nowPlaying by mutableStateOf<NowPlaying?>(null)
        setContent { Pane(nowPlaying, clock = { 0L }) }
        for (button in listOf("Previous", "Play", "Skip")) onNodeWithContentDescription(button).assertIsNotEnabled()

        // The last played song can be resumed, and Spotify may still go back or forward on its last device.
        nowPlaying = NowPlaying(midnightCity, 60_000, isPlaying = false, request = null, sampledAtEpochMs = 0L, kind = NowPlayingKind.LastPlayed)
        waitForIdle()
        for (button in listOf("Previous", "Play", "Skip")) onNodeWithContentDescription(button).assertIsEnabled()
    }
}
