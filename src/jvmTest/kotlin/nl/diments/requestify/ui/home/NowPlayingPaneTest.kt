package nl.diments.requestify.ui.home

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.test.assertTrue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
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
    private fun Pane(nowPlaying: NowPlaying, clock: () -> Long) {
        RequestifyTheme(BrandSeed, dark = true) {
            NowPlayingPane(nowPlaying, onTogglePause = {}, onSkip = {}, modifier = Modifier.width(320.dp), clock = clock)
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
                RequestifyTheme(BrandSeed, dark = true) {
                    NowPlayingPane(
                        nowPlaying, onTogglePause = {}, onSkip = {},
                        modifier = Modifier.width(320.dp).height(472.dp), clock = { 0L },
                    )
                }
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
}
