package nl.diments.requestify.core.blacklist

import kotlinx.serialization.Serializable
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track

/** [by] is null for the streamer, otherwise the moderator's login. */
@Serializable
data class BlockedTrack(val track: Track, val atEpochMs: Long, val by: String? = null)

/** [id] is null for an artist blocked by name only. */
@Serializable
data class BlockedArtist(val id: String?, val name: String, val atEpochMs: Long, val by: String? = null)

@Serializable
data class BlockedViewer(val twitchUserId: String, val login: String, val atEpochMs: Long, val by: String? = null)

/** Saved in blacklist.json: new fields need defaults. */
@Serializable
data class Blacklist(
    val tracks: List<BlockedTrack> = emptyList(),
    val artists: List<BlockedArtist> = emptyList(),
    val viewers: List<BlockedViewer> = emptyList(),
) {
    /** By Twitch user id only: a renamed viewer stays blocked, a namesake is not. */
    fun blocksViewer(r: Requester): Boolean =
        r.twitchUserId != null && viewers.any { it.twitchUserId == r.twitchUserId }

    fun blocksTrack(t: Track): Boolean = tracks.any { it.track.id == t.id }

    /** By id; by case-insensitive name when the track or the entry has no id. */
    fun blocksArtistOf(t: Track): Boolean = artists.any { a ->
        if (a.id != null && t.artistIds.isNotEmpty()) {
            a.id in t.artistIds
        } else {
            t.artists.any { it.equals(a.name, ignoreCase = true) }
        }
    }
}
