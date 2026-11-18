package nl.diments.requestify.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A file that cannot be saved right now: since when, and why (for the banner, the Log and the tray). */
data class SaveProblem(val file: String, val reason: String, val sinceEpochMs: Long)

/** Where background saves run: [scope] for their lifetime, [io] for the writing itself. */
class SaveContext(val scope: CoroutineScope, val clock: () -> Long, val io: CoroutineDispatcher = Dispatchers.IO)

/**
 * Saves the newest value given to [save] off the caller's thread, the way the queue already saves: the caller never
 * waits for the disk. Values given while a write runs are coalesced into one next write, and an older value is never
 * written after a newer one. A failed write is retried on [backoffMs] (then its last value again) and shows in
 * [problem] until a write works.
 */
class BackgroundWriter<T>(
    val fileName: String,
    private val write: (T) -> Unit,
    private val context: SaveContext,
    private val backoffMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
) {
    private class Pending<T>(val value: T, val version: Long)

    private val lock = Any()

    /** Guarded by [lock]. */
    private var pending: Pending<T>? = null

    /** Guarded by [lock]: how many values were given. */
    private var givenVersion = 0L

    /** The version of the newest value on disk. */
    private val writtenVersion = MutableStateFlow(0L)

    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val _problem = MutableStateFlow<SaveProblem?>(null)

    /** Set after a failed write, kept while retrying, cleared by the write that works. */
    val problem: StateFlow<SaveProblem?> = _problem.asStateFlow()

    init {
        context.scope.launch(context.io) {
            for (wake in signal) writePending()
        }
    }

    /** Never blocks or suspends: the newest value wins. */
    fun save(value: T) {
        synchronized(lock) {
            givenVersion++
            pending = Pending(value, givenVersion)
        }
        signal.trySend(Unit)
    }

    /** Returns once every value given to [save] before this call is on disk. */
    suspend fun awaitSaved() {
        val target = synchronized(lock) { givenVersion }
        writtenVersion.first { it >= target }
    }

    /** [awaitSaved] with a deadline: true when everything was written in time. */
    suspend fun flush(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { awaitSaved() } != null

    private suspend fun writePending() {
        var attempt = 0
        while (true) {
            val next = synchronized(lock) { pending } ?: return
            try {
                write(next.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.toString()
                _problem.value = _problem.value?.copy(reason = reason) ?: SaveProblem(fileName, reason, context.clock())
                delay(backoffMs.getOrElse(attempt) { backoffMs.lastOrNull() ?: 30_000L })
                attempt++
                continue
            }
            synchronized(lock) { if (pending === next) pending = null }
            writtenVersion.value = next.version
            _problem.value = null
            attempt = 0
        }
    }
}
