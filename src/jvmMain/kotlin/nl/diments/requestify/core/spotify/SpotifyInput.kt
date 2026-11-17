package nl.diments.requestify.core.spotify

sealed interface SpotifyInput {
    data class TrackId(val id: String) : SpotifyInput
    data class ArtistId(val id: String) : SpotifyInput
    data class NotATrack(val kind: String) : SpotifyInput
    data class SearchText(val query: String) : SpotifyInput
    data object Empty : SpotifyInput
}

private const val KINDS = "track|album|playlist|episode|artist|show"
private const val ID = "([A-Za-z0-9]{22})(?![A-Za-z0-9])"

private val urlLink = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?($KINDS)/$ID""")
private val uriLink = Regex("spotify:($KINDS):$ID")

/** The first Spotify link anywhere in [input] wins, whether URL or `spotify:` URI form. */
fun classifySpotifyInput(input: String): SpotifyInput {
    val text = input.trim()
    if (text.isEmpty()) return SpotifyInput.Empty
    val link = listOfNotNull(urlLink.find(text), uriLink.find(text)).minByOrNull { it.range.first }
        ?: return SpotifyInput.SearchText(text)
    val (kind, id) = link.destructured
    return when (kind) {
        "track" -> SpotifyInput.TrackId(id)
        "artist" -> SpotifyInput.ArtistId(id)
        else -> SpotifyInput.NotATrack(kind)
    }
}
