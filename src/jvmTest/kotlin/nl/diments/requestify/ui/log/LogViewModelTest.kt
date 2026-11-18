package nl.diments.requestify.ui.log

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.log.LogConnectionState
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.log.LogRow
import nl.diments.requestify.core.log.LogService
import nl.diments.requestify.core.log.LogStep
import nl.diments.requestify.core.log.LogStreamChange
import nl.diments.requestify.core.log.foldRows
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {
    private val noon = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()

    private val brightside = Track("t1", "spotify:track:t1", "Mr. Brightside", listOf("The Killers"), 222_000, false, null)
    private val despacito = Track("t2", "spotify:track:t2", "Despacito", listOf("Luis Fonsi"), 229_000, false, null)

    private fun request(id: String, at: Long, who: String, track: Track, vararg steps: LogStep): List<LogRecord> =
        listOf(
            LogRecord.RequestReceived(id, at, RequestSource.CHAT, Requester(who), "!sr ${track.name}"),
            LogRecord.RequestStep(id, at, LogStep.Resolved(track)),
        ) + steps.map { LogRecord.RequestStep(id, at, it) }

    private val mixed = foldRows(
        request("r1", noon, "Jan", brightside, LogStep.Queued(1)) +
            request("r2", noon + 1, "Piet", despacito, LogStep.Rejected(RejectReason.TrackBlocked, listed = true)) +
            LogRecord.ConnectionChanged("conn:Twitch:5", noon + 2, LogService.Twitch, LogConnectionState.Lost) +
            LogRecord.StreamChanged("stream:3:Live", noon + 3, LogStreamChange.Live) +
            LogRecord.GapNoticed("gap:BITS:4", noon + 4, RequestSource.BITS, null, noon + 4),
    )

    private fun TestScope.vm(rows: List<LogRow>, work: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) =
        LogViewModel(MutableStateFlow(rows), MutableStateFlow(null), backgroundScope, ZoneOffset.UTC, work).also { runCurrent() }

    private fun LogViewModel.ids() = days.value.flatMap { day -> day.rows.map { it.id } }

    @Test
    fun filtersSplitRowsByKind() = runTest {
        val vm = vm(mixed)
        assertEquals(listOf("gap:BITS:4", "stream:3:Live", "conn:Twitch:5", "r2", "r1"), vm.ids())

        vm.setFilter(LogFilter.Requests)
        runCurrent()
        assertEquals(listOf("gap:BITS:4", "r2", "r1"), vm.ids(), "requests, and the gaps where requests may be missing")

        vm.setFilter(LogFilter.Connections)
        runCurrent()
        assertEquals(listOf("stream:3:Live", "conn:Twitch:5"), vm.ids(), "connections and the stream's status")
    }

    @Test
    fun problemsShowOnlyProblems() = runTest {
        val vm = vm(mixed)
        vm.setFilter(LogFilter.Problems)
        runCurrent()
        assertEquals(listOf("gap:BITS:4", "conn:Twitch:5", "r2"), vm.ids())
    }

    @Test
    fun searchFindsNameAndTrack() = runTest {
        val vm = vm(mixed)
        fun found(q: String): List<String> {
            vm.setQuery(q)
            runCurrent()
            return vm.ids()
        }
        assertEquals(listOf("r1"), found("jan"))
        assertEquals(listOf("r1"), found("KILLERS"))
        assertEquals(listOf("r2"), found("despa"))
        assertEquals(emptyList(), found("nothing like this"))
        assertEquals(5, found("  ").size, "a blank search shows everything")
    }

    @Test
    fun rowsAreGroupedByLocalDay() = runTest {
        val late = Instant.parse("2026-11-17T23:00:00Z").toEpochMilli()
        val early = Instant.parse("2026-11-18T01:00:00Z").toEpochMilli()
        val vm = vm(foldRows(request("a", late, "Jan", brightside) + request("b", early, "Piet", despacito)))

        assertEquals(listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17)), vm.days.value.map { it.date })
        assertEquals(listOf(listOf("b"), listOf("a")), vm.days.value.map { day -> day.rows.map { it.id } })
    }

    @Test
    fun selectingARowFollowsItsUpdates() = runTest {
        val rows = MutableStateFlow(mixed)
        val vm = LogViewModel(rows, MutableStateFlow(null), backgroundScope, ZoneOffset.UTC, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.select("r1")
        runCurrent()
        assertEquals(LogStep.Queued(1), vm.selected.value?.latestStep)

        rows.value = foldRows(request("r1", noon, "Jan", brightside, LogStep.Queued(1), LogStep.Played))
        runCurrent()
        assertEquals(LogStep.Played, vm.selected.value?.latestStep)

        vm.select(null)
        runCurrent()
        assertNull(vm.selected.value)
    }

    @Test
    fun filteringRunsOffTheUiThread() = runTest {
        val work = Counting(StandardTestDispatcher(testScheduler))
        val vm = vm(mixed, work)
        val before = work.dispatches
        vm.setFilter(LogFilter.Problems)
        runCurrent()
        assertTrue(work.dispatches > before, "the filter ran on the work dispatcher")
    }

    /** A dispatcher that counts what it is given. */
    private class Counting(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            inner.dispatch(context, block)
        }
    }
}
