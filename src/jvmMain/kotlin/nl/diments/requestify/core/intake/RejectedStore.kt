package nl.diments.requestify.core.intake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.SaveProblem

/**
 * A request that was not let into the queue. [request] is the resolved request when there was one (for "Add anyway"),
 * null when the track could not be resolved. Saved in rejected.json: new fields need defaults.
 */
@Serializable
data class RejectedEntry(
    val id: String,
    val request: Request?,
    val input: String,
    val requester: Requester,
    val source: RequestSource,
    val payment: Payment?,
    val reason: RejectReason,
    val atEpochMs: Long,
    val refunded: Boolean,
)

/**
 * The Rejected list, newest first, capped at [MAX] entries: past the cap the oldest free entries go first, and paid
 * ones are never dropped (so the list can grow past [MAX] with paid entries only). Unreadable file at start: empty
 * and [loadFailed], nothing written over it. A change applies at once and is saved in the background; while the file cannot be read it is refused (false). Adding an id that
 * is there already, or removing one that is not, changes nothing and returns true.
 */
class RejectedStore(file: JsonFileStore<List<RejectedEntry>>, context: SaveContext) {
    private val state = PersistedState(file, context) { emptyList() }
    private val _paidCount = MutableStateFlow(paidIn(state.value.value))

    val entries: StateFlow<List<RejectedEntry>> get() = state.value
    val loadFailed: StateFlow<Boolean> get() = state.loadFailed

    /** Set while the file cannot be saved (the change stays and is retried). */
    val saveProblem: StateFlow<SaveProblem?> get() = state.saveProblem

    /** Returns once every change made before this call is on disk. */
    suspend fun awaitSaved() = state.awaitSaved()

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = state.flush(timeoutMs)

    /** The number of paid entries: the badge. */
    val paidCount: StateFlow<Int> = _paidCount.asStateFlow()

    @Synchronized
    fun add(entry: RejectedEntry): Boolean = change {
        if (it.any { e -> e.id == entry.id }) it else capped(listOf(entry) + it)
    }

    @Synchronized
    fun remove(id: String): Boolean = change { list -> list.filterNot { it.id == id } }

    private fun change(f: (List<RejectedEntry>) -> List<RejectedEntry>): Boolean {
        val ok = state.change(f)
        _paidCount.value = paidIn(state.value.value)
        return ok
    }

    private fun capped(newestFirst: List<RejectedEntry>): List<RejectedEntry> {
        var excess = newestFirst.size - MAX
        if (excess <= 0) return newestFirst
        val dropped = HashSet<Int>()
        for (i in newestFirst.indices.reversed()) {
            if (excess == 0) break
            if (newestFirst[i].payment == null) {
                dropped += i
                excess--
            }
        }
        return newestFirst.filterIndexed { i, _ -> i !in dropped }
    }

    private fun paidIn(list: List<RejectedEntry>) = list.count { it.payment != null }

    companion object {
        const val MAX = 50
    }
}
