package nl.diments.requestify.core.donations.donolink

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.donations.DonationCursor
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationCursors
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class DonoLinkSourceTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-donolink") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /** Wall-clock time at virtual time 0. */
    private val base = Instant.parse("2026-11-18T20:00:00Z").toEpochMilli()
    private val expiry = Instant.parse("2026-12-06T12:00:00Z").toEpochMilli()

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** DonoLink as a list of recorded donations, paged like the real feed (inclusive since, offset cursors). */
    private inner class FakeFeed : DonoLinkFeed {
        val items = mutableListOf<JsonObject>()
        var pageSize = 100
        val scripted = ArrayDeque<DonoLinkResult<DonoLinkPage>>()
        var throwNext: Exception? = null
        val calls = mutableListOf<Triple<Long, String, String?>>()
        var me: DonoLinkResult<DonoLinkMe> = DonoLinkResult.Ok(DonoLinkMe("DIMENTS", expiry))
        var meCalls = 0
        lateinit var now: () -> Long

        override suspend fun me(): DonoLinkResult<DonoLinkMe> = me.also { meCalls++ }

        override suspend fun activity(since: String, cursor: String?): DonoLinkResult<DonoLinkPage> {
            calls += Triple(now(), since, cursor)
            scripted.removeFirstOrNull()?.let { return it }
            throwNext?.let { throwNext = null; throw it }
            val from = parseInstant(since)!!
            val matching = items.filter { parseInstant(recordedAtOf(it)!!)!! >= from }
                .sortedBy { parseInstant(recordedAtOf(it)!!)!! }
            val offset = cursor?.toInt() ?: 0
            val page = matching.drop(offset).take(pageSize)
            val more = offset + page.size < matching.size
            return DonoLinkResult.Ok(DonoLinkPage(page, more, if (more) (offset + page.size).toString() else null))
        }
    }

    private fun item(id: String, recordedAtMs: Long, message: String = "https://open.spotify.com/track/${"A".repeat(22)}", spaced: Boolean = false) =
        buildJsonObject {
            put("id", id)
            put("type", "donation")
            put("platform", "donolink")
            put("occurred_at", iso(recordedAtMs))
            put("recorded_at", if (spaced) iso(recordedAtMs).replace('T', ' ') else iso(recordedAtMs))
            put("actor", buildJsonObject { put("name", "Jan") })
            put("message", message)
            put("value", 300)
            put("unit", "cents")
            put("currency", "EUR")
        }

    private fun cursors() = DonationCursorStore(
        JsonFileStore(dir.resolve("donations.json"), DonationCursors.serializer(), retryDelaysMs = emptyList()) { DonationCursors() },
        inCallerSaveContext(),
    )

    /** Every hand-over (calls) and what a handler that drops known ids would keep (kept). */
    private class Sink {
        val calls = mutableListOf<String>()
        val kept = mutableListOf<Donation>()
        suspend fun handle(d: Donation) {
            calls += d.id
            if (kept.none { it.id == d.id }) kept += d
        }
    }

    private fun TestScope.now() = base + testScheduler.currentTime

    private fun TestScope.source(feed: FakeFeed, sink: Sink, store: DonationCursorStore = cursors()) =
        DonoLinkSource(feed, sink::handle, store, backgroundScope, clock = { now() }).also {
            feed.now = { now() }
            it.start()
            runCurrent()
        }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun firstStartBeginsAtNow() = runTest {
        val feed = FakeFeed()
        feed.items += item("don_old", base - 3_600_000)
        val sink = Sink()
        source(feed, sink)

        assertEquals(iso(base), feed.calls.first().second)
        assertTrue(sink.calls.isEmpty(), "nothing from before the first link")
        assertEquals(DonationCursor(iso(base)), cursors().get(DonationSourceKind.DonoLink))
    }

    @Test
    fun theCursorIsSavedOnlyAfterTheRequest() = runTest {
        val feed = FakeFeed()
        val onDisk = CompletableDeferred<Unit>() // the handler returns once the request is on disk
        val handled = mutableListOf<String>()
        DonoLinkSource(feed, { d -> onDisk.await(); handled += d.id }, cursors(), backgroundScope, clock = { now() }).also {
            feed.now = { now() }
            it.start()
            runCurrent()
        }
        feed.items += item("don_a", base + 1_000)
        wait(5_000)

        assertEquals(DonationCursor(iso(base)), cursors().get(DonationSourceKind.DonoLink), "the cursor waits for the request")
        onDisk.complete(Unit)
        runCurrent()
        assertEquals(listOf("dl-don_a"), handled)
        assertEquals(DonationCursor(iso(base + 1_000), setOf("don_a")), cursors().get(DonationSourceKind.DonoLink))
    }

    @Test
    fun aDonationIsHandedOverOnce() = runTest {
        val feed = FakeFeed()
        val sink = Sink()
        val src = source(feed, sink)
        feed.items += item("don_a", base + 1_000)
        wait(5_000)
        wait(5_000) // since is inclusive: DonoLink returns it again
        wait(5_000)

        assertEquals(listOf("dl-don_a"), sink.calls, "handed over once, not once per poll")
        assertEquals(DonationStatus.Connected, src.health.value.status)
        assertEquals(DonationCursor(iso(base + 1_000), setOf("don_a")), cursors().get(DonationSourceKind.DonoLink))
    }

    @Test
    fun manyDonationsWithOneTimestampAreAllHandledOnce() = runTest {
        val feed = FakeFeed()
        val sink = Sink()
        source(feed, sink)
        val at = base + 2_000
        repeat(250) { feed.items += item("don_$it", at) }
        wait(5_000)
        wait(5_000)

        assertEquals(250, sink.calls.size)
        assertEquals(250, sink.calls.toSet().size, "none twice")
    }

    @Test
    fun catchUpFollowsEveryPageInOrder() = runTest {
        val store = cursors()
        store.save(DonationSourceKind.DonoLink, DonationCursor(iso(base - 3_600_000)))
        val feed = FakeFeed()
        repeat(150) { feed.items += item("don_${it.toString().padStart(3, '0')}", base - 3_000_000 + it * 1_000L) }
        val sink = Sink()
        source(feed, sink, store)

        assertEquals((0 until 150).map { "dl-don_${it.toString().padStart(3, '0')}" }, sink.calls, "every page, oldest first")
        assertEquals(listOf(null, "100"), feed.calls.map { it.third })
        assertEquals(iso(base - 3_000_000 + 149_000), cursors().get(DonationSourceKind.DonoLink)?.since)
    }

    @Test
    fun restartBeforeTheCursorSaveQueuesItOnce() = runTest {
        val feed = FakeFeed()
        val sink = Sink()
        val first = source(feed, sink)
        dir.resolve("donations.json.tmp").resolve("blocker").createDirectories() // the cursor cannot be saved
        feed.items += item("don_a", base + 1_000)
        wait(5_000)
        assertEquals(listOf("dl-don_a"), sink.calls)
        first.stop() // the app closes before the cursor was saved

        dir.resolve("donations.json.tmp").deleteRecursively()
        source(feed, sink)

        assertEquals(listOf("dl-don_a", "dl-don_a"), sink.calls, "handed over again after the restart")
        assertEquals(1, sink.kept.size, "the handler's known-id check keeps it once")
    }

    @Test
    fun aRefusedKeyStopsAndSaysSo() = runTest {
        val feed = FakeFeed()
        feed.scripted += DonoLinkResult.KeyRefused(401)
        val src = source(feed, Sink())
        wait(60_000)

        assertEquals(DonationStatus.KeyRefused, src.health.value.status)
        assertEquals(1, feed.calls.size, "no more polling with a refused key")
    }

    @Test
    fun rateLimitWaitsRetryAfter() = runTest {
        val feed = FakeFeed()
        feed.scripted += DonoLinkResult.RateLimited(7_000)
        source(feed, Sink())
        wait(6_999)
        assertEquals(1, feed.calls.size)
        wait(1)
        assertEquals(listOf(base, base + 7_000), feed.calls.map { it.first })
    }

    @Test
    fun failuresBackOffAndRecover() = runTest {
        val feed = FakeFeed()
        repeat(3) { feed.scripted += DonoLinkResult.Failed("down") }
        val src = source(feed, Sink())
        assertEquals(DonationStatus.Retrying, src.health.value.status)
        assertEquals(base, src.health.value.retryingSinceMs)

        wait(5_000 + 10_000 + 20_000)
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L), feed.calls.map { it.first - base })
        assertEquals(DonationStatus.Connected, src.health.value.status)
    }

    @Test
    fun expiryIsReadAtStartAndDaily() = runTest {
        val feed = FakeFeed()
        val src = source(feed, Sink())
        assertEquals(1, feed.meCalls)
        assertEquals(expiry, src.health.value.keyExpiresAtEpochMs)

        wait(24 * 3_600_000L)
        assertEquals(2, feed.meCalls)
    }

    @Test
    fun resetToNowSkipsTheOffPeriod() = runTest {
        val store = cursors()
        store.save(DonationSourceKind.DonoLink, DonationCursor(iso(base - 3_600_000)))
        val feed = FakeFeed()
        feed.items += item("don_while_off", base - 600_000)
        store.resetToNow(DonationSourceKind.DonoLink, iso(base)) // the streamer switched DonoLink on again
        val sink = Sink()
        source(feed, sink, store)
        wait(5_000)

        assertTrue(sink.calls.isEmpty())
        assertNotNull(feed.calls.firstOrNull())
        assertEquals(iso(base), feed.calls.first().second)
    }

    @Test
    fun aRecordedTimeWithASpaceKeepsPolling() = runTest {
        val feed = FakeFeed()
        val sink = Sink()
        val src = source(feed, sink)
        feed.items += item("don_a", base + 1_000, spaced = true) // DonoLink's own example writes times like this
        wait(5_000)
        feed.items += item("don_b", base + 6_000, spaced = true)
        wait(5_000)
        wait(5_000)

        assertEquals(listOf("dl-don_a", "dl-don_b"), sink.calls)
        assertEquals(DonationStatus.Connected, src.health.value.status)
    }

    @Test
    fun anUnexpectedErrorIsRetriedNotFatal() = runTest {
        val feed = FakeFeed()
        val sink = Sink()
        feed.throwNext = IllegalStateException("odd answer")
        val src = source(feed, sink)
        assertEquals(DonationStatus.Retrying, src.health.value.status)

        feed.items += item("don_a", base + 1_000)
        wait(5_000)
        assertEquals(listOf("dl-don_a"), sink.calls)
        assertEquals(DonationStatus.Connected, src.health.value.status)
    }

    @Test
    fun withoutAKeyItSaysSoAndWaits() = runTest {
        val feed = FakeFeed()
        feed.me = DonoLinkResult.KeyRefused(0) // no key saved yet
        val src = source(feed, Sink())
        assertEquals(DonationStatus.NoKey, src.health.value.status, "no key yet is not a refused key")
    }
}
