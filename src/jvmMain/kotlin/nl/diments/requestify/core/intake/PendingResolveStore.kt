package nl.diments.requestify.core.intake

import kotlinx.coroutines.flow.StateFlow
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState

/**
 * Paid requests waiting for Spotify to answer, so they survive a restart. Unreadable file at start: empty and
 * [loadFailed], nothing written over it. A write that fails returns false and changes nothing. Adding an id that
 * is there already, or removing one that is not, changes nothing and returns true.
 */
class PendingResolveStore(file: JsonFileStore<List<IncomingRequest>>) {
    private val state = PersistedState(file) { emptyList() }

    val pending: StateFlow<List<IncomingRequest>> get() = state.value
    val loadFailed: StateFlow<Boolean> get() = state.loadFailed

    fun add(r: IncomingRequest): Boolean = state.change { list -> if (list.any { it.id == r.id }) list else list + r }

    fun remove(id: String): Boolean = state.change { list -> list.filterNot { it.id == id } }
}
