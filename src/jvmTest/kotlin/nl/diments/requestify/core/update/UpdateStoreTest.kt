package nl.diments.requestify.core.update

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.deleteTestDir

class UpdateStoreTest {
    private lateinit var dir: Path
    private val pending = PendingUpdate("0.2.0", "Requestify-0.2.0.msi", "ab".repeat(32), "notes", "https://github.com/x")

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-updates") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    @Test
    fun roundTripsAndToleratesGarbage() {
        val store = UpdateStore(dir.resolve("not-yet-there"))
        assertNull(store.pending(), "nothing pending before anything was saved")
        store.savePending(pending)
        assertEquals(pending, store.pending())
        store.saveJustUpdated(JustUpdated("0.1.0", "0.2.0", "notes"))
        assertEquals(JustUpdated("0.1.0", "0.2.0", "notes"), store.justUpdated())

        dir.resolve("not-yet-there").resolve("pending.json").writeText("{not json")
        assertNull(store.pending(), "a damaged file reads as nothing, never a crash")
    }

    @Test
    fun markAttemptCounts() {
        val store = UpdateStore(dir)
        assertNull(store.markAttempt(), "no pending update, no attempt")
        store.savePending(pending)
        assertEquals(1, store.markAttempt()?.attempts)
        assertEquals(2, store.markAttempt()?.attempts)
        assertEquals(2, UpdateStore(dir).pending()?.attempts, "kept on disk for the next start")
    }

    @Test
    fun clearPendingDeletesTheMsi() {
        val store = UpdateStore(dir)
        store.savePending(pending)
        dir.resolve("Requestify-0.2.0.msi").writeText("msi")
        dir.resolve("Requestify-0.1.9.msi.part").writeText("half")
        store.saveJustUpdated(JustUpdated("0.1.0", "0.2.0", ""))
        store.clearPending()
        assertNull(store.pending())
        assertFalse(dir.resolve("Requestify-0.2.0.msi").exists())
        assertFalse(dir.resolve("Requestify-0.1.9.msi.part").exists())
        assertTrue(store.justUpdated() != null, "the card's note is separate")
    }

    @Test
    fun theExitCodeIsReadFromTheHelpersFile() {
        val store = UpdateStore(dir)
        assertNull(store.lastExitCode())
        dir.resolve("install-exit-code.txt").writeText("1603\r\n")
        assertEquals(1603, store.lastExitCode())
        store.clearExitCode()
        assertNull(store.lastExitCode())
        dir.resolve("install-exit-code.txt").writeText("odd")
        assertNull(store.lastExitCode())
    }
}
