package nl.diments.requestify.core.log

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.intake.IntakeRecord
import nl.diments.requestify.core.model.ChannelPointsRef
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.RequestEvent
import nl.diments.requestify.core.stream.GateEvent
import nl.diments.requestify.core.twitch.RedemptionStatus
import nl.diments.requestify.core.twitch.points.OutboxResult

/** A connection as the Log sees it; a change from one to another may be news. */
enum class ConnectionSnapshot { Up, Down, LoginExpired, KeyRefused, KeyExpiring }

/** Everything the Log listens to. None of these replays: the Logbook subscribes before anything can happen. */
data class LogInputs(
    val intake: Flow<IntakeRecord> = emptyFlow(),
    val requests: Flow<RequestEvent> = emptyFlow(),
    val refunds: Flow<OutboxResult> = emptyFlow(),
    val gate: Flow<GateEvent> = emptyFlow(),
    val connections: Flow<Pair<LogService, ConnectionSnapshot>> = emptyFlow(),
    val gaps: Flow<LogRecord.GapNoticed> = emptyFlow(),
)

/**
 * The Log as the app keeps it: it follows what the app already reports ([inputs]) and turns it into [LogRecord]s,
 * written by [store] and folded into [rows] on [folding]. Events are handled one at a time, in the order they came,
 * after the saved records are read, so a request that came in before a restart keeps its one row.
 */
