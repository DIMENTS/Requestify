package nl.diments.requestify.ui.home

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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

    /** As on Home: the pane is 320 dp wide. */
    @Composable
    private fun Pane(nowPlaying: NowPlaying) {
        RequestifyTheme(BrandSeed, dark = true) {
            NowPlayingPane(nowPlaying, onTogglePause = {}, onSkip = {}, modifier = Modifier.width(320.dp))
        }
    }

    @Test
    fun ownMusicWhenNoRequest() = runComposeUiTest {
        setContent { Pane(NowPlaying(midnightCity, 60_000, isPlaying = true, request = null)) }
        onNodeWithText("Midnight City").assertIsDisplayed()
        onNodeWithText("Your own music").assertIsDisplayed()
    }

    @Test
    fun bitsRequestShowsChipAndRequester() = runComposeUiTest {
        val request = Request("r1", midnightCity, Requester("streamfan"), RequestSource.BITS, Payment.Bits(500), createdAtEpochMs = 1L)
        setContent { Pane(NowPlaying(midnightCity, 60_000, isPlaying = true, request = request)) }
        onNodeWithText("500 Bits").assertIsDisplayed()
        onNodeWithText("streamfan").assertIsDisplayed()
    }

    @Test
    fun progressRunsOnBetweenPollsOnlyWhilePlaying() = runComposeUiTest {
        var nowPlaying by mutableStateOf(NowPlaying(midnightCity, 60_000, isPlaying = true, request = null))
        mainClock.autoAdvance = false
        setContent { Pane(nowPlaying) }
        mainClock.advanceTimeBy(5_500) // the position ticks every 250 ms
        onNodeWithText("1:05").assertExists()

        nowPlaying = nowPlaying.copy(progressMs = 90_000, isPlaying = false)
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("1:30").assertExists()
    }
}
