package nl.diments.requestify.core.twitch.points

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.RedemptionStatus.CANCELED
import nl.diments.requestify.core.twitch.RedemptionStatus.FULFILLED
import nl.diments.requestify.core.twitch.chat.awaitUntil
import nl.diments.requestify.core.twitch.chat.settle

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class RedemptionOutboxTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-outbox") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val path get() = dir.resolve("redemptions.json")

    private fun file() = JsonFileStore(path, ListSerializer(OutboxEntry.serializer())) { emptyList() }

    /** What redemptions.json holds now. */
    private fun saved(): List<OutboxEntry> = file().read()

    private fun TestScope.helix() = FakeRedemptionsApi { testScheduler.currentTime }

    private fun TestScope.outbox(helix: FakeRedemptionsApi) = RedemptionOutbox(file(), helix.api, backgroundScope)

    private fun breakWrites() { dir.resolve("redemptions.json.tmp").resolve("blocker").createDirectories() }
    private fun healWrites() { dir.resolve("redemptions.json.tmp").deleteRecursively() }

    @Test
    fun failedRefundIsRetriedAfterRestart() = runTest {
        val helix = helix()
        helix.patchStatuses += 500
        val first = outbox(helix)
        first.start()
        first.enqueue("rw1", "red1", CANCELED)

        awaitUntil("the failed try") { first.entries.value.singleOrNull()?.tries == 1 }
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED, tries = 1)), saved())
        first.stop() // the app is closed before the next try

        val second = outbox(helix)
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED, tries = 1)), second.entries.value)
        second.start()

        awaitUntil("sent after the restart") { second.entries.value.isEmpty() }
        assertEquals(
            listOf("red1:CANCELED", "red1:CANCELED"),
            helix.patches.map { "${it.redemptionId}:${it.status}" },
        )
        assertEquals("rw1", helix.patches.last().rewardId)
        assertEquals(emptyList(), saved())
    }

    @Test
    fun entryIsSavedBeforeTwitchIsCalled() = runTest {
        val helix = helix()
        val inFileAtCall = CopyOnWriteArrayList<List<OutboxEntry>>()
        helix.onPatch = { inFileAtCall += saved() }
        val outbox = outbox(helix)
        outbox.start()
        outbox.enqueue("rw1", "red1", FULFILLED)

        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf(listOf(OutboxEntry("rw1", "red1", FULFILLED))), inFileAtCall.toList())
        assertEquals(emptyList(), saved())
    }

    @Test
    fun notFoundDropsEntry() = runTest {
        val helix = helix()
        helix.patchStatuses += 404 // already settled in the dashboard, or the reward is gone
        val outbox = outbox(helix)
        outbox.start()
        outbox.enqueue("rw1", "red1", CANCELED)

        awaitUntil("dropped") { outbox.entries.value.isEmpty() }
        advanceTimeBy(3_600_000)
        settle()
        assertEquals(1, helix.patches.size, "never tried again")
        assertEquals(emptyList(), saved())
    }

    @Test
    fun forbiddenDropsEntry() = runTest {
        val helix = helix()
        helix.patchStatuses += 403 // not a reward of this app
        val outbox = outbox(helix)
        outbox.start()
        outbox.enqueue("rw1", "red1", FULFILLED)
        outbox.enqueue("rw1", "red2", FULFILLED)

        awaitUntil("both handled") { outbox.entries.value.isEmpty() }
        assertEquals(listOf("red1", "red2"), helix.patches.map { it.redemptionId })
        assertEquals(emptyList(), saved())
    }

    @Test
    fun loginNeededWaitsWithoutDropping() = runTest {
        val helix = helix()
        helix.token = TokenResult.LoginNeeded
        val outbox = outbox(helix)
        outbox.start()
        outbox.enqueue("rw1", "red1", CANCELED)

        awaitUntil("the try") { helix.tokenCalls.get() == 1 }
        advanceTimeBy(3_600_000)
        settle()
        outbox.enqueue("rw1", "red2", FULFILLED)
        settle()
        assertEquals(1, helix.tokenCalls.get(), "nothing is tried until the next start")
        val waiting = listOf(OutboxEntry("rw1", "red1", CANCELED), OutboxEntry("rw1", "red2", FULFILLED))
        assertEquals(waiting, outbox.entries.value)
        assertEquals(waiting, saved())

        helix.token = TokenResult.Ok("new")
        outbox.start() // logged in again
        awaitUntil("both sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf("red1:CANCELED", "red2:FULFILLED"), helix.patches.map { "${it.redemptionId}:${it.status}" })
        assertEquals(emptyList(), saved())
    }

    @Test
    fun backoffSchedule() = runTest {
        val helix = helix()
        repeat(6) { helix.patchStatuses += 500 }
        val outbox = outbox(helix)
        outbox.start()
        outbox.enqueue("rw1", "red1", CANCELED)
        awaitUntil("try 1") { outbox.entries.value.first().tries == 1 }
        outbox.enqueue("rw1", "red2", FULFILLED) // waits behind the first one

        // 5 s, 15 s, 1 min, 5 min, then every 5 min.
        for ((waitMs, tries) in listOf(5_000L to 2, 15_000L to 3, 60_000L to 4, 300_000L to 5, 300_000L to 6)) {
            advanceTimeBy(waitMs - 1)
            settle()
            assertEquals(tries - 1, helix.patches.size, "not before $waitMs ms")
            advanceTimeBy(1)
            awaitUntil("try $tries") { outbox.entries.value.first().tries == tries }
        }
        assertEquals(listOf(0L, 5_000, 20_000, 80_000, 380_000, 680_000), helix.patches.map { it.atMs })
        assertEquals(6, saved().first().tries, "the tries are saved")

        advanceTimeBy(300_000)
        awaitUntil("both sent") { outbox.entries.value.isEmpty() }
        assertEquals(List(7) { "red1" } + "red2", helix.patches.map { it.redemptionId }, "in order")
        assertEquals(980_000L, helix.patches.last().atMs, "the next one at once")
    }

    @Test
    fun secondEntryForTheSameRedemptionReplacesTheUnsentOne() = runTest {
        val helix = helix()
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red1", FULFILLED)
        outbox.enqueue("rw1", "red2", CANCELED)
        outbox.enqueue("rw1", "red1", CANCELED)
        outbox.enqueue("rw1", "red1", CANCELED)

        val expected = listOf(OutboxEntry("rw1", "red1", CANCELED), OutboxEntry("rw1", "red2", CANCELED))
        assertEquals(expected, outbox.entries.value)
        assertEquals(expected, saved())

        outbox.start()
        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf("red1:CANCELED", "red2:CANCELED"), helix.patches.map { "${it.redemptionId}:${it.status}" })
    }

    @Test
    fun failedWriteKeepsTheStatusChangeAndRetriesTheWrite() = runTest {
        val helix = helix()
        breakWrites()
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red1", CANCELED)

        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED)), outbox.entries.value, "kept in memory")
        assertFalse(path.exists())

        healWrites()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED)), saved(), "written on the retry")

        outbox.start()
        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        assertEquals(emptyList(), saved())
    }

    @Test
    fun unreadableFileIsNeverOverwrittenAndIsMergedOnceReadable() = runTest {
        val helix = helix()
        path.resolve("blocker").createDirectories() // a folder where the file should be: it cannot be read
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red2", FULFILLED)
        assertEquals(listOf(OutboxEntry("rw1", "red2", FULFILLED)), outbox.entries.value)
        assertTrue(path.resolve("blocker").exists(), "left as it is")

        // Readable again, with what an earlier run left.
        path.toFile().deleteRecursively()
        path.writeText("""[{"rewardId":"rw1","redemptionId":"red1","status":"CANCELED","tries":2}]""")
        advanceTimeBy(5_001)
        runCurrent()

        val merged = listOf(OutboxEntry("rw1", "red1", CANCELED, tries = 2), OutboxEntry("rw1", "red2", FULFILLED))
        assertEquals(merged, outbox.entries.value)
        assertEquals(merged, saved())

        outbox.start()
        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf("red1", "red2"), helix.patches.map { it.redemptionId })
    }
}
