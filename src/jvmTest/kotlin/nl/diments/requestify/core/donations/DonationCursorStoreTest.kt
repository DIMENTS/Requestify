package nl.diments.requestify.core.donations

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalPathApi::class)
class DonationCursorStoreTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-cursors") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() = DonationCursorStore(
        JsonFileStore(dir.resolve("donations.json"), DonationCursors.serializer(), retryDelaysMs = emptyList()) { DonationCursors() },
        inCallerSaveContext(),
    )

    @Test
    fun eachSourceKeepsItsOwnCursorAcrossRestarts() {
        val first = store()
        assertNull(first.get(DonationSourceKind.DonoLink))
        assertTrue(first.save(DonationSourceKind.DonoLink, DonationCursor("2026-11-18T20:00:00Z", setOf("a"))))
        assertTrue(first.save(DonationSourceKind.StreamElements, DonationCursor("2026-11-18T21:00:00Z")))

        val second = store()
        assertEquals(DonationCursor("2026-11-18T20:00:00Z", setOf("a")), second.get(DonationSourceKind.DonoLink))
        assertEquals(DonationCursor("2026-11-18T21:00:00Z"), second.get(DonationSourceKind.StreamElements))
    }

    @Test
    fun resetToNowForgetsWhatWasSeen() {
        val store = store()
        store.save(DonationSourceKind.DonoLink, DonationCursor("2026-11-01T00:00:00Z", setOf("a", "b")))
        store.resetToNow(DonationSourceKind.DonoLink, "2026-11-18T20:00:00Z")
        assertEquals(DonationCursor("2026-11-18T20:00:00Z"), store().get(DonationSourceKind.DonoLink))
    }

    @Test
    fun aFailedSaveIsReported() {
        dir.resolve("donations.json.tmp").resolve("blocker").createDirectories()
        val store = store()
        assertTrue(store.save(DonationSourceKind.DonoLink, DonationCursor("x")), "applied at once, saving retried")
        assertEquals(DonationCursor("x"), store.get(DonationSourceKind.DonoLink))
        assertEquals("donations.json", store.saveProblem.value?.file)
    }
}
