package nl.diments.requestify.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.RemoveReason

class LogRowsTest {
    private val minute = 60_000L
    private val track = Track("t1", "spotify:track:t1", "Mr. Brightside", listOf("The Killers"), 222_000, false, null)
    private val jan = Requester("Jan")

    private fun received(id: String, at: Long, source: RequestSource = RequestSource.DONOLINK) =
        LogRecord.RequestReceived(id, at, source, jan, "https://open.spotify.com/track/t1", Payment.Money(300, "EUR"))

    private fun step(id: String, at: Long, step: LogStep) = LogRecord.RequestStep(id, at, step)

    @Test
    fun aRequestFoldsIntoOneRowWithItsTimeline() {
        val records = listOf(
            received("r1", 1_000),
            step("r1", 1_000, LogStep.Resolved(track)),
            step("r1", 1_000, LogStep.Queued(2)),
            step("r1", 240_000, LogStep.HandedOver),
            step("r1", 300_000, LogStep.Played),
        )

        val row = foldRows(records).single()

        assertEquals("r1", row.id)
        assertEquals(LogKind.Request, row.kind)
        assertEquals(1_000, row.atEpochMs)
        assertEquals(LogStep.Played, row.latestStep)
        assertEquals(track, row.track)
        assertEquals(records, row.records)
        assertFalse(row.isProblem)
    }

    @Test
    fun rowsAreNewestFirstAndRecordsInTimeOrder() {
        val rows = foldRows(
            listOf(
                received("old", 1_000),
                received("new", 5_000),
                step("old", 9_000, LogStep.Played),
                step("old", 2_000, LogStep.Queued(1)),
            ),
        )
        assertEquals(listOf("new", "old"), rows.map { it.id })
        assertEquals(listOf(1_000L, 2_000L, 9_000L), rows[1].records.map { it.atEpochMs })
    }

    @Test
    fun outageIsOneRowUntilRestored() {
        val lost = LogRecord.ConnectionChanged("conn:twitch:1000", 1_000, LogService.Twitch, LogConnectionState.Lost)
        val down = foldRows(listOf(lost)).single()
        assertEquals(LogKind.Connection, down.kind)
        assertTrue(down.isProblem, "still down")

        val restored = LogRecord.ConnectionChanged("conn:twitch:1000", 5_000, LogService.Twitch, LogConnectionState.Restored)
        val up = foldRows(listOf(lost, restored)).single()
        assertEquals(listOf(lost, restored), up.records)
        assertFalse(up.isProblem, "restored")
    }

    @Test
    fun streamEndedCountsClearedAndRefunded() {
        val end = 10 * minute
        val records = listOf(
            LogRecord.StreamChanged("stream:$end", end, LogStreamChange.StreamEnded),
            received("chat1", 1_000, RequestSource.CHAT),
            step("chat1", end + 1_000, LogStep.Removed(RemoveReason.StreamEnded)),
            received("cp1", 2_000, RequestSource.CHANNEL_POINTS),
            step("cp1", end + 1_000, LogStep.Removed(RemoveReason.StreamEnded)),
            step("cp1", end + 5_000, LogStep.Refunded),
            received("late", 3_000, RequestSource.CHAT),
            step("late", end + 3 * minute, LogStep.Removed(RemoveReason.StreamEnded)), // another clear, later
        )

        val stream = foldRows(records).single { it.kind == LogKind.Stream }

        assertEquals(2, stream.clearedCount)
        assertEquals(1, stream.refundedCount)
        assertFalse(stream.isProblem)
    }

    @Test
    fun problemsFollowTheLatestState() {
        fun problem(vararg steps: LogStep) =
            foldRows(listOf(received("r", 0)) + steps.mapIndexed { i, s -> step("r", i + 1L, s) }).single().isProblem

        assertTrue(problem(LogStep.Rejected(RejectReason.TooLong(420_000), listed = true)), "rejected")
        assertTrue(problem(LogStep.Pending), "waiting for Spotify")
        assertTrue(problem(LogStep.Queued(1), LogStep.Removed(RemoveReason.WrongSong), LogStep.RefundPending(1)), "refund pending")
        assertFalse(problem(LogStep.Queued(1), LogStep.Removed(RemoveReason.WrongSong), LogStep.RefundPending(1), LogStep.Refunded))
        assertTrue(problem(LogStep.Queued(1), LogStep.Removed(RemoveReason.WrongSong), LogStep.RefundGaveUp), "refund given up")
        assertFalse(problem(LogStep.Ignored), "a donation without a link is not a problem")
        assertFalse(problem(LogStep.Queued(1), LogStep.Played))
        assertTrue(
            problem(LogStep.Rejected(RejectReason.SpotifyUnavailable, listed = true)),
            "Spotify could not be reached",
        )
    }

    @Test
    fun gapIsAlwaysAProblem() {
        val gap = LogRecord.GapNoticed("gap:bits:5000", 5_000, RequestSource.BITS, fromEpochMs = 1_000, toEpochMs = 5_000)
        val row = foldRows(listOf(gap)).single()
        assertEquals(LogKind.Gap, row.kind)
        assertTrue(row.isProblem)
    }
}
