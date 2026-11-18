package nl.diments.requestify.core.storage

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class PersistedStateTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-persisted")
        file = dir.resolve("list.json")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun jsonStore(path: Path = file) =
        JsonFileStore(path, ListSerializer(String.serializer()), retryDelaysMs = emptyList()) { emptyList() }

    private fun TestScope.context() = SaveContext(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))

    private fun TestScope.state(store: JsonFileStore<List<String>> = jsonStore()) = PersistedState(store, context()) { emptyList() }

    /** Another program holds the file and denies reading it (a Windows sharing violation). */
    private fun lockedForReading(): SeekableByteChannel =
        Files.newByteChannel(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)

    @Test
    fun aChangeIsVisibleBeforeItIsWritten() = runTest {
        val state = state()
        assertTrue(state.change { it + "a" })
        assertEquals(listOf("a"), state.value.value)
        assertFalse(file.exists(), "the write happens in the background")
        runCurrent()
        assertEquals(listOf("a"), jsonStore().read())
    }

    @Test
    fun aFailingWriteKeepsTheChangeAndReportsIt() = runTest {
        // A folder where the file's temp file must go makes every write fail.
        val blocked = dir.resolve("blocked.json")
        dir.resolve("blocked.json.tmp").createDirectories()
        val state = state(jsonStore(blocked))
        assertTrue(state.change { it + "a" })
        runCurrent()
        assertEquals(listOf("a"), state.value.value, "the change stays")
        val problem = assertNotNull(state.saveProblem.value)
        assertEquals("blocked.json", problem.file)

        dir.resolve("blocked.json.tmp").deleteRecursively()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(state.saveProblem.value)
        assertEquals(listOf("a"), jsonStore(blocked).read())
    }

    @Test
    fun anUnreadableFileRefusesChangesAndIsNeverOverwritten() = runTest {
        jsonStore().write(listOf("saved"))
        val text = file.readText()
        lockedForReading().use {
            val state = state()
            assertTrue(state.loadFailed.value)
            assertEquals(emptyList(), state.value.value, "the app still starts, on the default")
            assertFalse(state.change { it + "a" }, "refused while the saved file cannot be read")
            assertEquals(emptyList(), state.value.value)
            advanceTimeBy(20_000)
            runCurrent()
        }
        assertEquals(text, file.readText(), "the saved file is untouched")
    }

    @Test
    fun anUnreadableFileIsReadAgainInTheBackground() = runTest {
        jsonStore().write(listOf("saved"))
        val text = file.readText()
        val lock = lockedForReading()
        val state = state()
        assertTrue(state.loadFailed.value)
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(state.loadFailed.value, "still held: still the default")

        lock.close()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(state.loadFailed.value)
        assertEquals(listOf("saved"), state.value.value)
        assertEquals(text, file.readText(), "the default was never written")
        assertTrue(state.change { it + "a" })
        runCurrent()
        assertEquals(listOf("saved", "a"), jsonStore().read())
    }

    @Test
    fun awaitSavedReturnsOnceTheChangeIsOnDisk() = runTest {
        val state = state()
        state.change { it + "a" }
        state.awaitSaved()
        assertEquals(listOf("a"), jsonStore().read())
    }
}
