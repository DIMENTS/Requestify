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

    private fun tip(status: GateStatus, zone: java.time.ZoneId = java.time.ZoneId.of("UTC")): String {
        var text = ""
        runComposeUiTest { setContent { text = tooltipText(status, zone) } ; waitForIdle() }
        return text
    }

    @Test
    fun tooltipSaysWhenFreeRequestsClearInTheGivenZone() {
        val at = java.time.Instant.parse("2026-11-17T22:41:00Z").toEpochMilli()
        val grace = status(open = true, twitch = true).copy(clearAtEpochMs = at)
        assertEquals(
            "Requests are open because you’re live. Click to close them for now.\nFree requests clear at 22:41",
            tip(grace),
        )
        // The same moment on the clock of another zone (Amsterdam is UTC+1 in November).
        assertEquals(true, tip(grace, java.time.ZoneId.of("Europe/Amsterdam")).endsWith("Free requests clear at 23:41"))
        // Single-digit hours keep their zero.
        val early = java.time.Instant.parse("2026-11-17T05:07:00Z").toEpochMilli()
        assertEquals(true, tip(grace.copy(clearAtEpochMs = early)).endsWith("clear at 05:07"))
    }

    @Test
    fun tooltipSaysClearingWaitsWhilePostponed() {
        val text = tip(status(open = true, twitch = true).copy(clearPostponed = true))
        assertEquals(true, text.endsWith("Clearing waits until you close requests"), text)
        assertEquals(false, text.contains("Free requests clear at"))
    }

    @Test
    fun tooltipExplainsAManualOverrideAndHasNoClearLineWithoutAGrace() {
        assertEquals(
            "You set this by hand. Requests follow your stream again the next time it goes live or offline.",
            tip(status(open = true, twitch = true).copy(manual = true)),
        )
        assertEquals("Requests are closed. Click to open them.", tip(status(open = false, twitch = false)))
    }
}
