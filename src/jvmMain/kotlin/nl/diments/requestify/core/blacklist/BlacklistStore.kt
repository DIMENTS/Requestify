package nl.diments.requestify.core.blacklist

import kotlinx.coroutines.flow.StateFlow
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.SaveProblem

/**
 * The blacklist in blacklist.json. Unreadable file at start: empty and [loadFailed], nothing written over it, and a
 * change returns false (nothing changes) until the file could be read. A save that fails keeps the change in memory,
 * is retried in the background and shows in [saveProblem]. Adding what is blocked already, or removing what is not
 * blocked, changes nothing and returns true.
 */
class BlacklistStore(file: JsonFileStore<Blacklist>, context: SaveContext) {
    private val state = PersistedState(file, context) { Blacklist() }

    val blacklist: StateFlow<Blacklist> get() = state.value
    val loadFailed: StateFlow<Boolean> get() = state.loadFailed

    /** Set while the file cannot be saved (the change stays and is retried). */
    val saveProblem: StateFlow<SaveProblem?> get() = state.saveProblem

    /** Returns once every change made before this call is on disk. */
    suspend fun awaitSaved() = state.awaitSaved()

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = state.flush(timeoutMs)

    fun addTrack(track: Track, by: String?, at: Long): Boolean = state.change {
        if (it.tracks.any { b -> b.track.id == track.id }) it else it.copy(tracks = it.tracks + BlockedTrack(track, at, by))
    }

    /** [id] is null to block by name only. */
    fun addArtist(id: String?, name: String, by: String?, at: Long): Boolean = state.change {
        if (it.artists.any { a -> sameArtist(a, id, name) }) it else it.copy(artists = it.artists + BlockedArtist(id, name, at, by))
    }

    fun addViewer(userId: String, login: String, by: String?, at: Long): Boolean = state.change {
        if (it.viewers.any { v -> v.twitchUserId == userId }) it else it.copy(viewers = it.viewers + BlockedViewer(userId, login, at, by))
    }

    fun removeTrack(trackId: String): Boolean = state.change { it.copy(tracks = it.tracks.filterNot { b -> b.track.id == trackId }) }

    /** [idOrName] is an artist id, or the name of an artist blocked by name only. */
    fun removeArtist(idOrName: String): Boolean = state.change {
        it.copy(artists = it.artists.filterNot { a -> a.id == idOrName || (a.id == null && a.name.equals(idOrName, ignoreCase = true)) })
    }

    fun removeViewer(userId: String): Boolean = state.change { it.copy(viewers = it.viewers.filterNot { v -> v.twitchUserId == userId }) }

    private fun sameArtist(a: BlockedArtist, id: String?, name: String) =
        if (id != null) a.id == id else a.id == null && a.name.equals(name, ignoreCase = true)
}
