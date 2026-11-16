package nl.diments.requestify.core.queue

import kotlinx.serialization.Serializable
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.storage.JsonFileStore

@Serializable
data class PlayedEntry(val request: Request, val playedAtEpochMs: Long)

@Serializable
data class PersistedPlayback(
    val queue: RequestQueue = RequestQueue(),
    val handedOver: Request? = null,
    val playing: Request? = null,
    val history: List<PlayedEntry> = emptyList(),
)

class QueueStore(private val file: JsonFileStore<PersistedPlayback>) {
    /** Throws [nl.diments.requestify.core.storage.StoreReadException] while the file cannot be read; never save then. */
    fun load(): PersistedPlayback = file.read()

    /** Keeps only the [MAX_HISTORY] most recently played history entries. */
    fun save(p: PersistedPlayback) {
        val capped = p.history.sortedByDescending { it.playedAtEpochMs }.take(MAX_HISTORY)
        file.write(p.copy(history = capped))
    }

    companion object {
        /** How many played entries are kept. */
        const val MAX_HISTORY = 50
    }
}
