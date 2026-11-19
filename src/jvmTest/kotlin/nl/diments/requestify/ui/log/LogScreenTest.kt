package nl.diments.requestify.ui.log

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.log.LogConnectionState
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.log.LogService
import nl.diments.requestify.core.log.LogStep
import nl.diments.requestify.core.log.foldRows
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class LogScreenTest {
    private val originalLocale = Locale.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restore() {
        Locale.setDefault(originalLocale)
        scope.cancel()
    }

    private val at = Instant.parse("2026-11-18T21:42:00Z").toEpochMilli()
    private val track = Track("t1", "spotify:track:t1", "Mr. Brightside", listOf("The Killers"), 222_000, false, null)

    private val rows = foldRows(
        listOf(
            LogRecord.RequestReceived("r1", at, RequestSource.DONOLINK, Requester("Jan"), "https://open.spotify.com/track/t1", Payment.Money(300, "EUR")),
            LogRecord.RequestStep("r1", at, LogStep.Resolved(track)),
            LogRecord.RequestStep("r1", at, LogStep.Queued(2)),
            LogRecord.RequestStep("r1", at + 240_000, LogStep.Played),
        ),
    )

    private fun vm(error: String? = null) =
        LogViewModel(MutableStateFlow(rows), MutableStateFlow(error), scope, ZoneOffset.UTC, Dispatchers.Unconfined)

    @Test
    fun positionsAreNeverZero() = runComposeUiTest {
        val next = foldRows(
            listOf(
                LogRecord.RequestReceived("r2", at, RequestSource.CHAT, Requester("Piet"), "despacito"),
                LogRecord.RequestStep("r2", at, LogStep.Queued(0)), // handed to Spotify before its place was read
            ),
        )
        val vm = LogViewModel(MutableStateFlow(next), MutableStateFlow(null), scope, ZoneOffset.UTC, Dispatchers.Unconfined)
        setContent { RequestifyTheme(BrandSeed, dark = true) { LogScreen(vm) } }
        onNodeWithText("In queue, up next").assertIsDisplayed()
        onAllNodesWithText("#0", substring = true).assertCountEquals(0)
    }

    @Test
    fun rowsShowTimeSourceAndState() = runComposeUiTest {
        val vm = vm()
        setContent { RequestifyTheme(BrandSeed, dark = true) { LogScreen(vm) } }

        onNodeWithText("21:42").assertIsDisplayed()
        onNodeWithText("Jan · Mr. Brightside").assertIsDisplayed()
        onNodeWithText("Played").assertIsDisplayed()
        onNodeWithText("DonoLink €3").assertIsDisplayed()
    }

    @Test
    fun clickingARowShowsItsTimeline() = runComposeUiTest {
        val vm = vm()
        setContent { RequestifyTheme(BrandSeed, dark = true) { LogScreen(vm) } }

        onAllNodesWithTag(LOG_ROW_TAG).onFirst().performClick()
        waitForIdle()

        onNodeWithText("What happened").assertIsDisplayed()
        onNodeWithText("Received").assertIsDisplayed()
        onNodeWithText("In queue, #2").assertIsDisplayed()
        onNodeWithText("21:46").assertIsDisplayed()
    }

    @Test
    fun anOutageSaysHowLongItLasted() = runComposeUiTest {
        val outage = foldRows(
            listOf(
                LogRecord.ConnectionChanged("c1", at, LogService.Twitch, LogConnectionState.Lost),
                LogRecord.ConnectionChanged("c1", at + 4_000, LogService.Twitch, LogConnectionState.Restored),
            ),
        )
        val vm = LogViewModel(MutableStateFlow(outage), MutableStateFlow(null), scope, ZoneOffset.UTC, Dispatchers.Unconfined)
        setContent { RequestifyTheme(BrandSeed, dark = true) { LogScreen(vm) } }

        onNodeWithText("Connection lost · restored after 4 s").assertIsDisplayed()
        onNodeWithText("Twitch").assertIsDisplayed()
    }

    @Test
    fun aWriteErrorShowsTheBanner() = runComposeUiTest {
        val vm = vm(error = "disk full")
        setContent { RequestifyTheme(BrandSeed, dark = true) { LogScreen(vm) } }

        onNodeWithText("The Log can’t save right now: disk full").assertIsDisplayed()
    }

    @Test
    fun titlesLineUpWhateverTheChipBeside() = runComposeUiTest {
        val mixed = foldRows(
            listOf(
                LogRecord.RequestReceived("r1", at, RequestSource.CHAT, Requester("Jan"), "despacito"),
                LogRecord.RequestStep("r1", at, LogStep.Resolved(track)),
                LogRecord.RequestReceived("r2", at + 1_000, RequestSource.CHANNEL_POINTS, Requester("Piet"), "despacito"),
                LogRecord.RequestStep("r2", at + 1_000, LogStep.Resolved(track)),
                LogRecord.RequestReceived("r3", at + 2_000, RequestSource.STREAMELEMENTS, Requester("Kees"), "x", Payment.Money(123456, "EUR")),
                LogRecord.RequestStep("r3", at + 2_000, LogStep.Resolved(track)),
            ),
        )
        val vm = LogViewModel(MutableStateFlow(mixed), MutableStateFlow(null), scope, ZoneOffset.UTC, Dispatchers.Unconfined)
        setContent { RequestifyTheme(BrandSeed, dark = true) { LogScreen(vm) } }
        val starts = listOf("Jan", "Piet", "Kees").map { onNodeWithText("$it · Mr. Brightside").getBoundsInRoot().left }
        assertEquals(1, starts.distinct().size, "the titles start at $starts")
    }
}
