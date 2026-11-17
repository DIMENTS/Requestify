package nl.diments.requestify.core.intake

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalPathApi::class)
class RejectedStoreTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-rejected") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() = RejectedStore(
        JsonFileStore(dir.resolve("rejected.json"), ListSerializer(RejectedEntry.serializer()), retryDelaysMs = emptyList()) { emptyList() },
    )

    private fun entry(id: String, paid: Boolean = false, at: Long = 0) = RejectedEntry(
        id, null, "input $id", Requester("V", "v", "u1"), RequestSource.CHAT,
        if (paid) Payment.Bits(100) else null, RejectReason.Duplicate, at, refunded = false,
    )

    @Test
    fun capKeepsPaidAndDropsOldestFreeFirst() {
        val store = store()
        // added oldest first, so free0 is the oldest free entry; the two paid ones are among the oldest
        (0 until 50).forEach {
            val paid = it == 1 || it == 2
            store.add(entry(if (paid) "paid$it" else "free$it", paid = paid, at = it.toLong()))
        }
        assertEquals(50, store.entries.value.size)
        assertTrue(store.add(entry("new")))
        val ids = store.entries.value.map { it.id }
        assertEquals(50, ids.size)
        assertEquals("new", ids.first(), "newest first")
        assertFalse("free0" in ids, "the oldest free one is gone")
        assertTrue("paid1" in ids && "paid2" in ids)
        assertTrue("free3" in ids)
    }

    @Test
    fun paidNeverDroppedEvenAboveCap() {
        val store = store()
        (0 until 51).forEach { store.add(entry("p$it", paid = true)) }
        assertEquals(51, store.entries.value.size)
        assertEquals(51, store.paidCount.value)
    }

    @Test
    fun paidCountIsTheBadge() {
        val store = store()
        assertEquals(0, store.paidCount.value)
        store.add(entry("a", paid = true))
        store.add(entry("b"))
        assertEquals(1, store.paidCount.value)
        store.add(entry("a", paid = true)) // same id: not added twice
        assertEquals(2, store.entries.value.size)
        store.remove("a")
        assertEquals(0, store.paidCount.value)
        assertEquals(listOf("b"), store.entries.value.map { it.id })
    }

    @Test
    fun roundTripAcrossRestart() {
        val store = store()
        store.add(entry("a", paid = true, at = 1))
        store.add(entry("b", at = 2))
        val again = store()
        assertEquals(listOf("b", "a"), again.entries.value.map { it.id })
        assertEquals(1, again.paidCount.value)
        assertFalse(again.loadFailed.value)
    }
}
