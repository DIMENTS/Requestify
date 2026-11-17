package nl.diments.requestify.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class TitleBarTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun showsTheAppNameAndSpotifysStatus() = runComposeUiTest {
        val health = mutableStateOf<SpotifyHealth>(SpotifyHealth.Ok)
        setContent { RequestifyTheme(BrandSeed, dark = true) { TitleBar(health.value) } }
        onNodeWithText("Requestify").assertIsDisplayed()
        onNodeWithText("Spotify connected").assertIsDisplayed()

        health.value = SpotifyHealth.NoActiveDevice
        waitForIdle()
        onNodeWithText("Open Spotify on a device").assertIsDisplayed()
    }

    @Test
    fun theStatusAndTheRequestsSwitchTakeClicksAndTheRestMovesTheWindow() = runComposeUiTest {
        val hits = mutableListOf<Boolean>()
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                TitleBar(
                    SpotifyHealth.Ok,
                    onHitTest = { hits += it },
                    requestsSwitch = { Box(Modifier.size(52.dp, 32.dp).testTag("switch")) },
                )
            }
        }
        onNodeWithText("Spotify connected").performMouseInput { moveTo(center) }
        assertEquals(true, hits.last(), "the status chip")
        onNodeWithTag("switch").performMouseInput { moveTo(center) }
        assertEquals(true, hits.last(), "the requests switch")
        onNodeWithText("Requestify").performMouseInput { moveTo(center) }
        assertEquals(false, hits.last(), "the app name")
    }

    @Test
    fun theCaptionButtonsKeepTheirRoom() = runComposeUiTest {
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                TitleBar(
                    SpotifyHealth.Ok,
                    Modifier.testTag("bar"),
                    captionInset = 141.dp,
                    requestsSwitch = { Box(Modifier.size(52.dp, 32.dp).testTag("switch")) },
                )
            }
        }
        val bar = onNodeWithTag("bar").getBoundsInRoot()
        assertEquals(40.dp, bar.height)
        assertEquals(bar.right - 141.dp - 16.dp, onNodeWithTag("switch").getBoundsInRoot().right)
    }
}
