package nl.diments.requestify.core.spotify

import nl.diments.requestify.core.spotify.SpotifyInput.Empty
import nl.diments.requestify.core.spotify.SpotifyInput.NotATrack
import nl.diments.requestify.core.spotify.SpotifyInput.SearchText
import nl.diments.requestify.core.spotify.SpotifyInput.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ID = "4uLU6hMCjMI75M1A2tKUQC"

private fun classify(input: String) = classifySpotifyInput(input)

class SpotifyInputTest {
    @Test fun plainLink() = assertEquals(TrackId(ID), classify("https://open.spotify.com/track/$ID"))
    @Test fun intlLinkWithQuery() = assertEquals(TrackId(ID), classify("https://open.spotify.com/intl-nl/track/$ID?si=abc123"))
    @Test fun uri() = assertEquals(TrackId(ID), classify("spotify:track:$ID"))
    @Test fun linkInsideMessage() = assertEquals(TrackId(ID), classify("love this https://open.spotify.com/track/$ID !!"))
    @Test fun album() = assertEquals(NotATrack("album"), classify("https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX"))
    @Test fun playlist() = assertEquals(NotATrack("playlist"), classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    @Test fun episodeUri() = assertEquals(NotATrack("episode"), classify("spotify:episode:512ojhOuo1ktJprKbVcKyQ"))
    @Test fun searchText() = assertEquals(SearchText("daft punk one more time"), classify("  daft punk one more time "))
    @Test fun blank() = assertEquals(Empty, classify("   "))
    @Test fun malformedIdIsSearch() = assertEquals(SearchText("https://open.spotify.com/track/short"), classify("https://open.spotify.com/track/short"))
    @Test fun earliestLinkWinsAcrossForms() = assertEquals(
        NotATrack("album"),
        classify("spotify:album:1ATL5GLyefJaxhQzSPVrLX then https://open.spotify.com/track/$ID"),
    )
}
