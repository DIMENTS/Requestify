package nl.diments.requestify.core.log

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import nl.diments.requestify.core.storage.AppJson

/**
 * The Log on disk: one `yyyy-MM-dd.jsonl` file per local day under [dir], one [LogRecord] per line, appended. Files
 * older than [retentionDays] are deleted at [start] and once a day.
 *
 * [append] never waits and never fails: records wait in memory (at most [bufferMax]; the oldest go first) until the
 * writer on [io] has them on disk. A write that fails is tried again after 1 s, doubling to 60 s, while [writeError]
 * says why; nothing else ever waits on the Log.
 */
class LogStore(
    private val dir: Path,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retentionDays: Int = 30,
    private val bufferMax: Int = 10_000,
) {
    private val lock = Any()

    /** Records not on disk yet, with their sequence number, oldest first. Guarded by [lock]. */
    private val buffer = ArrayDeque<Pair<Long, LogRecord>>()
    private var nextSeq = 0L

    /** Records dropped because the buffer was full while writes failed. Guarded by [lock]. */
    private var lost = 0

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _writeError = MutableStateFlow<String?>(null)

    /** Why the Log cannot save right now; null while it can. */
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    private var writer: Job? = null
    private var cleaner: Job? = null

    fun append(record: LogRecord) {
        synchronized(lock) {
            if (buffer.size >= bufferMax) {
                buffer.removeFirst()
                lost++
            }
            buffer.addLast(nextSeq++ to record)
        }
        wake.trySend(Unit)
    }

    /** Every record within the retention, oldest day first, each day in the order written. A corrupt line is skipped. */
    suspend fun readAll(): List<LogRecord> = withContext(io) {
        if (!dir.isDirectory()) return@withContext emptyList()
        val oldest = today().minusDays(retentionDays.toLong())
        dayFiles().filter { (date, _) -> !date.isBefore(oldest) }.sortedBy { it.first }.flatMap { (_, file) ->
            val lines = try {
                file.readLines(StandardCharsets.UTF_8)
            } catch (_: IOException) {
                emptyList()
            }
            lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                try {
                    AppJson.instance.decodeFromString(LogRecord.serializer(), line)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (writer?.isActive != true) writer = scope.launch(io) { writeLoop() }
            if (cleaner?.isActive != true) {
                cleaner = scope.launch(io) {
                    while (true) {
                        deleteOld()
                        delay(DAY_MS)
                    }
                }
            }
        }
        wake.trySend(Unit)
    }

    /** Writes what it can once more, then stops. */
    suspend fun stop() {
        synchronized(lock) {
            writer?.cancel()
            cleaner?.cancel()
            writer = null
            cleaner = null
        }
        withContext(io) { writeBuffered() }
    }

    private suspend fun writeLoop() {
        var attempt = 0
        while (true) {
            if (writeBuffered()) {
                attempt = 0
                wake.receive()
            } else {
                delay(RETRY_MS.getOrElse(attempt) { RETRY_MS.last() })
                attempt++
            }
        }
    }

    /** One write at a time: [stop]'s last write must not write a batch the writer is still writing. */
    private val writeLock = Any()

    /** Writes the buffered records, day by day; true when all of them are on disk. One at a time ([writeLock]). */
    private fun writeBuffered(): Boolean = synchronized(writeLock) { writeBufferedLocked() }

    private fun writeBufferedLocked(): Boolean {
        val batch = synchronized(lock) { buffer.toList() }
        if (batch.isEmpty()) {
            if (synchronized(lock) { buffer.isEmpty() }) _writeError.value = null
            return true
        }
        for ((date, group) in consecutiveDays(batch)) {
            try {
                dir.createDirectories()
                val text = group.joinToString("") { AppJson.instance.encodeToString(LogRecord.serializer(), it.second) + "\n" }
                Files.write(
                    dir.resolve("$date.jsonl"),
                    text.toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                )
            } catch (e: IOException) {
                val dropped = synchronized(lock) { lost }
                _writeError.value = buildString {
                    append(e.message ?: e::class.simpleName ?: "error")
                    if (dropped > 0) append(" ($dropped records lost)")
                }
                return false
            }
            val written = group.mapTo(HashSet()) { it.first }
            synchronized(lock) { buffer.removeAll { it.first in written } }
        }
        val empty = synchronized(lock) {
            if (buffer.isEmpty()) lost = 0
            buffer.isEmpty()
        }
        if (empty) _writeError.value = null
        return empty
    }

    /** [batch] split into runs of the same local day, in order. */
    private fun consecutiveDays(batch: List<Pair<Long, LogRecord>>): List<Pair<LocalDate, List<Pair<Long, LogRecord>>>> {
        val runs = mutableListOf<Pair<LocalDate, MutableList<Pair<Long, LogRecord>>>>()
        for (item in batch) {
            val date = dayOf(item.second.atEpochMs)
            if (runs.lastOrNull()?.first == date) runs.last().second += item else runs += date to mutableListOf(item)
        }
        return runs
    }

    private fun deleteOld() {
        if (!dir.isDirectory()) return
        val oldest = today().minusDays(retentionDays.toLong())
        for ((date, file) in dayFiles()) {
            if (date.isBefore(oldest)) {
                try {
                    file.deleteIfExists()
                } catch (_: IOException) {
                    // tried again tomorrow
                }
            }
        }
    }

    private fun dayFiles(): List<Pair<LocalDate, Path>> = try {
        dir.listDirectoryEntries("*.jsonl").mapNotNull { file ->
            try {
                LocalDate.parse(file.name.removeSuffix(".jsonl")) to file
            } catch (_: DateTimeParseException) {
                null
            }
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun dayOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun today(): LocalDate = dayOf(clock())

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
        val RETRY_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L)
    }
}
