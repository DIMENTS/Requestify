package nl.diments.requestify.core.blacklist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track

class BlacklistTest {
    private fun track(artists: List<String>, ids: List<String>) =
        Track("t", "spotify:track:t", "Song", artists, 200_000, false, null, ids)

    @Test
    fun artistMatchesByIdOrByNameWithoutIds() {
        val byId = Blacklist(artists = listOf(BlockedArtist("a1", "Whatever", 1)))
        assertTrue(byId.blocksArtistOf(track(listOf("Renamed"), listOf("a1", "a2"))))
        assertFalse(byId.blocksArtistOf(track(listOf("Whatever"), listOf("a9"))))

        val byName = Blacklist(artists = listOf(BlockedArtist(null, "the weeknd", 1)))
        assertTrue(byName.blocksArtistOf(track(listOf("The Weeknd"), listOf("a1"))))
        assertFalse(byName.blocksArtistOf(track(listOf("M83"), listOf("a2"))))

        // a track saved before artist ids were kept is matched on name, even against an entry with an id
        assertTrue(byId.copy(artists = listOf(BlockedArtist("a1", "Artist X", 1)))
            .blocksArtistOf(track(listOf("artist x"), emptyList())))
    }

    @Test
    fun viewerMatchesByUserIdNotByName() {
        val list = Blacklist(viewers = listOf(BlockedViewer("u1", "kaas", 1)))
        assertTrue(list.blocksViewer(Requester("Renamed", "newname", "u1")))
        assertFalse(list.blocksViewer(Requester("Kaas", "kaas", "u2")))
        assertFalse(list.blocksViewer(Requester("Kaas", "kaas", null)))
    }
}
