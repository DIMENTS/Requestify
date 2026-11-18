package nl.diments.requestify.core.log

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.intake.IntakeRecord
import nl.diments.requestify.core.model.ChannelPointsRef
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.playback.RequestEvent
import nl.diments.requestify.core.stream.GateEvent
import nl.diments.requestify.core.twitch.RedemptionStatus
import nl.diments.requestify.core.twitch.points.OutboxResult

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class LogbookTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-logbook") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val intake = MutableSharedFlow<IntakeRecord>(extraBufferCapacity = 64)
    private val requests = MutableSharedFlow<RequestEvent>(extraBufferCapacity = 64)
    private val refunds = MutableSharedFlow<OutboxResult>(extraBufferCapacity = 64)
    private val gate = MutableSharedFlow<GateEvent>(extraBufferCapacity = 64)
    private val connections = MutableSharedFlow<Pair<LogService, ConnectionSnapshot>>(extraBufferCapacity = 64)
    private val gaps = MutableSharedFlow<LogRecord.GapNoticed>(extraBufferCapacity = 64)

    private val track = Track("t1", "spotify:track:t1", "Mr. Brightside", listOf("The Killers"), 222_000, false, null)

    private fun TestScope.store() = LogStore(
        dir.resolve("log"), backgroundScope, clock = { testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.logbook(store: LogStore = store()) = Logbook(
        store = store,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        inputs = LogInputs(intake, requests, refunds, gate, connections, gaps),
        folding = StandardTestDispatcher(testScheduler),
    ).also {
        it.start()
        runCurrent()
    }

    private fun incoming(id: String, source: RequestSource = RequestSource.CHAT, sourceRef: String? = null) =
        IncomingRequest(id, "https://open.spotify.com/track/t1", false, Requester("Jan"), source, null, sourceRef, 1L)

    private fun request(id: String, sourceRef: String? = null) =
        Request(id, track, Requester("Jan"), RequestSource.CHAT, null, createdAtEpochMs = 1L, sourceRef = sourceRef)

    private fun TestScope.emit(record: IntakeOutcome, id: String, sourceRef: String? = null) {
        intake.tryEmit(IntakeRecord(incoming(id, sourceRef = sourceRef), record, testScheduler.currentTime))
        runCurrent()
    }

    private fun Logbook.row(id: String) = rows.value.single { it.id == id }

    @Test
    fun everyOutcomeBecomesARow() = runTest {
        val log = logbook()
        emit(IntakeOutcome.Accepted(request("a"), 2, 0), "a")
        emit(IntakeOutcome.Rejected(RejectReason.TooLong(420_000), track, listed = true), "r")
        emit(IntakeOutcome.Ignored, "i")
        emit(IntakeOutcome.Pending, "p")

        assertEquals(4, log.rows.value.size)
        assertEquals(LogStep.Queued(2), log.row("a").latestStep)
        assertEquals(track, log.row("a").track)
        assertEquals(LogStep.Rejected(RejectReason.TooLong(420_000), listed = true), log.row("r").latestStep)
        assertEquals(track, log.row("r").track)
        assertEquals(LogStep.Ignored, log.row("i").latestStep)
        assertEquals(LogStep.Pending, log.row("p").latestStep)
        assertEquals(RequestSource.CHAT, log.row("a").received?.source)
    }

    @Test
    fun queueEventsUpdateTheSameRow() = runTest {
        val log = logbook()
        emit(IntakeOutcome.Accepted(request("a"), 1, 0), "a")
        requests.tryEmit(RequestEvent.HandedOver(request("a")))
        requests.tryEmit(RequestEvent.Started(request("a")))
        runCurrent()

        val row = log.rows.value.single()
        assertEquals(LogStep.Played, row.latestStep)
        assertTrue(row.records.any { (it as? LogRecord.RequestStep)?.step == LogStep.HandedOver })
    }

    @Test
    fun aRequestTheLogNeverSawComingStillGetsARow() = runTest {
        val log = logbook()
        requests.tryEmit(RequestEvent.Removed(request("old"), RemoveReason.Streamer)) // queued before the Log existed
        runCurrent()

        val row = log.row("old")
        assertEquals(LogStep.Removed(RemoveReason.Streamer), row.latestStep)
        assertEquals(track, row.track)
        assertEquals("Jan", row.received?.requester?.displayName)
    }

    @Test
    fun aRefundUpdatesTheChannelPointsRow() = runTest {
        val log = logbook()
        val ref = ChannelPointsRef("rw1", "red1", managed = true).format()
        emit(IntakeOutcome.Accepted(request("cp1", ref), 1, 0), "cp1", sourceRef = ref)
        requests.tryEmit(RequestEvent.Removed(request("cp1", ref), RemoveReason.WrongSong))
        refunds.tryEmit(OutboxResult.Retrying("red1", RedemptionStatus.CANCELED, tries = 1))
        runCurrent()
        assertEquals(LogStep.RefundPending(1), log.row("cp1").latestStep)
        assertTrue(log.row("cp1").isProblem)

        refunds.tryEmit(OutboxResult.Sent("red1", RedemptionStatus.CANCELED))
        runCurrent()
        assertEquals(LogStep.Refunded, log.row("cp1").latestStep)
        assertFalse(log.row("cp1").isProblem)
    }

    @Test
    fun anOutageIsLoggedOnceAndClosedOnRestore() = runTest {
        val log = logbook()
        connections.tryEmit(LogService.Twitch to ConnectionSnapshot.Up)
        runCurrent()
        assertTrue(log.rows.value.isEmpty(), "being up is no news")

        connections.tryEmit(LogService.Twitch to ConnectionSnapshot.Down)
        connections.tryEmit(LogService.Twitch to ConnectionSnapshot.Down)
        runCurrent()
        val down = log.rows.value.single()
        assertEquals(LogKind.Connection, down.kind)
        assertTrue(down.isProblem)

        connections.tryEmit(LogService.Twitch to ConnectionSnapshot.Up)
        runCurrent()
        val up = log.rows.value.single()
        assertEquals(
            listOf(LogConnectionState.Lost, LogConnectionState.Restored),
            up.records.map { (it as LogRecord.ConnectionChanged).state },
        )
        assertFalse(up.isProblem)
    }

    @Test
    fun gateEventsBecomeStreamRows() = runTest {
        val log = logbook()
        gate.tryEmit(GateEvent.WentLive(1, byRead = false))
        gate.tryEmit(GateEvent.WentOffline(2))
        gate.tryEmit(GateEvent.StreamEnded(2)) // the same moment as the offline: its own row
        gate.tryEmit(GateEvent.OpenedByHand(3))
        runCurrent()

        assertEquals(
            listOf(LogStreamChange.OpenedByHand, LogStreamChange.StreamEnded, LogStreamChange.Offline, LogStreamChange.Live),
            log.rows.value.map { (it.records.single() as LogRecord.StreamChanged).change },
        )
    }

    @Test
    fun gapsAreKeptAsTheyCome() = runTest {
        val log = logbook()
        val gap = LogRecord.GapNoticed("gap:BITS:9", 9, RequestSource.BITS, 1, 9)
        gaps.tryEmit(gap)
        runCurrent()
        assertEquals(listOf(gap), log.rows.value.single().records)
    }

    @Test
    fun rowsSurviveARestart() = runTest {
        val store = store()
        val first = logbook(store)
        emit(IntakeOutcome.Accepted(request("a"), 1, 0), "a")
        gate.tryEmit(GateEvent.WentLive(5, byRead = true))
        runCurrent()
        val before = first.rows.value
        first.stop()
        store.stop()

        val second = logbook(store())
        assertEquals(before, second.rows.value)
    }

    @Test
    fun foldingTenThousandRecordsStaysFast() {
        val records = (0 until 10_000).flatMap { i ->
            listOf(
                LogRecord.RequestReceived("r$i", i.toLong(), RequestSource.CHAT, Requester("V$i"), "x", Payment.Bits(100)),
                LogRecord.RequestStep("r$i", i.toLong(), LogStep.Queued(1)),
            )
        } + LogRecord.StreamChanged("stream:end", 9_000, LogStreamChange.StreamEnded)
        lateinit var rows: List<LogRow>
        val took = measureTime { rows = foldRows(records) }
        assertEquals(10_001, rows.size)
        assertTrue(took.inWholeMilliseconds < 1_000, "folded in $took")
    }

    @Test
    fun oldRecordsLeaveMemoryOnceADay() = runTest {
        val log = logbook()
        gate.tryEmit(GateEvent.WentLive(0, byRead = false))
        runCurrent()
        assertEquals(1, log.rows.value.size)

        advanceTimeBy(31 * 24 * 3_600_000L)
        runCurrent()
        assertEquals(emptyList(), log.rows.value, "older than 30 days: gone from the screen too, not only from disk")
    }
}
