package nl.diments.requestify.ui.window

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.stream.GateStatus
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.LocalReducedMotion
import nl.diments.requestify.ui.theme.RequestifyTheme

/** How the title bar moves: the requests toggle fades between its looks, and nothing grows with animation effects off. */
@OptIn(ExperimentalTestApi::class)
class TitleBarMotionTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun gate(open: Boolean) = GateStatus(open, twitch = false, live = false, manual = false, clearAtEpochMs = null, clearPostponed = false)

    @Test
    fun theRequestsToggleFadesBetweenOpenAndClosed() = runComposeUiTest {
        var status by mutableStateOf(gate(open = true))
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    RequestsToggle(status, onToggle = {}, modifier = Modifier.testTag("toggle"))
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        // A pixel of the button's fill, left of its icon.
        fun fill(): Int {
            val image = onNodeWithTag("toggle", useUnmergedTree = true).captureToImage().toAwtImage()
            return image.getRGB(4, image.height / 2)
        }
        val open = fill()
        status = gate(open = false)
        mainClock.advanceTimeBy(64)
        val between = fill()
        mainClock.advanceTimeBy(1_000)
        val closed = fill()
        assertNotEquals(open, closed)
        assertNotEquals(open, between, "the fill fades instead of switching at once")
        assertNotEquals(closed, between, "the fill fades instead of switching at once")
    }

    @Test
    fun withAnimationEffectsOffTheStatusChipTakesItsNewWidthAtOnce() = runComposeUiTest {
        var problems by mutableStateOf<List<ConnectionProblem>>(emptyList())
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                RequestifyTheme(BrandSeed, dark = true) { TitleBar(problems) }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        problems = listOf(ConnectionProblem.Spotify(SpotifyHealth.NoActiveDevice))
        mainClock.advanceTimeBy(64)
        val early = onNodeWithText("Open Spotify on a device").getBoundsInRoot()
        mainClock.advanceTimeBy(1_000)
        assertEquals(onNodeWithText("Open Spotify on a device").getBoundsInRoot(), early)
    }
}
