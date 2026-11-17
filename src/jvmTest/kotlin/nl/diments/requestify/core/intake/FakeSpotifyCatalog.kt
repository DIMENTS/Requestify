package nl.diments.requestify.core.intake

import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.spotify.ArtistRef
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult

/** A scriptable [SpotifyCatalog] that records its calls. */
class FakeSpotifyCatalog : SpotifyCatalog {
    val calls = mutableListOf<String>()

    /** What [track] answers; a track not listed is [SpotifyResult.NotFound]. */
    val tracks = mutableMapOf<String, Track>()
    var searchResult: SpotifyResult<List<Track>> = SpotifyResult.Ok(emptyList())

    /** While set, every answer is this failure. */
    var failure: SpotifyResult<Nothing>? = null

    override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> {
        calls += "search:$query:$limit"
        return failure ?: searchResult
    }

    override suspend fun track(id: String): SpotifyResult<Track> {
        calls += "track:$id"
        return failure ?: tracks[id]?.let { SpotifyResult.Ok(it) } ?: SpotifyResult.NotFound
    }

    override suspend fun searchArtists(query: String, limit: Int): SpotifyResult<List<ArtistRef>> = error("not used")
    override suspend fun artist(id: String): SpotifyResult<ArtistRef> = error("not used")
    override suspend fun isPremium(): SpotifyResult<Boolean?> = error("not used")
}