class Logbook(
    private val store: LogStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val inputs: LogInputs,
    private val folding: CoroutineDispatcher = Dispatchers.Default,
    private val retentionDays: Int = 30,
) {
    private val lock = Any()

    /** Every record in the retention, in the order known; guarded by [lock]. */
    private val records = ArrayList<LogRecord>()

    // Only the worker below touches these.
    private val receivedRows = HashSet<String>()
    private val resolvedRows = HashSet<String>()
    private val redemptionRows = HashMap<String, String>()
    private val connectionNow = HashMap<LogService, ConnectionSnapshot>()
    private val openOutage = HashMap<LogService, String>()

    private val changed = MutableStateFlow(0L)
    private val _rows = MutableStateFlow<List<LogRow>>(emptyList())

    /** The Log's rows, newest first. */
    val rows: StateFlow<List<LogRow>> = _rows.asStateFlow()

    /** Why the Log cannot save right now; null while it can. */
    val writeError: StateFlow<String?> = store.writeError

    private var job: Job? = null

    fun start() {
        synchronized(lock) { if (job?.isActive == true) return }
        store.start()
        val launched = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val inbox = Channel<Any>(Channel.UNLIMITED)
            // Subscribed before anything else runs: nothing these report is missed.
            launch(start = CoroutineStart.UNDISPATCHED) { inputs.intake.collect { inbox.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { inputs.requests.collect { inbox.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { inputs.refunds.collect { inbox.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { inputs.gate.collect { inbox.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { inputs.connections.collect { inbox.trySend(ConnectionChange(it.first, it.second)) } }
            launch(start = CoroutineStart.UNDISPATCHED) { inputs.gaps.collect { inbox.trySend(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) {
                changed.collect {
                    val snapshot = synchronized(lock) { records.toList() }
                    _rows.value = withContext(folding) { foldRows(snapshot) }
                }
            }
            val saved = store.readAll()
            synchronized(lock) { records.addAll(0, saved) }
            saved.forEach(::index)
            changed.value++
            // Once a day what is older than the retention leaves memory too, like its day files leave the disk.
            launch {
                while (true) {
                    delay(DAY_MS)
                    val cutoff = clock() - retentionDays * DAY_MS
                    val removed = synchronized(lock) { records.removeAll { it.atEpochMs < cutoff } }
                    if (removed) changed.value++
                }
            }
            for (event in inbox) {
                try {
                    handle(event)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // one odd event never ends the Log
                }
            }
        }
        synchronized(lock) { job = launched }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    private data class ConnectionChange(val service: LogService, val snapshot: ConnectionSnapshot)

    private fun handle(event: Any) {
        when (event) {
            is IntakeRecord -> onIntake(event)
            is RequestEvent -> onRequest(event)
            is OutboxResult -> onRefund(event)
            is GateEvent -> onGate(event)
            is ConnectionChange -> onConnection(event.service, event.snapshot)
            is LogRecord.GapNoticed -> add(event)
        }
    }

    private fun onIntake(r: IntakeRecord) {
        val incoming = r.incoming
        val id = incoming.id
        val at = r.atEpochMs
        received(id, at, incoming.source, incoming.requester, incoming.input, incoming.payment, incoming.sourceRef)
        when (val o = r.outcome) {
            is IntakeOutcome.Accepted -> {
                resolved(id, at, o.request.track)
                add(LogRecord.RequestStep(id, at, LogStep.Queued(o.position)))
            }
            is IntakeOutcome.Rejected -> {
                o.track?.let { resolved(id, at, it) }
                add(LogRecord.RequestStep(id, at, LogStep.Rejected(o.reason, o.listed)))
            }
            IntakeOutcome.Ignored -> add(LogRecord.RequestStep(id, at, LogStep.Ignored))
            IntakeOutcome.Pending -> add(LogRecord.RequestStep(id, at, LogStep.Pending))
        }
    }

    private fun onRequest(event: RequestEvent) {
        val (request, step) = when (event) {
            is RequestEvent.HandedOver -> event.request to LogStep.HandedOver
            is RequestEvent.Started -> event.request to LogStep.Played
            is RequestEvent.Removed -> event.request to LogStep.Removed(event.why)
        }
        val at = clock()
        // A request queued before the Log existed (or the app updated) still gets its row.
        received(request.id, at, request.source, request.requester, request.track.uri, request.payment, request.sourceRef)
        resolved(request.id, at, request.track)
        add(LogRecord.RequestStep(request.id, at, step))
    }

    private fun onRefund(result: OutboxResult) {
        val row = redemptionRows[result.redemptionId] ?: return
        val refund = result.status == RedemptionStatus.CANCELED
        val step = when (result) {
            is OutboxResult.Sent -> if (refund) LogStep.Refunded else LogStep.Fulfilled
            is OutboxResult.Retrying -> if (refund) LogStep.RefundPending(result.tries) else return
            is OutboxResult.GaveUp -> if (refund) LogStep.RefundGaveUp else return
        }
        add(LogRecord.RequestStep(row, clock(), step))
    }

    private fun onGate(event: GateEvent) {
        val change = when (event) {
            is GateEvent.WentLive -> LogStreamChange.Live
            is GateEvent.WentOffline -> LogStreamChange.Offline
            is GateEvent.OpenedByHand -> LogStreamChange.OpenedByHand
            is GateEvent.ClosedByHand -> LogStreamChange.ClosedByHand
            is GateEvent.StreamEnded -> LogStreamChange.StreamEnded
        }
        // Two changes in the same millisecond (offline and the end of a stream long gone) stay two rows.
        add(LogRecord.StreamChanged("stream:${event.atEpochMs}:${change.name}", event.atEpochMs, change))
    }

    private fun onConnection(service: LogService, snapshot: ConnectionSnapshot) {
        if (connectionNow[service] == snapshot) return
        connectionNow[service] = snapshot
        val now = clock()
        val open = openOutage[service]
        if (snapshot == ConnectionSnapshot.Up) {
            if (open != null) {
                add(LogRecord.ConnectionChanged(open, now, service, LogConnectionState.Restored))
                openOutage.remove(service)
            }
            return
        }
        val state = when (snapshot) {
            ConnectionSnapshot.Down -> LogConnectionState.Lost
            ConnectionSnapshot.LoginExpired -> LogConnectionState.LoginExpired
            ConnectionSnapshot.KeyRefused -> LogConnectionState.KeyRefused
            else -> LogConnectionState.KeyExpiring
        }
        val row = open ?: "conn:${service.name}:$now".also { openOutage[service] = it }
        add(LogRecord.ConnectionChanged(row, now, service, state))
    }

    private fun received(
        id: String,
        at: Long,
        source: RequestSource,
        requester: Requester,
        input: String,
        payment: Payment?,
        sourceRef: String?,
    ) {
        if (id in receivedRows) return
        add(LogRecord.RequestReceived(id, at, source, requester, input, payment, sourceRef))
    }

    private fun resolved(id: String, at: Long, track: Track) {
        if (id in resolvedRows) return
        add(LogRecord.RequestStep(id, at, LogStep.Resolved(track)))
    }

    private fun add(record: LogRecord) {
        synchronized(lock) { records += record }
        index(record)
        store.append(record)
        changed.value++
    }

    /** Remembers what later events need to find their row. */
    private fun index(record: LogRecord) {
        when (record) {
            is LogRecord.RequestReceived -> {
                receivedRows += record.rowId
                ChannelPointsRef.parse(record.sourceRef)?.let { redemptionRows[it.redemptionId] = record.rowId }
            }
            is LogRecord.RequestStep -> if (record.step is LogStep.Resolved) resolvedRows += record.rowId
            // An outage still open when the app last closed is closed by the next "up" of this run.
            is LogRecord.ConnectionChanged ->
                if (record.state == LogConnectionState.Restored) openOutage.remove(record.service) else openOutage[record.service] = record.rowId
            else -> Unit
        }
    }
}

private const val DAY_MS = 24 * 3_600_000L
