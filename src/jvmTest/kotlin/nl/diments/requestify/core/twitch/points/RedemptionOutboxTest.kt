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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.SaveContext
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

    private fun TestScope.outbox(helix: FakeRedemptionsApi) = RedemptionOutbox(file(), helix.api, backgroundScope, context = SaveContext(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler)))

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
        assertFalse(outbox.wasSent("red1"))

        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf(listOf(OutboxEntry("rw1", "red1", FULFILLED))), inFileAtCall.toList())
        assertEquals(emptyList(), saved())
        assertTrue(outbox.wasSent("red1"))
    }

    @Test
    fun refundIsFinal() = runTest {
        val helix = helix()
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red1", CANCELED)
        outbox.enqueue("rw1", "red1", FULFILLED) // e.g. added anyway and played: the viewer was told "refunded"
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED)), outbox.entries.value)
        runCurrent()
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED)), saved())

        // Also while the refund is on its way, and when that try fails.
        helix.patchStatuses += 500
        helix.onPatch = { if (it.status == "CANCELED") outbox.enqueue("rw1", "red1", FULFILLED) }
        outbox.start()
        awaitUntil("the failed try") { outbox.entries.value.single().tries == 1 }
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED, tries = 1)), outbox.entries.value)

        advanceTimeBy(5_000)
        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        settle()
        assertEquals(listOf("CANCELED", "CANCELED"), helix.patches.map { it.status })
    }

    @Test
    fun badRequestMovesTheEntryBehindTheOthers() = runTest {
        val helix = helix()
        helix.patchStatuses += 400
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "bad", FULFILLED)
        outbox.enqueue("rw1", "red2", CANCELED)
        outbox.start()

        awaitUntil("the refund behind it is sent") { helix.patches.size == 2 }
        awaitUntil("only the bad one left") { outbox.entries.value.size == 1 }
        assertEquals(listOf("bad@0", "red2@0"), helix.patches.map { "${it.redemptionId}@${it.atMs}" })
        assertEquals(listOf(OutboxEntry("rw1", "bad", FULFILLED, tries = 1)), outbox.entries.value)
        assertEquals(outbox.entries.value, saved())

        outbox.enqueue("rw1", "red3", CANCELED) // a later one does not wait for it either
        awaitUntil("sent at once") { helix.patches.size == 3 }
        assertEquals("red3@0", helix.patches.last().let { "${it.redemptionId}@${it.atMs}" })

        advanceTimeBy(4_999)
        settle()
        assertEquals(3, helix.patches.size, "the bad one waits its own backoff")
        advanceTimeBy(1)
        awaitUntil("retried") { outbox.entries.value.isEmpty() }
        assertEquals("bad@5000", helix.patches.last().let { "${it.redemptionId}@${it.atMs}" })
    }

    @Test
    fun rateLimitWaitsForAllWithoutCountingATry() = runTest {
        val helix = helix()
        helix.patchStatuses += 429 // no Ratelimit-Reset header: Twitch's client waits 5 s
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red1", CANCELED)
        outbox.enqueue("rw1", "red2", CANCELED)
        outbox.start()

        awaitUntil("the limited try") { helix.patches.size == 1 }
        settle()
        assertEquals(listOf(0, 0), outbox.entries.value.map { it.tries }, "not counted as a try")
        advanceTimeBy(4_999)
        settle()
        assertEquals(1, helix.patches.size, "everything waits")
        advanceTimeBy(1)
        awaitUntil("both sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf("red1@0", "red1@5000", "red2@5000"), helix.patches.map { "${it.redemptionId}@${it.atMs}" })
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
    fun aRefusedLoginIsRetriedWithBackoffNotParked() = runTest {
        val helix = helix()
        repeat(4) { helix.patchStatuses += 401 } // two tries, each a 401, a forced refresh and a 401 again
        val outbox = outbox(helix)
        outbox.start()
        outbox.enqueue("rw1", "red1", CANCELED)

        awaitUntil("the first try") { outbox.entries.value.singleOrNull()?.tries == 1 }
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED, tries = 1)), saved(), "kept, and counted as a try")
        advanceTimeBy(5_000)
        awaitUntil("the second try, 5 s later") { outbox.entries.value.singleOrNull()?.tries == 2 }
        advanceTimeBy(15_000)
        awaitUntil("sent on the third try, without a new start") { outbox.entries.value.isEmpty() }
        assertEquals(List(5) { "red1:CANCELED" }, helix.patches.map { "${it.redemptionId}:${it.status}" })
        assertEquals(emptyList(), saved())
    }

    @Test
    fun aSavedRefundWinsOverAFulfilMadeWhileTheFileWasUnreadable() = runTest {
        val helix = helix()
        path.resolve("blocker").createDirectories() // the file cannot be read at start
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red1", FULFILLED) // it started playing; the refund of an earlier run is not known yet
        outbox.enqueue("rw1", "red2", FULFILLED)

        path.toFile().deleteRecursively()
        path.writeText("""[{"rewardId":"rw1","redemptionId":"red1","status":"CANCELED","tries":1}]""")
        advanceTimeBy(5_001)
        runCurrent()

        val merged = listOf(OutboxEntry("rw1", "red1", CANCELED, tries = 1), OutboxEntry("rw1", "red2", FULFILLED))
        assertEquals(merged, outbox.entries.value, "a refund is final")
        assertEquals(merged, saved())
    }

    @Test
    fun loggedOutWaitsWithoutDropping() = runTest {
        val helix = helix()
        helix.token = TokenResult.LoggedOut
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
        runCurrent()
        assertEquals(expected, saved())

        outbox.start()
        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        assertEquals(listOf("red1:CANCELED", "red2:CANCELED"), helix.patches.map { "${it.redemptionId}:${it.status}" })
    }

    @Test
    fun anEntryIsKeptWhileItsFileCannotBeWritten() = runTest {
        val helix = helix()
        breakWrites()
        val outbox = outbox(helix)
        outbox.enqueue("rw1", "red1", CANCELED)
        runCurrent()

        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED)), outbox.entries.value, "kept in memory")
        assertEquals("redemptions.json", outbox.saveProblem.value?.file, "the failing save shows")

        healWrites()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(null, outbox.saveProblem.value)
        assertEquals(listOf(OutboxEntry("rw1", "red1", CANCELED)), saved(), "written once the file works")
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

    @Test
    fun resultsReportSentRetryingAndGaveUp() = runTest {
        val helix = helix()
        helix.patchStatuses += 500 // red1's first try fails
        val outbox = outbox(helix)
        val results = CopyOnWriteArrayList<OutboxResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { outbox.results.collect { results += it } }
        outbox.start()
        outbox.enqueue("rw1", "red1", CANCELED)

        awaitUntil("the failed try") { results.isNotEmpty() }
        advanceTimeBy(5_000)
        awaitUntil("sent") { outbox.entries.value.isEmpty() }
        helix.patchStatuses += 403 // not this app's reward
        outbox.enqueue("rw1", "red2", CANCELED)
        awaitUntil("refused") { results.size == 3 }

        assertEquals(
            listOf<OutboxResult>(
                OutboxResult.Retrying("red1", CANCELED, tries = 1),
                OutboxResult.Sent("red1", CANCELED),
                OutboxResult.GaveUp("red2", CANCELED),
            ),
            results.toList(),
        )
    }
}
