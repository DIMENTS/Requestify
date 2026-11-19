package nl.diments.requestify.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.update.JustUpdated
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class UpdatedCardTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun setUp() { Locale.setDefault(Locale.ENGLISH) }
    @AfterTest fun tearDown() { Locale.setDefault(originalLocale) }

    @Test
    fun showsOnceAndDismisses() = runDesktopComposeUiTest(width = 900, height = 600) {
        val updated = JustUpdated("0.2.0", "0.2.1", "## New\n- A **welcome wizard**")
        assertTrue(showUpdatedCard(updated, lastShownVersion = null))
        assertTrue(showUpdatedCard(updated, lastShownVersion = "0.2.0"))
        assertFalse(showUpdatedCard(updated, lastShownVersion = "0.2.1"), "closed once: gone for good")
        assertFalse(showUpdatedCard(null, lastShownVersion = null))

        var dismissed = 0
        setContent { RequestifyTheme(BrandSeed, dark = true) { UpdatedCard(updated, onDismiss = { dismissed++ }) } }
        onNodeWithText("Updated to 0.2.1").assertIsDisplayed()
        onNodeWithText("• A welcome wizard", substring = true).assertIsDisplayed()
        onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun markdownBecomesReadableText() {
        assertEquals(
            "New\n• A welcome wizard\n• GitHub\n\nPlain and *simple* text",
            releaseNotesText("## New\n- A **welcome wizard**\n* [GitHub](https://github.com/DIMENTS/Requestify)\n\nPlain __and__ *simple* text"),
        )
        assertEquals("Thanks lofi_lisa", releaseNotesText("Thanks lofi_lisa"), "a name with an underscore stays")
        assertEquals("", releaseNotesText("   "))
    }

    @Test
    fun thePlaceholderLineIsLeftOut() {
        assertEquals("", releaseNotesText(PLACEHOLDER_NOTES), "a release whose notes were not written shows none")
        assertEquals("Nieuw\n• Bot", releaseNotesText("## Nieuw\n$PLACEHOLDER_NOTES\n- Bot"))
    }
}
