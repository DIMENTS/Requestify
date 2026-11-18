package nl.diments.requestify.core.intake

import kotlinx.coroutines.flow.StateFlow
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.SaveProblem

/**
 * Paid requests waiting for Spotify to answer, so they survive a restart. Unreadable file at start: empty and
 * [loadFailed], nothing written over it. A change applies at once and is saved in the background; while the file cannot be read it is refused (false). Adding an id that
 * is there already, or removing one that is not, changes nothing and returns true.
 */
class PendingResolveStore(file: JsonFileStore<List<IncomingRequest>>, context: SaveContext) {
    private val state = PersistedState(file, context) { emptyList() }

    val pending: StateFlow<List<IncomingRequest>> get() = state.value
    val loadFailed: StateFlow<Boolean> get() = state.loadFailed

    /** Set while the file cannot be saved (the change stays and is retried). */
    val saveProblem: StateFlow<SaveProblem?> get() = state.saveProblem

    /** Returns once every change made before this call is on disk. */
    suspend fun awaitSaved() = state.awaitSaved()

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = state.flush(timeoutMs)

    fun add(r: IncomingRequest): Boolean = state.change { list -> if (list.any { it.id == r.id }) list else list + r }

    fun remove(id: String): Boolean = state.change { list -> list.filterNot { it.id == id } }
}
