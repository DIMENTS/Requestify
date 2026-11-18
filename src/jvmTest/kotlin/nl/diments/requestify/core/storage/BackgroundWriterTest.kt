package nl.diments.requestify.core.storage

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundWriterTest {
    private val t0 = 1_000_000L

    private fun TestScope.context() =
        SaveContext(scope = backgroundScope, clock = { t0 + testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler))

    @Test
    fun saveReturnsBeforeTheWrite() = runTest {
        val written = mutableListOf<Int>()
        val writer = BackgroundWriter<Int>("x.json", { written += it }, context())
        writer.save(1)
        assertEquals(emptyList(), written, "save must hand the write to the background, not do it itself")
        runCurrent()
        assertEquals(listOf(1), written)
    }

    @Test
    fun quickChangesAreCoalescedIntoTheNewest() = runTest {
        val written = mutableListOf<Int>()
        lateinit var writer: BackgroundWriter<Int>
        writer = BackgroundWriter("x.json", { v ->
            written += v
            if (v == 1) { // two more changes arrive while 1 is being written
                writer.save(2)
                writer.save(3)
            }
        }, context())
        writer.save(1)
        runCurrent()
        assertEquals(listOf(1, 3), written)
    }

    @Test
    fun anOlderValueIsNeverWrittenAfterANewer() = runTest {
        val written = mutableListOf<Int>()
        var failFirst = true
        val writer = BackgroundWriter<Int>("x.json", { v ->
            if (failFirst) {
                failFirst = false
                throw IOException("locked")
            }
            written += v
        }, context())
        writer.save(1)
        runCurrent() // 1 fails; the retry waits
        writer.save(2)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(2), written, "after a newer value, the older one is never written")
    }

    @Test
    fun aFailedWriteIsRetriedWithBackoffAndReported() = runTest {
        val attemptsAt = mutableListOf<Long>()
        var failures = 2
        val writer = BackgroundWriter<Int>("x.json", {
            attemptsAt += testScheduler.currentTime
            if (failures-- > 0) throw IOException("disk full")
        }, context())
        writer.save(1)
        runCurrent()
        assertEquals(SaveProblem("x.json", "disk full", t0), writer.problem.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SaveProblem("x.json", "disk full", t0), writer.problem.value, "kept while retrying, with its start")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L), attemptsAt)
        assertNull(writer.problem.value, "cleared after the write that works")
    }

    @Test
    fun awaitSavedWaitsForTheWriteBeforeIt() = runTest {
        val written = mutableListOf<Int>()
        var failures = 1
        val writer = BackgroundWriter<Int>("x.json", { v ->
            if (failures-- > 0) throw IOException("locked")
            written += v
        }, context())
        writer.save(1)
        var done = false
        launch {
            writer.awaitSaved()
            done = true
        }
        runCurrent()
        assertFalse(done, "the first write failed: nothing is saved yet")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(done)
        assertEquals(listOf(1), written)
    }

    @Test
    fun flushGivesUpAfterTheTimeout() = runTest {
        val writer = BackgroundWriter<Int>("x.json", { throw IOException("disk full") }, context())
        writer.save(1)
        val start = testScheduler.currentTime
        val saved = writer.flush(3_000)
        assertFalse(saved)
        assertEquals(3_000L, testScheduler.currentTime - start)
    }

    @Test
    fun flushIsTrueWhenEverythingIsWritten() = runTest {
        val writer = BackgroundWriter<Int>("x.json", {}, context())
        writer.save(1)
        assertTrue(writer.flush(3_000))
    }
}
