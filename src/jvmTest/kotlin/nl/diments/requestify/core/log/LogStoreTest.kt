package nl.diments.requestify.core.log

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
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
import nl.diments.requestify.core.storage.AppJson

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class LogStoreTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-log").resolve("log") }
    @AfterTest fun tearDown() { dir.parent.deleteRecursively() }

    /** 2026-11-18 12:00 UTC: the tests' "now" at virtual time 0. */
    private val noon = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 11, 18)

    private fun TestScope.store(bufferMax: Int = 10_000) = LogStore(
        dir = dir,
        scope = backgroundScope,
        clock = { noon + testScheduler.currentTime },
        zone = ZoneOffset.UTC,
        io = StandardTestDispatcher(testScheduler),
        bufferMax = bufferMax,
    )

    private fun record(i: Int, at: Long = noon) = LogRecord.StreamChanged("s$i", at, LogStreamChange.Live)

    private fun dayFile(date: LocalDate) = dir.resolve("$date.jsonl")

    /** Appending to the day file fails while a folder stands in its place. */
    private fun breakWrites() { dayFile(today).createDirectories() }
    private fun healWrites() { dayFile(today).deleteRecursively() }

    @Test
    fun stopWhileWritingWritesEachRecordOnce() = kotlinx.coroutines.runBlocking {
        repeat(20) { round ->
            val roundDir = dir.resolve("round-$round")
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            val store = LogStore(roundDir, scope, clock = { noon }, zone = ZoneOffset.UTC, io = kotlinx.coroutines.Dispatchers.IO)
            store.start()
            repeat(500) { store.append(record(it)) }
            store.stop() // races the writer that is still busy with the same records
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            val ids = roundDir.resolve("$today.jsonl").readLines().map { AppJson.instance.decodeFromString(LogRecord.serializer(), it).rowId }
            assertEquals(500, ids.size, "round $round: every record once, none twice")
            assertEquals(500, ids.toSet().size)
        }
    }

    @Test
    fun appendedRecordsReadBackInOrder() = runTest {
        val store = store()
        store.start()
        (1..3).forEach { store.append(record(it)) }
        runCurrent()

        assertEquals((1..3).map { record(it) }, store.readAll())
        assertEquals(3, dayFile(today).readLines().size)
    }

    @Test
    fun recordsLandInTheirLocalDayFile() = runTest {
        val store = store()
        store.start()
        val late = Instant.parse("2026-11-17T23:59:59Z").toEpochMilli()
        val early = Instant.parse("2026-11-18T00:00:01Z").toEpochMilli()
        store.append(record(1, late))
        store.append(record(2, early))
        runCurrent()

        assertEquals(1, dayFile(today.minusDays(1)).readLines().size)
        assertEquals(1, dayFile(today).readLines().size)
        assertEquals(listOf(record(1, late), record(2, early)), store.readAll())
    }

    @Test
    fun filesOlderThanThirtyDaysAreDeleted() = runTest {
        dir.createDirectories()
        val line = AppJson.instance.encodeToString(LogRecord.serializer(), record(1))
        dayFile(today.minusDays(31)).writeText(line + "\n")
        dayFile(today.minusDays(29)).writeText(line + "\n")
        val store = store()
        store.start()
        runCurrent()

        assertFalse(dayFile(today.minusDays(31)).exists(), "older than 30 days")
        assertTrue(dayFile(today.minusDays(29)).exists())

        advanceTimeBy(2 * 24 * 3_600_000L + 1) // two days later, the daily clean-up runs again
        runCurrent()
        assertFalse(dayFile(today.minusDays(29)).exists(), "now 31 days old")
    }

    @Test
    fun aCorruptLineIsSkipped() = runTest {
        dir.createDirectories()
        val good = AppJson.instance.encodeToString(LogRecord.serializer(), record(1))
        val alsoGood = AppJson.instance.encodeToString(LogRecord.serializer(), record(2))
        dayFile(today).writeText("$good\n{not json\n$alsoGood\n")

        assertEquals(listOf(record(1), record(2)), store().readAll())
    }

    @Test
    fun aFailedWriteIsRetriedAndReported() = runTest {
        breakWrites()
        val store = store()
        store.start()
        store.append(record(1))
        store.append(record(2))
        runCurrent()
        assertNotNull(store.writeError.value, "the failure is reported")

        healWrites()
        advanceTimeBy(1_001)
        runCurrent()

        assertNull(store.writeError.value)
        assertEquals(listOf(record(1), record(2)), store.readAll(), "kept in memory and written in order")
    }

    @Test
    fun theBufferNeverGrowsPastItsMax() = runTest {
        breakWrites()
        val store = store(bufferMax = 3)
        store.start()
        (1..5).forEach { store.append(record(it)) }
        runCurrent()
        assertTrue(store.writeError.value.orEmpty().contains("2"), "says how many were lost: ${store.writeError.value}")

        healWrites()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals((3..5).map { record(it) }, store.readAll(), "the oldest went first")
    }
}
