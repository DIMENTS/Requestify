package nl.diments.requestify.core.storage

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A value kept in a [JsonFileStore]. A change applies in memory at once and is saved in the background
 * ([BackgroundWriter]): the caller never waits for the disk, and a save that fails is retried and shows in
 * [saveProblem]. When the saved file cannot be read at start, the value is the default and [loadFailed] is true: the
 * file is read again every [rereadEveryMs] in the background, changes are refused until a read succeeds, and the
 * default is never written over the saved file.
 */
class PersistedState<T : Any>(
    private val file: JsonFileStore<T>,
    private val context: SaveContext,
    private val rereadEveryMs: Long = 5_000,
    default: () -> T,
) {
    private val loaded: T? = try {
        file.read()
    } catch (_: StoreReadException) {
        null
    }
    private val state = MutableStateFlow(loaded ?: default())
    private val _loadFailed = MutableStateFlow(loaded == null)
    private val writer = BackgroundWriter(file.fileName, file::write, context)

    val value: StateFlow<T> = state.asStateFlow()

    /** True while the saved file could not be read and the default is used instead. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** Set while the file cannot be saved. */
    val saveProblem: StateFlow<SaveProblem?> get() = writer.problem

    init {
        if (loaded == null) context.scope.launch(context.io) { readAgainUntilItWorks() }
    }

    /**
     * Applies [f] to the current value and hands the result to the background save. [f] returns the same value when
     * there is nothing to change; nothing is saved then. False, with the value unchanged, only while the saved file
     * could not be read yet.
     */
    @Synchronized
    fun change(f: (T) -> T): Boolean {
        if (_loadFailed.value) return false
        val next = f(state.value)
        if (next != state.value) {
            state.value = next
            writer.save(next)
        }
        return true
    }

    /** Returns once every change made before this call is on disk. */
    suspend fun awaitSaved() = writer.awaitSaved()

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = writer.flush(timeoutMs)

    private suspend fun readAgainUntilItWorks() {
        while (true) {
            delay(rereadEveryMs)
            val read = try {
                file.read()
            } catch (_: StoreReadException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
            synchronized(this) {
                state.value = read
                _loadFailed.value = false
            }
            return
        }
    }
}
