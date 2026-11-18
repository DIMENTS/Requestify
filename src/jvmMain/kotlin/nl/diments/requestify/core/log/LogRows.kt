package nl.diments.requestify.core.log

import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.RemoveReason

enum class LogKind { Request, Connection, Stream, Gap }

/**
 * One row of the Log: every record with its [id], in time order. [atEpochMs] is when it began. A stream-end row counts
 * the free requests the clear took out ([clearedCount]) and the Channel Points refunds among them ([refundedCount]).
 */
data class LogRow(
    val id: String,
    val kind: LogKind,
    val atEpochMs: Long,
    val records: List<LogRecord>,
    val isProblem: Boolean,
    val clearedCount: Int = 0,
    val refundedCount: Int = 0,
) {
    /** The request as it came in; null for other rows. */
    val received: LogRecord.RequestReceived? get() = records.firstNotNullOfOrNull { it as? LogRecord.RequestReceived }

    /** Where the request stands now (its track aside); null for other rows. */
    val latestStep: LogStep?
        get() = records.lastOrNull { it is LogRecord.RequestStep && it.step !is LogStep.Resolved }
            ?.let { (it as LogRecord.RequestStep).step }

    val track: Track? get() = records.firstNotNullOfOrNull { ((it as? LogRecord.RequestStep)?.step as? LogStep.Resolved)?.track }
}

/** How long after a stream ended its clear's removals and refunds count for it. */
private const val CLEAR_WINDOW_MS = 2 * 60_000L

/**
 * Groups [records] into rows: newest row first (of rows begun in the same millisecond, the one known last first), each
 * row's records in time order.
 */
fun foldRows(records: List<LogRecord>): List<LogRow> {
    val grouped = LinkedHashMap<String, MutableList<LogRecord>>()
    for (record in records) grouped.getOrPut(record.rowId) { mutableListOf() } += record
    val rows = grouped.map { (id, list) -> rowOf(id, list.sortedBy { it.atEpochMs }) }
    val counted = rows.map { if (it.kind == LogKind.Stream && it.endsStream()) it.withClearCounts(rows) else it }
    return counted.withIndex()
        .sortedWith(compareByDescending<IndexedValue<LogRow>> { it.value.atEpochMs }.thenByDescending { it.index })
        .map { it.value }
}

private fun rowOf(id: String, records: List<LogRecord>): LogRow {
    val kind = when (records.first()) {
        is LogRecord.RequestReceived, is LogRecord.RequestStep -> LogKind.Request
        is LogRecord.ConnectionChanged -> LogKind.Connection
        is LogRecord.StreamChanged -> LogKind.Stream
        is LogRecord.GapNoticed -> LogKind.Gap
    }
    val row = LogRow(id, kind, records.first().atEpochMs, records, isProblem = false)
    return row.copy(isProblem = row.problem())
}

private fun LogRow.problem(): Boolean = when (kind) {
    LogKind.Request -> {
        val steps = records.mapNotNull { (it as? LogRecord.RequestStep)?.step }
        val spotifyDown = steps.any { it is LogStep.Rejected && it.reason == RejectReason.SpotifyUnavailable }
        spotifyDown || when (latestStep) {
            is LogStep.Rejected, LogStep.Pending, is LogStep.RefundPending, LogStep.RefundGaveUp -> true
            else -> false
        }
    }
    LogKind.Connection -> (records.last() as LogRecord.ConnectionChanged).state != LogConnectionState.Restored
    LogKind.Stream -> false
    LogKind.Gap -> true
}

private fun LogRow.endsStream() = records.any { it is LogRecord.StreamChanged && it.change == LogStreamChange.StreamEnded }

private fun LogRow.withClearCounts(rows: List<LogRow>): LogRow {
    val window = atEpochMs..atEpochMs + CLEAR_WINDOW_MS
    val cleared = rows.filter { row ->
        row.records.any {
            it is LogRecord.RequestStep && it.atEpochMs in window &&
                (it.step as? LogStep.Removed)?.why == RemoveReason.StreamEnded
        }
    }
    val refunded = cleared.count { row -> row.records.any { it is LogRecord.RequestStep && it.step == LogStep.Refunded } }
    return copy(clearedCount = cleared.size, refundedCount = refunded)
}
