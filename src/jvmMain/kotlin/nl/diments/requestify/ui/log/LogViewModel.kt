package nl.diments.requestify.ui.log

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.log.LogKind
import nl.diments.requestify.core.log.LogRow
import nl.diments.requestify.core.log.Logbook

enum class LogFilter { All, Requests, Connections, Problems }

/** The rows of one local day, newest first. */
data class LogDay(val date: LocalDate, val rows: List<LogRow>)

/**
 * The Logboek screen: the Log's rows by local day (newest first), narrowed by a [filter] and a search [query], and
 * the row whose details are open ([selected]). Filtering and grouping run on [work], never on the UI thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModel(
    rows: StateFlow<List<LogRow>>,
    /** Why the Log cannot save right now; null while it can. */
    val writeError: StateFlow<String?>,
    scope: CoroutineScope,
    /** The local time zone the rows are shown in. */
    val zone: ZoneId = ZoneId.systemDefault(),
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        logbook: Logbook,
        scope: CoroutineScope,
        zone: ZoneId = ZoneId.systemDefault(),
        work: CoroutineDispatcher = Dispatchers.Default,
    ) : this(logbook.rows, logbook.writeError, scope, zone, work)

    private val _filter = MutableStateFlow(LogFilter.All)
    private val _query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<String?>(null)

    val filter: StateFlow<LogFilter> = _filter.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()

    val days: StateFlow<List<LogDay>> = combine(rows, _filter, _query) { r, f, q -> Triple(r, f, q) }
        .mapLatest { (r, f, q) -> withContext(work) { daysOf(r, f, q) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val selected: StateFlow<LogRow?> = combine(rows, selectedId) { r, id -> id?.let { r.firstOrNull { row -> row.id == it } } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setFilter(f: LogFilter) {
        _filter.value = f
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** Opens the details of the row with [id]; null closes them. */
    fun select(id: String?) {
        selectedId.value = id
    }

    private fun daysOf(rows: List<LogRow>, filter: LogFilter, query: String): List<LogDay> {
        val q = query.trim().lowercase()
        return rows.asSequence()
            .filter { it.passes(filter) }
            .filter { q.isEmpty() || it.matches(q) }
            .groupBy { Instant.ofEpochMilli(it.atEpochMs).atZone(zone).toLocalDate() }
            .map { (date, dayRows) -> LogDay(date, dayRows) }
            .sortedByDescending { it.date }
    }

    private fun LogRow.passes(filter: LogFilter): Boolean = when (filter) {
        LogFilter.All -> true
        // A gap is about requests that may never have come in.
        LogFilter.Requests -> kind == LogKind.Request || kind == LogKind.Gap
        LogFilter.Connections -> kind == LogKind.Connection || kind == LogKind.Stream || kind == LogKind.App
        LogFilter.Problems -> isProblem
    }

    /** A search only finds requests: by who asked, the track, its artists, or what was typed. */
    private fun LogRow.matches(q: String): Boolean {
        if (kind != LogKind.Request) return false
        val received = received
        val track = track
        val haystack = listOfNotNull(received?.requester?.displayName, received?.input, track?.name) + track?.artists.orEmpty()
        return haystack.any { it.lowercase().contains(q) }
    }
}
