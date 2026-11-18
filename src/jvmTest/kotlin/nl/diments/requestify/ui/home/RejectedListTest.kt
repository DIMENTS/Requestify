package nl.diments.requestify.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class RejectedListTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    private val echoes = Track("t1", "spotify:track:t1", "Echoes", listOf("Pink Floyd"), 1_380_000, false, null)
    private val bigcheer = Requester("BigCheer", "bigcheer", "7")
    private val bitsRequest = Request("r1", echoes, bigcheer, RequestSource.BITS, Payment.Bits(500), createdAtEpochMs = 1L)

    private fun entry(
        id: String = "r1",
        request: Request? = bitsRequest,
        reason: RejectReason = RejectReason.TooLong(420_000),
        refunded: Boolean = false,
    ) = RejectedEntry(
        id, request, input = "Pink Floyd - Echoes", requester = bigcheer, source = RequestSource.BITS,
        payment = Payment.Bits(500), reason = reason, atEpochMs = 1L, refunded = refunded,
    )

    @Test
    fun rowShowsReasonAndActions() = runComposeUiTest {
        var added: String? = null
        var dismissed: String? = null
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                RejectedList(listOf(entry(refunded = true)), onAddAnyway = { added = it }, onDismiss = { dismissed = it })
            }
        }
        onNodeWithText("Echoes").assertIsDisplayed()
        onNodeWithText("Pink Floyd").assertIsDisplayed()
        onNodeWithText("500 Bits").assertIsDisplayed()
        onNodeWithText("BigCheer").assertIsDisplayed()
        onNodeWithText("Longer than 7:00 · points refunded").assertIsDisplayed()

        onNodeWithContentDescription("Add anyway").performClick()
        assertEquals("r1", added)
        onNodeWithContentDescription("Dismiss").performClick()
        assertEquals("r1", dismissed)
    }

    @Test
    fun donationReasonsHaveText() = runComposeUiTest {
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                RejectedList(
                    listOf(
                        entry(id = "d1", reason = RejectReason.BelowMinimumAmount(100, "EUR")),
                        entry(id = "d2", reason = RejectReason.OtherCurrency("USD")),
                    ),
                    onAddAnyway = {},
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("Below the minimum (€1)").assertIsDisplayed()
        onNodeWithText("Other currency (USD)").assertIsDisplayed()
    }

    @Test
    fun rowWithoutTrackOffersOnlyDismiss() = runComposeUiTest {
        var dismissed: String? = null
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                RejectedList(
                    listOf(entry(id = "r2", request = null, reason = RejectReason.NoMatch)),
                    onAddAnyway = {},
                    onDismiss = { dismissed = it },
                )
            }
        }
        onNodeWithText("Pink Floyd - Echoes").assertIsDisplayed()
        onNodeWithText("No track found").assertIsDisplayed()
        onNodeWithContentDescription("Add anyway").assertDoesNotExist()
        onNodeWithContentDescription("Dismiss").performClick()
        assertEquals("r2", dismissed)
    }

    @Test
    fun emptyStateSaysNothingWasRejected() = runComposeUiTest {
        setContent { RequestifyTheme(BrandSeed, dark = true) { RejectedList(emptyList(), {}, {}) } }
        onNodeWithText("Nothing has been rejected.").assertIsDisplayed()
    }

    @Test
    fun everyReasonHasAText() = runComposeUiTest {
        val reasons = listOf(
            RejectReason.NotATrack("album") to "Only tracks can be requested",
            RejectReason.NoMatch to "No track found",
            RejectReason.Unplayable to "Can’t be played in your country",
            RejectReason.SpotifyUnavailable to "Spotify couldn’t be reached",
            RejectReason.ViewerBlocked to "On the blacklist",
            RejectReason.TrackBlocked to "On the blacklist",
            RejectReason.ArtistBlocked to "On the blacklist",
            RejectReason.Explicit to "Explicit tracks are blocked",
            RejectReason.TooShort(30_000) to "Shorter than 0:30",
            RejectReason.TooLong(420_000) to "Longer than 7:00",
            RejectReason.Closed to "Requests were closed",
            RejectReason.QueueFull(25) to "The queue is full (25)",
            RejectReason.ViewerLimit(2) to "Already 2 requests in the queue",
            RejectReason.Cooldown(95_000) to "On cooldown, 1:35 left",
            RejectReason.Duplicate to "Already queued or played recently",
            RejectReason.BelowMinimum(100) to "Below the minimum (100 Bits)",
            RejectReason.ShortLinkUnresolved to "Use the open.spotify.com link",
        )
        val shown = mutableListOf<String>()
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                reasons.forEach { (reason, _) -> shown += reason.text() }
            }
        }
        assertEquals(reasons.map { it.second }, shown)
    }
}
