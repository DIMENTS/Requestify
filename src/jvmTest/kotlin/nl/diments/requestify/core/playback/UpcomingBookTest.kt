package nl.diments.requestify.core.playback

import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpcomingBookTest {
    private var now = 0L
    private val book = UpcomingBook(PlaybackConfig(upcomingRefreshMs = 30_000)) { now }

    private fun track(id: String) = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null)

    @Test
    fun fromLeavesOutHandedOverAndRepeatsCappedAt15() {
        val handedOver = Request("r", track("sent"), Requester("Viewer"), RequestSource.CHAT, null, createdAtEpochMs = 1L)
        // 20 entries: the handed-over track, then every other track twice in a row, then more distinct ones.
        val queue = listOf(track("sent")) + (1..10).flatMap { listOf(track("t$it"), track("t$it")) } + (11..20).map { track("t$it") }
        assertEquals(30 + 1, queue.size)

        val shown = book.from(queue, handedOver)

        assertEquals(UPCOMING_LIMIT, shown.size)
        assertEquals((1..15).map { "t$it" }, shown.map { it.id })
        assertTrue(shown.none { it.id == "sent" })
    }

    @Test
    fun fromKeepsEverythingWithoutAHandedOverRequest() {
        assertEquals(listOf("a", "b"), book.from(listOf(track("a"), track("b")), null).map { it.id })
    }

    @Test
    fun dueAfterRefreshIntervalOrTrackChangeOnly() {
        assertTrue(book.due(active = true, trackId = "A", handOverResult = null)) // never read yet
        book.markRead("A")

        now = 29_000
        assertFalse(book.due(active = true, trackId = "A", handOverResult = null))
        now = 30_000
        assertTrue(book.due(active = true, trackId = "A", handOverResult = null))
        assertTrue(book.due(active = true, trackId = "B", handOverResult = null)) // another track: at once
        assertFalse(book.due(active = false, trackId = "B", handOverResult = null)) // nothing active
    }

    @Test
    fun dueAfterStaleOrReset() {
        book.markRead("A")
        assertFalse(book.due(active = true, trackId = "A", handOverResult = null))

        book.markStale()
        assertTrue(book.due(active = true, trackId = "A", handOverResult = null))
        book.markRead("A")
        assertFalse(book.due(active = true, trackId = "A", handOverResult = null))

        book.reset()
        assertTrue(book.due(active = true, trackId = "A", handOverResult = null))
    }
}
