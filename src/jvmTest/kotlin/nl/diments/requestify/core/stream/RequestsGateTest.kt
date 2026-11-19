package nl.diments.requestify.core.stream

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import nl.diments.requestify.core.storage.SaveContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class RequestsGateTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-stream") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val minute = 60_000L
    private val grace = 10 * minute

    /** How often the end-of-stream clear ran, across every gate of the test. */
    private var cleared = 0

    /** Every dispatch of the gates' background saves. */
    private var ioDispatches = 0

    /** A gate on the file in [dir]; calling it again (a restart) builds a second one on the same file. */
    private fun TestScope.gate(
        twitch: MutableStateFlow<Boolean> = MutableStateFlow(true),
        scope: CoroutineScope = backgroundScope,
        session: () -> Boolean = { true },
    ) = RequestsGate(
        file = JsonFileStore(dir.resolve("stream.json"), StreamState.serializer(), retryDelaysMs = emptyList()) { StreamState() },
        twitchConnected = twitch,
        scope = scope,
        clock = { testScheduler.currentTime },
        onStreamEnded = { cleared++ },
        sessionConnected = session,
        context = SaveContext(scope, { testScheduler.currentTime }, countingIo(StandardTestDispatcher(testScheduler))),
    ).also { it.start() }

    /** Counts [inner]'s dispatches; its virtual-time delays stay (the writer's retries wait on the test clock). */
    @OptIn(InternalCoroutinesApi::class)
    private fun countingIo(inner: CoroutineDispatcher): CoroutineDispatcher = object : CoroutineDispatcher(), Delay by (inner as Delay) {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ioDispatches++
            inner.dispatch(context, block)
        }
    }

    @Test
    fun aRestartAfterADropInTheSameStreamClearsNothing() = runTest {
        val first = appScope()
        val gate = gate(scope = first)
        gate.onLive(true) // stream.online at 0
        wait(30 * minute)
        gate.onLive(false) // the IRL connection drops
        wait(2 * minute)
        gate.onLive(true) // back within the grace: Twitch's started_at is now this moment
        val reconnectedAt = testScheduler.currentTime
        runCurrent()
        wait(20 * minute)
        first.cancel() // the app restarts (a crash, or a language change)
        val after = gate()
        after.onLive(true, read = true, startedAtMs = reconnectedAt)
        runCurrent()
        assertEquals(0, cleared, "the same stream: no end-of-stream clear, no refunds")
        assertTrue(after.open.value)
    }

    @Test
    fun theHeartbeatTicksOnlyWhileTheSessionIsConnected() = runTest {
        var connected = true
        val gate = gate(session = { connected })
        gate.onLive(true)
        runCurrent()
        wait(minute)
        assertEquals(minute, saved().lastSeenLiveMs)
        connected = false // EventSub lost: nothing tells the app the stream is still live
        wait(3 * minute)
        assertEquals(minute, saved().lastSeenLiveMs, "no heartbeat while the session is down")
    }

    @Test
    fun aChangeDoesNotWaitForTheFile() = runTest {
        val gate = gate()
        runCurrent()
        val events = mutableListOf<GateEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.events.collect { events += it } }

        gate.onLive(true)
        assertTrue(gate.open.value)
        assertEquals<List<GateEvent>>(listOf(GateEvent.WentLive(0, byRead = false)), events, "the change and its event come at once")
        assertFalse(saved().live, "the file is written in the background")
        runCurrent()
        assertTrue(saved().live)
    }

    @Test
    fun theHeartbeatWritesOffTheCallerThread() = runTest {
        val gate = gate()
        gate.onLive(true)
        runCurrent()
        val before = ioDispatches

        wait(minute)
        assertEquals(minute, saved().lastSeenLiveMs, "the heartbeat saved the time it saw the stream live")
        assertTrue(ioDispatches > before, "through the background writer")
    }

    /** A scope that ends like the app does when it closes: [cancel] it. */
    private fun TestScope.appScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun followsTheStream() = runTest {
        val gate = gate()
        runCurrent()
        assertFalse(gate.open.value, "offline: closed")

        gate.onLive(true)
        assertTrue(gate.open.value, "live: open")

        gate.onLive(false)
        assertFalse(gate.open.value, "offline: closed at once")

        wait(minute)
        gate.onLive(true)
        assertTrue(gate.open.value, "back within the grace: open again")
        assertEquals(0, cleared)
    }

    @Test
    fun shortDropKeepsFreeRequestsAndManualClose() = runTest {
        val gate = gate()
        gate.onLive(true)
        gate.toggle()
        assertFalse(gate.open.value)
        assertTrue(gate.status.value.manual)

        gate.onLive(false)
        wait(2 * minute)
        gate.onLive(true)
        assertFalse(gate.open.value, "the manual close survives the drop")
        assertTrue(gate.status.value.manual)
        wait(grace)
        assertEquals(0, cleared, "a drop changes nothing")

        // The real end ends the override; the next start follows the stream again.
        gate.onLive(false)
        wait(grace)
        assertEquals(1, cleared)
        assertFalse(gate.status.value.manual)
        gate.onLive(true)
        assertTrue(gate.open.value)
    }

    @Test
    fun tenMinutesOfflineEndsTheStreamOnce() = runTest {
        val gate = gate()
        gate.onLive(true)
        wait(minute)
        gate.onLive(false)

        wait(5 * minute)
        gate.onLive(false) // the REST check repeats it: the grace does not start again
        wait(5 * minute - 1)
        assertEquals(0, cleared)
        wait(1)
        assertEquals(1, cleared)
        assertFalse(gate.open.value)
        assertNull(gate.status.value.clearAtEpochMs)

        gate.onLive(false)
        wait(60 * minute)
        assertEquals(1, cleared, "exactly one clear per stream end")
    }

    @Test
    fun restartDuringGraceStillClearsOnTime() = runTest {
        val first = appScope()
        val before = gate(scope = first)
        before.onLive(true)
        before.onLive(false)
        wait(5 * minute)
        first.cancel() // the app closes

        val after = gate()
        runCurrent()
        assertFalse(after.open.value)
        assertEquals(grace, after.status.value.clearAtEpochMs)
        wait(5 * minute - 1)
        assertEquals(0, cleared)
        wait(1)
        assertEquals(1, cleared)
    }

    @Test
    fun restartAfterGraceClearsAtStart() = runTest {
        val first = appScope()
        val before = gate(scope = first)
        before.onLive(true)
        before.onLive(false)
        wait(minute)
        first.cancel() // the app closes during the grace and stays off past it
        wait(30 * minute)
        assertEquals(0, cleared)

        val after = gate()
        runCurrent()
        assertEquals(1, cleared)
        assertNull(after.status.value.clearAtEpochMs)

        gate() // another restart: the stream has ended already
        wait(grace)
        assertEquals(1, cleared)
    }

    @Test
    fun manualOpenAfterStreamPostponesClearUntilClosed() = runTest {
        val first = appScope()
        val gate = gate(scope = first)
        gate.onLive(true)
        gate.onLive(false)
        wait(3 * minute)
        gate.toggle()
        assertTrue(gate.open.value, "opened by hand after the stream")
        assertTrue(gate.status.value.manual)

        wait(7 * minute)
        assertEquals(0, cleared, "the clear waits while requests are open")
        assertTrue(gate.open.value)
        assertTrue(gate.status.value.clearPostponed)
        assertNull(gate.status.value.clearAtEpochMs)

        wait(60 * minute)
        first.cancel() // a restart keeps the manual open and the postponed clear
        val after = gate()
        runCurrent()
        assertTrue(after.open.value)
        assertTrue(after.status.value.clearPostponed)
        assertEquals(0, cleared)

        after.toggle()
        runCurrent()
        assertFalse(after.open.value)
        assertEquals(1, cleared, "closing again clears at once")
        assertFalse(after.status.value.clearPostponed)
    }

    @Test
    fun closingAfterAManualOpenAfterTheClearClearsAgain() = runTest {
        val gate = gate()
        gate.onLive(true)
        gate.onLive(false)
        wait(grace)
        assertEquals(1, cleared)

        gate.toggle() // a listening party after the stream: free requests come in
        assertTrue(gate.open.value)
        assertTrue(gate.status.value.clearPostponed)
        gate.toggle()
        runCurrent()
        assertFalse(gate.open.value)
        assertEquals(2, cleared, "the close clears what came in while open")
        assertFalse(gate.status.value.clearPostponed)
    }

    @Test
    fun goingLiveCancelsTheClearOwedByAManualOpenAfterTheClear() = runTest {
        val gate = gate()
        gate.onLive(true)
        gate.onLive(false)
        wait(grace)
        gate.toggle()

        gate.onLive(true)
        assertFalse(gate.status.value.clearPostponed)
        gate.toggle()
        runCurrent()
        assertFalse(gate.open.value)
        assertEquals(1, cleared, "no clear for a close while live")
    }

    @Test
    fun closingInsideTheGraceLeavesTheClearToTheGraceEnd() = runTest {
        val gate = gate()
        gate.onLive(true)
        gate.onLive(false)
        wait(minute)
        gate.toggle()
        assertTrue(gate.status.value.clearPostponed)
        assertNull(gate.status.value.clearAtEpochMs, "the clear waits for the close")

        gate.toggle()
        runCurrent()
        assertEquals(0, cleared)
        assertFalse(gate.status.value.clearPostponed)
        assertEquals(grace, gate.status.value.clearAtEpochMs)

        wait(grace)
        assertEquals(1, cleared, "exactly once, at the grace's end")
    }

    @Test
    fun goingLiveCancelsAPostponedClear() = runTest {
        val gate = gate()
        gate.onLive(true)
        gate.onLive(false)
        gate.toggle()
        wait(grace)
        assertTrue(gate.status.value.clearPostponed)

        gate.onLive(true)
        assertTrue(gate.open.value)
        assertFalse(gate.status.value.clearPostponed)
        assertFalse(gate.status.value.manual, "a real start ends the override")
        gate.toggle()
        runCurrent()
        assertEquals(0, cleared)
    }

    @Test
    fun manualOpenWhileOfflineEndsAtNextRealStartAndEnd() = runTest {
        val gate = gate()
        runCurrent()
        assertFalse(gate.open.value)
        gate.toggle()
        assertTrue(gate.open.value)
        assertTrue(gate.status.value.manual)

        gate.onLive(true)
        assertTrue(gate.open.value)
        assertFalse(gate.status.value.manual, "the real start ends the override")

        gate.onLive(false)
        assertFalse(gate.open.value, "follows the stream again: closed at once")
        wait(grace)
        assertEquals(1, cleared)
        assertFalse(gate.open.value)
        assertFalse(gate.status.value.clearPostponed)
    }

    @Test
    fun openDuringADropCountsAsAnOverrideOfTheStreamItReturnsTo() = runTest {
        val gate = gate()
        gate.onLive(true)
        gate.onLive(false)
        wait(minute)
        gate.toggle()
        assertTrue(gate.open.value)
        gate.onLive(true)
        assertTrue(gate.open.value)
        assertFalse(gate.status.value.clearPostponed, "the return cancels the clear the open postponed")

        gate.onLive(false)
        assertFalse(gate.open.value, "the stream's end still closes at once")
        wait(grace)
        assertEquals(1, cleared, "and clears: the open was made during this stream")
        assertFalse(gate.status.value.manual)
    }

    @Test
    fun aChangeWhoseFileCannotBeWrittenAppliesAndIsSavedOnceItWorks() = runTest {
        val first = appScope()
        val gate = gate(scope = first)
        gate.onLive(true)
        runCurrent()
        breakWrites()

        gate.onLive(false)
        runCurrent()
        assertFalse(gate.open.value, "closed although the save failed")
        assertEquals(grace, gate.status.value.clearAtEpochMs)
        assertTrue(saved().live, "the file still has the stream live")
        assertEquals("stream.json", gate.saveProblem.value?.file)

        gate.toggle()
        assertTrue(gate.open.value, "a toggle applies at once; its save is retried")

        healWrites()
        wait(minute)
        assertEquals(null, gate.saveProblem.value)
        assertFalse(saved().live, "saved once the file works again")
        assertEquals(true, saved().override)
    }

    @Test
    fun theGraceRunsFromMemoryWhenItCannotBeSaved() = runTest {
        val first = appScope()
        val gate = gate(scope = first)
        gate.onLive(true)
        runCurrent()
        breakWrites()
        gate.onLive(false)

        wait(grace)
        assertEquals(1, cleared)
        assertNull(gate.status.value.clearAtEpochMs)
        assertEquals(StreamState(live = true, lastSeenLiveMs = 0, streamStartedAtEpochMs = 0), saved(), "nothing saved since the stream went live")

        healWrites()
        wait(minute)
        assertEquals(StreamState(live = false, lastSeenLiveMs = 0, streamStartedAtEpochMs = 0), saved(), "the stream's end is saved once the file works")
        first.cancel()
        gate()
        wait(grace)
        assertEquals(1, cleared, "no second clear after a restart")
    }

    @Test
    fun anUnreadableFileIsNeverWrittenOverAndCatchesUpOnceReadable() = runTest {
        val file = dir.resolve("stream.json")
        file.toFile().mkdirs() // reading it fails: not missing, not corrupt
        val gate = gate()
        gate.onLive(true)
        assertTrue(gate.open.value, "events still apply")
        gate.toggle()
        assertTrue(gate.open.value, "a toggle is not applied while the file cannot be read")
        assertTrue(file.toFile().isDirectory, "nothing written over it")

        file.toFile().delete()
        JsonFileStore(file, StreamState.serializer()) { StreamState() }.write(StreamState(openWithoutTwitch = false))
        gate.onLive(false)
        runCurrent()
        assertEquals(StreamState(live = false, offlineSinceMs = 0, openWithoutTwitch = false, lastSeenLiveMs = 0, streamStartedAtEpochMs = 0), saved())
        assertFalse(gate.open.value)
    }

    private fun saved() = JsonFileStore(dir.resolve("stream.json"), StreamState.serializer()) { StreamState() }.read()

    /** Makes writes fail (a directory sits where the temp file goes) until [healWrites]. */
    private fun breakWrites() { dir.resolve("stream.json.tmp").toFile().mkdirs() }
    private fun healWrites() { dir.resolve("stream.json.tmp").toFile().deleteRecursively() }

    @Test
    fun withoutTwitchItIsAPlainRememberedSwitchDefaultOpen() = runTest {
        val twitch = MutableStateFlow(false)
        val first = appScope()
        val gate = gate(twitch, first)
        assertTrue(gate.open.value, "default open")
        assertFalse(gate.status.value.twitch)

        gate.toggle()
        assertFalse(gate.open.value)
        gate.onLive(true)
        assertFalse(gate.open.value, "the stream does not move it")
        runCurrent() // the quit waits for the save
        first.cancel()

        val after = gate(twitch)
        assertFalse(after.open.value, "remembered")
        after.toggle()
        assertTrue(after.open.value)
        assertFalse(after.status.value.manual)

        after.onLive(false)
        twitch.value = true
        runCurrent()
        assertFalse(after.open.value, "with Twitch it follows the stream: offline")
        assertTrue(after.status.value.twitch)
    }

    @Test
    fun statusShowsClearTimeDuringGrace() = runTest {
        val gate = gate()
        gate.onLive(true)
        wait(minute)
        assertNull(gate.status.value.clearAtEpochMs)

        gate.onLive(false)
        val status = gate.status.value
        assertEquals(minute + grace, status.clearAtEpochMs)
        assertFalse(status.open)
        assertFalse(status.live)
        assertTrue(status.twitch)
        assertFalse(status.clearPostponed)

        wait(2 * minute)
        gate.onLive(true)
        assertNull(gate.status.value.clearAtEpochMs)
        assertTrue(gate.status.value.live)
        assertTrue(gate.status.value.open)
    }

    // A stream that was live when the app closed (stream.offline never came).

    @Test
    fun aSavedLiveStreamKeepsRequestsClosedUntilTwitchConfirmsIt() = runTest {
        val first = appScope()
        gate(scope = first).onLive(true)
        runCurrent() // the quit waits for the save
        first.cancel() // the app closes (or crashes, or the PC shuts down) while live

        val after = gate()
        runCurrent()
        assertFalse(after.open.value, "the saved live is a leftover until Twitch confirms it")
        assertFalse(after.status.value.live)
        after.onLive(true, read = true)
        assertTrue(after.open.value)
        assertTrue(after.status.value.live)
        assertEquals(0, cleared)
    }

    @Test
    fun aToggleStillOpensWhileTheSavedLiveIsUnconfirmed() = runTest {
        val first = appScope()
        gate(scope = first).onLive(true)
        runCurrent() // the quit waits for the save
        first.cancel()

        val after = gate()
        after.toggle()
        assertTrue(after.open.value, "the streamer's click decides")
        assertTrue(after.status.value.manual)
        after.onLive(true, read = true)
        assertTrue(after.open.value)
        after.toggle()
        assertFalse(after.open.value)
    }

    @Test
    fun aRestartLongAfterTheStreamEndedEndsItAtOnce() = runTest {
        val first = appScope()
        val before = gate(scope = first)
        before.onLive(true)
        before.toggle() // closed by hand during that stream
        wait(5 * minute)
        first.cancel() // closed while live
        wait(60 * minute)

        val after = gate()
        runCurrent()
        after.onLive(false, read = true) // Twitch: offline, since some time
        runCurrent()
        assertEquals(1, cleared, "it ended long ago: its free requests are cleared at once")
        assertNull(after.status.value.clearAtEpochMs)

        wait(2 * minute)
        after.onLive(true) // the next stream, within ten minutes of the restart: not a drop
        assertTrue(after.open.value, "the manual close of the last stream is over")
        assertFalse(after.status.value.manual)
        wait(grace)
        assertEquals(1, cleared)
    }

    @Test
    fun aQuickRestartCarriesOnWithTheGraceFromWhenTheStreamWasLastSeen() = runTest {
        val first = appScope()
        val before = gate(scope = first)
        before.onLive(true)
        wait(5 * minute) // the heartbeat remembers the stream live until now
        first.cancel() // a crash: no stop()
        wait(3 * minute)

        val after = gate()
        after.onLive(false, read = true)
        assertEquals(5 * minute + grace, after.status.value.clearAtEpochMs)
        wait(7 * minute - 1)
        assertEquals(0, cleared)
        wait(1)
        assertEquals(1, cleared)
    }

    @Test
    fun anOfflineEventAfterARestartStartsTheGraceNow() = runTest {
        val first = appScope()
        gate(scope = first).onLive(true)
        runCurrent() // the quit waits for the save
        first.cancel()
        wait(30 * minute)

        val after = gate()
        after.onLive(false) // stream.offline: it went offline just now
        assertEquals(30 * minute + grace, after.status.value.clearAtEpochMs)
        assertEquals(0, cleared)
    }

    @Test
    fun theStreamIsRememberedLiveEveryMinuteAndAtStop() = runTest {
        val gate = gate()
        gate.onLive(true)
        runCurrent()
        assertEquals(0L, saved().lastSeenLiveMs)
        wait(3 * minute)
        assertEquals(3 * minute, saved().lastSeenLiveMs)
        wait(minute / 2)
        gate.stop()
        runCurrent()
        assertEquals(3 * minute + minute / 2, saved().lastSeenLiveMs)
        wait(5 * minute)
        assertEquals(3 * minute + minute / 2, saved().lastSeenLiveMs, "no heartbeat after stop()")

        gate.onLive(false)
        gate.start()
        wait(5 * minute)
        assertEquals(3 * minute + minute / 2, saved().lastSeenLiveMs, "no heartbeat while offline")
    }

    /** Collects [RequestsGate.events] from now on. */
    private fun TestScope.eventsOf(gate: RequestsGate): List<GateEvent> {
        val seen = mutableListOf<GateEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.events.collect { seen += it } }
        return seen
    }

    @Test
    fun eventsFollowEveryChange() = runTest {
        val gate = gate()
        val events = eventsOf(gate)
        runCurrent()

        gate.onLive(true)
        wait(minute)
        gate.toggle() // close by hand
        gate.toggle() // open by hand
        gate.onLive(false)
        wait(grace)

        assertEquals(
            listOf(
                GateEvent.WentLive(0, byRead = false),
                GateEvent.ClosedByHand(minute),
                GateEvent.OpenedByHand(minute),
                GateEvent.WentOffline(minute),
                GateEvent.StreamEnded(minute + grace),
            ),
            events,
        )
        assertEquals(1, cleared)
    }

    @Test
    fun aLiveReadAndADropSayWhatHappened() = runTest {
        val gate = gate()
        val events = eventsOf(gate)
        runCurrent()

        gate.onLive(true, read = true)
        gate.onLive(true) // the same again: nothing new
        gate.onLive(false)
        wait(minute)
        gate.onLive(true) // back within the grace

        assertEquals(
            listOf(GateEvent.WentLive(0, byRead = true), GateEvent.WentOffline(0), GateEvent.WentLive(minute, byRead = false)),
            events,
        )
    }

    @Test
    fun aDropInTheGraceStillHoldsBackAnUpdateAtStart() = runTest {
        val first = gate()
        first.onLive(true)
        wait(5 * minute)
        first.onLive(false) // the stream dropped: the grace runs
        wait(minute)
        first.stop() // the streamer restarts Requestify
        runCurrent()
        val stream = nl.diments.requestify.core.update.streamAtStart(gate(), now = testScheduler.currentTime)
        val at = (stream as? nl.diments.requestify.core.update.StreamAtStart.LastSeenLive)?.atMs
        assertTrue(at != null && at in (4 * minute)..(6 * minute), "the drop is a live moment for the updater: $stream")
    }

    @Test
    fun theUpdaterLearnsWhetherTheFileWasReadable() = runTest {
        assertEquals(false, gate().loadedUnreadable, "a missing file is no problem: no stream seen yet")
        dir.resolve("stream.json").toFile().mkdirs() // reading it fails
        assertEquals(true, gate().loadedUnreadable)
    }

    @Test
    fun loadedLastSeenLiveIsKept() = runTest {
        val first = gate()
        assertNull(first.loadedLastSeenLiveMs, "nothing saved yet")
        first.onLive(true)
        wait(5 * minute)
        first.stop() // the app closes during the stream
        runCurrent() // the quit waits for the save

        val second = gate()
        assertEquals(5 * minute, second.loadedLastSeenLiveMs)
        second.onLive(false)
        second.stop()
        runCurrent()

        assertNull(gate().loadedLastSeenLiveMs, "saved offline: no live moment to report")
    }
}
