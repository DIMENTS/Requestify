package nl.diments.requestify.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.stream.GateEvent
import nl.diments.requestify.core.twitch.eventsub.EventSubState

@OptIn(ExperimentalCoroutinesApi::class)
class BitsGapWatcherTest {
    private val minute = 60_000L

    /** Wall-clock time at virtual time 0. */
    private val base = 1_000_000_000L

    private val eventSub = MutableStateFlow<EventSubState>(EventSubState.Stopped)
    private val gate = MutableSharedFlow<GateEvent>(extraBufferCapacity = 16)

    private fun TestScope.now() = base + testScheduler.currentTime

    /** A watcher started at [appStartMs]; returns the gaps it reports. */
    private fun TestScope.watch(loadedLastSeenLiveMs: Long? = null, appStartMs: Long = base): List<LogRecord.GapNoticed> {
        val watcher = BitsGapWatcher(eventSub, gate, loadedLastSeenLiveMs, appStartMs, backgroundScope, clock = { now() })
        val gaps = mutableListOf<LogRecord.GapNoticed>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { watcher.gaps.collect { gaps += it } }
        watcher.start()
        runCurrent()
        return gaps
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun gap(from: Long?, to: Long) = LogRecord.GapNoticed("gap:BITS:$to", to, RequestSource.BITS, from, to)

    @Test
    fun aDisconnectDuringALiveStreamIsAGap() = runTest {
        eventSub.value = EventSubState.Connected
        val gaps = watch()
        gate.emit(GateEvent.WentLive(now(), byRead = false))
        wait(minute)

        val lostAt = now()
        eventSub.value = EventSubState.Disconnected(lostAt)
        wait(4 * minute)
        eventSub.value = EventSubState.Connecting
        runCurrent()
        assertTrue(gaps.isEmpty(), "reported once it is over")
        eventSub.value = EventSubState.Connected
        runCurrent()

        assertEquals(listOf(gap(lostAt, now())), gaps)
    }

    @Test
    fun aReconnectWithinTheSessionIsNoGap() = runTest {
        eventSub.value = EventSubState.Connected
        val gaps = watch()
        gate.emit(GateEvent.WentLive(now(), byRead = false))
        wait(30 * minute) // session_reconnect hands over without ever leaving Connected
        eventSub.value = EventSubState.Connected
        runCurrent()

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun anAppClosedDuringTheStreamLeavesAGap() = runTest {
        val lastSeen = base - 10 * minute
        eventSub.value = EventSubState.Connected
        val gaps = watch(loadedLastSeenLiveMs = lastSeen)
        wait(minute)
        gate.emit(GateEvent.WentLive(now(), byRead = true))
        runCurrent()

        assertEquals(listOf(gap(lastSeen, now())), gaps)

        gate.emit(GateEvent.WentOffline(now()))
        gate.emit(GateEvent.WentLive(now(), byRead = true)) // a drop later on: not the start of the run
        runCurrent()
        assertEquals(1, gaps.size)
    }

    @Test
    fun startingDuringALiveStreamLeavesAGapOfUnknownStart() = runTest {
        eventSub.value = EventSubState.Connected
        val gaps = watch(appStartMs = base)
        wait(minute)
        gate.emit(GateEvent.WentLive(now(), byRead = true))
        runCurrent()

        assertEquals(listOf(gap(null, base)), gaps)
    }

    @Test
    fun noGapWhileOffline() = runTest {
        eventSub.value = EventSubState.Connected
        val gaps = watch()
        eventSub.value = EventSubState.Disconnected(now())
        wait(5 * minute)
        eventSub.value = EventSubState.Connected
        runCurrent()
        gate.emit(GateEvent.WentLive(now(), byRead = false)) // stream.online while running: nothing was missed
        runCurrent()

        assertTrue(gaps.isEmpty())
    }
}
