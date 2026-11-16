package nl.diments.requestify.core.queue

import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestQueueTest {
    private fun req(id: String, paid: Boolean = false) = Request(
        id = id,
        track = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null),
        requester = Requester("Viewer"),
        source = RequestSource.MANUAL,
        payment = if (paid) Payment.Bits(100) else null,
        createdAtEpochMs = 0,
    )

    private fun RequestQueue.ids() = items.map { it.id }

    @Test fun freeRequestsAppendInOrder() =
        assertEquals(listOf("f1", "f2"), RequestQueue().add(req("f1"), true).add(req("f2"), true).ids())

    @Test fun paidGoesAfterLastPaidBeforeFree() = assertEquals(
        listOf("p1", "p2", "f1"),
        RequestQueue().add(req("f1"), true).add(req("p1", paid = true), true).add(req("p2", paid = true), true).ids(),
    )

    @Test fun paidPriorityOffAppends() = assertEquals(
        listOf("f1", "p1"),
        RequestQueue().add(req("f1"), false).add(req("p1", paid = true), false).ids(),
    )

    @Test fun manualMoveIsRespected() {
        val q = RequestQueue(listOf(req("p1", paid = true), req("f1"))).move("f1", 0)
        assertEquals(listOf("f1", "p1", "p2"), q.add(req("p2", paid = true), true).ids())
    }

    @Test fun moveClampsIndex() = assertEquals(
        listOf("b", "a"),
        RequestQueue(listOf(req("a"), req("b"))).move("a", 99).ids(),
    )

    @Test fun removeUnknownIdIsNoOp() {
        val q = RequestQueue(listOf(req("a")))
        assertEquals(q, q.remove("x"))
    }

    @Test fun addFirstPutsAtHead() =
        assertEquals("x", RequestQueue(listOf(req("a"))).addFirst(req("x")).head?.id)

    @Test fun totalDuration() =
        assertEquals(400_000, RequestQueue(listOf(req("a"), req("b"))).totalDurationMs)
}
