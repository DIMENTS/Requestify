package nl.diments.requestify.core.donations.streamelements

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class StreamElementsSourceTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-se") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val base = Instant.parse("2026-11-18T20:00:00Z").toEpochMilli()
    private val minute = 60_000L
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private class FakeFeed : AstroFeed {
        override val tips = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16)
        override val state = MutableStateFlow<AstroState>(AstroState.Stopped)
        var starts = 0
        var stops = 0
        override fun start() {
            starts++
            state.value = AstroState.Connecting
        }
        override fun stop() {
            stops++
            state.value = AstroState.Stopped
        }
    }

    private class Sink {
        val calls = mutableListOf<String>()
        val kept = mutableListOf<Donation>()
        var failures = 0
        suspend fun handle(d: Donation) {
            calls += d.id
            if (failures > 0) {
                failures--
                throw IllegalStateException("store busy")
            }
            if (kept.none { it.id == d.id }) kept += d
        }
    }

    private fun cursors() = DonationCursorStore(
        JsonFileStore(dir.resolve("donations.json"), DonationCursors.serializer(), retryDelaysMs = emptyList()) { DonationCursors() },
        inCallerSaveContext(),
    )

    private fun tip(id: String) = buildJsonObject {
        put("_id", id)
        put("approved", "allowed")
        put("status", "success")
        put("donation", buildJsonObject {
            put("user", buildJsonObject { put("username", "Jan") })
            put("message", "https://open.spotify.com/track/${"A".repeat(22)}")
            put("amount", 3)
            put("currency", "USD")
        })
    }

    private class Run(val source: StreamElementsSource, val feed: FakeFeed, val sink: Sink, val gaps: List<LogRecord.GapNoticed>)

    private fun TestScope.now() = base + testScheduler.currentTime

    private fun TestScope.start(store: DonationCursorStore = cursors()): Run {
        val feed = FakeFeed()
        val sink = Sink()
        val source = StreamElementsSource(feed, sink::handle, store, backgroundScope, clock = { now() })
        val gaps = mutableListOf<LogRecord.GapNoticed>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.gaps.collect { gaps += it } }
        source.start()
        runCurrent()
        return Run(source, feed, sink, gaps)
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun gap(from: Long?, to: Long) =
        LogRecord.GapNoticed("gap:STREAMELEMENTS:$to", to, RequestSource.STREAMELEMENTS, from, to)

    @Test
    fun aLiveTipIsHandedOver() = runTest {
        val r = start()
        assertEquals(1, r.feed.starts)
        r.feed.state.value = AstroState.Connected
        r.feed.tips.emit(tip("t1"))
        runCurrent()

        assertEquals(listOf("se-t1"), r.sink.calls)
        assertEquals(DonationStatus.Connected, r.source.health.value.status)
        assertFalse(r.source.health.value.canCatchUp, "tips made while Requestify is off are not picked up")
    }

    @Test
    fun aRepeatedTipIsKeptOnce() = runTest {
        val r = start()
        r.feed.state.value = AstroState.Connected
        r.feed.tips.emit(tip("t1"))
        r.feed.tips.emit(tip("t1"))
        runCurrent()
        assertEquals(1, r.sink.kept.size)
    }

    @Test
    fun aLostConnectionLogsAGap() = runTest {
        val r = start()
        r.feed.state.value = AstroState.Connected
        wait(minute)
        val lost = now()
        r.feed.state.value = AstroState.Disconnected(lost)
        runCurrent()
        assertEquals(DonationStatus.Retrying, r.source.health.value.status)
        wait(3 * minute)
        r.feed.state.value = AstroState.Connected
        runCurrent()

        assertEquals(listOf(gap(lost, now())), r.gaps)
    }

    @Test
    fun aShortBlipIsNoGap() = runTest {
        val r = start()
        r.feed.state.value = AstroState.Connected
        r.feed.state.value = AstroState.Disconnected(now())
        wait(5_000)
        r.feed.state.value = AstroState.Connected
        runCurrent()
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun theTimeTheAppWasOffLogsAGap() = runTest {
        val store = cursors()
        store.save(DonationSourceKind.StreamElements, DonationCursor(iso(base - 30 * minute))) // last seen connected
        val r = start(store)
        wait(5_000)
        r.feed.state.value = AstroState.Connected
        runCurrent()

        assertEquals(listOf(gap(base - 30 * minute, now())), r.gaps)
    }

    @Test
    fun theLastConnectedMomentIsRemembered() = runTest {
        val store = cursors()
        val r = start(store)
        r.feed.state.value = AstroState.Connected
        wait(2 * minute + 30_000)
        assertEquals(iso(base + 2 * minute), store.get(DonationSourceKind.StreamElements)?.since, "every minute while connected")

        r.source.stop()
        assertEquals(iso(now()), store.get(DonationSourceKind.StreamElements)?.since, "and when it stops")
        assertEquals(1, r.feed.stops)
    }

    @Test
    fun aRefusedJwtSaysSo() = runTest {
        val r = start()
        r.feed.state.value = AstroState.Refused
        runCurrent()
        assertEquals(DonationStatus.KeyRefused, r.source.health.value.status)
    }

    @Test
    fun aTipTheHandlerFailsOnIsTriedAgain() = runTest {
        val r = start()
        r.sink.failures = 2
        r.feed.state.value = AstroState.Connected
        r.feed.tips.emit(tip("t1"))
        runCurrent()
        assertTrue(r.sink.kept.isEmpty())

        wait(5_000 + 10_000)
        assertEquals(listOf("se-t1"), r.sink.kept.map { it.id }, "a paid tip is never dropped")
        assertEquals(3, r.sink.calls.size)
    }
}
