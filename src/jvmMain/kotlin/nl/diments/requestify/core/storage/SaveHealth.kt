package nl.diments.requestify.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Every file that cannot be saved right now, for the one "can't save" banner, the Log, the tray and the notifier.
 * [problems] come from each store's background writer; [queueError] is the queue's own save error (its controller saves
 * queue.json itself), which counts from the moment it first appeared until it is gone.
 */
class SaveHealth(
    problems: List<StateFlow<SaveProblem?>>,
    queueError: StateFlow<String?>,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    /** When the queue's current save outage began; null while it saves. Guarded by this. */
    private var queueSince: Long? = null

    private val queueProblem: StateFlow<SaveProblem?> =
        queueError.map { queueProblemFor(it) }.stateIn(scope, SharingStarted.Eagerly, queueProblemFor(queueError.value))

    val problems: StateFlow<List<SaveProblem>> =
        combine(problems + queueProblem) { all -> all.filterNotNull() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    @Synchronized
    private fun queueProblemFor(error: String?): SaveProblem? {
        if (error == null) {
            queueSince = null
            return null
        }
        val since = queueSince ?: clock().also { queueSince = it }
        return SaveProblem(QUEUE_FILE, error, since)
    }

    private companion object {
        const val QUEUE_FILE = "queue.json"
    }
}
