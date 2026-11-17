package nl.diments.requestify.ui.window

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.stream.GateStatus
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class RequestsToggleTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun status(open: Boolean, twitch: Boolean, live: Boolean = false) =
        GateStatus(open = open, twitch = twitch, live = live, manual = false, clearAtEpochMs = null, clearPostponed = false)

    @Test
    fun toggleLabelsForEveryState() = runComposeUiTest {
        val status = mutableStateOf(status(open = true, twitch = true, live = true))
        setContent { RequestifyTheme(BrandSeed, dark = true) { RequestsToggle(status.value, onToggle = {}) } }
        onNodeWithText("Live · requests open").assertIsDisplayed()

        status.value = status(open = false, twitch = true, live = false)
        waitForIdle()
        onNodeWithText("Offline · requests closed").assertIsDisplayed()

        status.value = status(open = true, twitch = false)
        waitForIdle()
        onNodeWithText("Requests open").assertIsDisplayed()

        status.value = status(open = false, twitch = false)
        waitForIdle()
        onNodeWithText("Requests closed").assertIsDisplayed()

        // A manual override combines the stream's state with the switch's.
        status.value = status(open = true, twitch = true, live = false).copy(manual = true)
        waitForIdle()
        onNodeWithText("Offline · requests open").assertIsDisplayed()
    }

    @Test
    fun toggleClickCallsOnToggle() = runComposeUiTest {
        var toggles = 0
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                RequestsToggle(status(open = false, twitch = false), onToggle = { toggles++ })
            }
        }
        onNodeWithText("Requests closed").performClick()
        assertEquals(1, toggles)
    }
}
