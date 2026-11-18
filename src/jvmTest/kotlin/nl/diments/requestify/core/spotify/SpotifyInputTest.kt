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
    @Test fun artistLinkClassifiesAsArtistId() = assertEquals(
        SpotifyInput.ArtistId("0OdUWJ0sBjDrqHygGUXeCF"),
        classify("https://open.spotify.com/intl-nl/artist/0OdUWJ0sBjDrqHygGUXeCF?si=x"),
    )
    @Test fun playlist() = assertEquals(NotATrack("playlist"), classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    @Test fun episodeUri() = assertEquals(NotATrack("episode"), classify("spotify:episode:512ojhOuo1ktJprKbVcKyQ"))
    @Test fun searchText() = assertEquals(SearchText("daft punk one more time"), classify("  daft punk one more time "))
    @Test fun blank() = assertEquals(Empty, classify("   "))
    @Test fun malformedIdIsSearch() = assertEquals(SearchText("https://open.spotify.com/track/short"), classify("https://open.spotify.com/track/short"))
    @Test fun shortLink() = assertEquals(SpotifyInput.ShortLink("https://spotify.link/AbC12_x-9"), classify("https://spotify.link/AbC12_x-9"))
    @Test fun shortLinkWithoutSchemeInsideMessage() =
        assertEquals(SpotifyInput.ShortLink("https://spotify.link/hRkBrwub9xb"), classify("Cheer100 spotify.link/hRkBrwub9xb pls"))
    @Test fun appShortLink() =
        assertEquals(SpotifyInput.ShortLink("https://spotify.app.link/hRkBrwub9xb"), classify("http://spotify.app.link/hRkBrwub9xb?_p=c2"))
    @Test fun shortLinkHostMustBeSpotifys() = assertEquals(SearchText("notspotify.link/abc"), classify("notspotify.link/abc"))
    @Test fun earliestLinkWinsWithShortLinks() = assertEquals(
        SpotifyInput.ShortLink("https://spotify.link/first"),
        classify("spotify.link/first or https://open.spotify.com/track/$ID"),
    )
    @Test fun earliestLinkWinsAcrossForms() = assertEquals(
        NotATrack("album"),
        classify("spotify:album:1ATL5GLyefJaxhQzSPVrLX then https://open.spotify.com/track/$ID"),
    )
}
