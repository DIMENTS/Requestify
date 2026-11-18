package nl.diments.requestify.core.twitch

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.stream.RequestsGate
import nl.diments.requestify.core.stream.StreamState
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class StreamWatcherTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-stream-watcher") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /** A real gate on a temp file, a Helix whose `GET /streams` the test controls, all in virtual time. */
    private inner class Harness(scope: TestScope) {
        val events = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 64)
        val freshSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

        /** What `GET /streams` says, when it answers. */
        var live = false

        /** Answers `GET /streams` with this error instead. */
        var failWith: HttpStatusCode? = null

        /** While set, `GET /streams` waits for it. */
        var hold: CompletableDeferred<Unit>? = null

        /** `GET /streams` calls, also those still waiting. */
        var checks = 0

        /** How often the end-of-stream clear ran. */
        var cleared = 0

        val gate = RequestsGate(
            file = JsonFileStore(dir.resolve("stream.json"), StreamState.serializer()) { StreamState() },
            twitchConnected = MutableStateFlow(true),
            scope = scope.backgroundScope,
            clock = { scope.currentTime },
            onStreamEnded = { cleared++ },
        )

        private val api = TwitchApi(
            http = HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                        addHandler { request ->
                            check(request.url.encodedPath == "/helix/streams") { "unexpected ${request.url}" }
                            checks++
                            hold?.await()
                            val json = headersOf(HttpHeaders.ContentType, "application/json")
                            when (val status = failWith) {
                                null -> respond(
                                    if (live) """{"data":[{"id":"s1","type":"live"}]}""" else """{"data":[]}""",
                                    HttpStatusCode.OK,
                                    json,
                                )
                                else -> respond("""{"status":${status.value}}""", status, json)
                            }
                        }
                    },
                ),
            ),
            token = { TokenResult.Ok("tok") },
            broadcasterId = { "42" },
        )

        val watcher = StreamWatcher(events, freshSessions, api, gate, scope.backgroundScope, clock = { scope.currentTime })

        val gateLive: Boolean get() = gate.status.value.live
    }

    @Test
    fun onlineAndOfflineEventsDriveTheGate() = runTest {
        val h = Harness(this)
        h.watcher.start()
        runCurrent()
        assertFalse(h.gateLive)
        assertFalse(h.gate.open.value)

        h.events.emit(EventSubEvent.StreamOnline)
        runCurrent()
        assertTrue(h.gateLive)
        assertTrue(h.gate.open.value, "requests follow the stream")

        h.events.emit(EventSubEvent.StreamOffline)
        runCurrent()
        assertFalse(h.gateLive)
        assertFalse(h.gate.open.value)
        assertEquals(1, h.checks, "events need no read; only the start reads the live status")
    }

    @Test
    fun startupAndFreshSessionReadLiveStatus() = runTest {
        val h = Harness(this)
        h.live = true
        h.watcher.start()
        h.watcher.start() // already running: no second reader
        runCurrent()
        assertEquals(1, h.checks)
        assertTrue(h.gateLive, "live at start")

        h.live = false
        h.freshSessions.emit(Unit)
        runCurrent()
        assertEquals(2, h.checks, "a fresh session lost the events in between: read again")
        assertFalse(h.gateLive)
    }

    @Test
    fun failedLiveCheckChangesNothing() = runTest {
        val h = Harness(this)
        h.gate.onLive(true)
        h.failWith = HttpStatusCode.ServiceUnavailable
        h.watcher.start()
        runCurrent()
        assertEquals(1, h.checks)
        assertTrue(h.gateLive, "Twitch failing is no reason to close requests")

        h.failWith = HttpStatusCode.Unauthorized // a refused login, after its one retry
        h.freshSessions.emit(Unit)
        runCurrent()
        assertTrue(h.checks > 1)
        assertTrue(h.gateLive)

        h.events.emit(EventSubEvent.StreamOffline) // the events still work
        runCurrent()
        assertFalse(h.gateLive)
    }

    @Test
    fun aReadOvertakenByAStreamEventIsDropped() = runTest {
        val h = Harness(this)
        val hold = CompletableDeferred<Unit>()
        h.hold = hold
        h.live = false // Helix still says offline (it lags behind the event)
        h.watcher.start()
        runCurrent()
        assertEquals(1, h.checks)

        h.events.emit(EventSubEvent.StreamOnline)
        runCurrent()
        assertTrue(h.gateLive)

        hold.complete(Unit)
        runCurrent()
        assertTrue(h.gateLive, "the event is newer than the read that was under way")
    }

    @Test
    fun aStoppedWatcherNeverTouchesTheGate() = runTest {
        val h = Harness(this)
        val hold = CompletableDeferred<Unit>()
        h.hold = hold
        h.live = true
        h.watcher.start()
        runCurrent()
        assertEquals(1, h.checks)

        h.watcher.stop()
        hold.complete(Unit) // the read that was under way answers anyway
        h.events.emit(EventSubEvent.StreamOnline)
        h.freshSessions.emit(Unit)
        runCurrent()
        assertFalse(h.gateLive, "without the Twitch session the gate never hears of the stream")
        assertEquals(1, h.checks)

        h.watcher.start() // the session is back
        runCurrent()
        assertEquals(2, h.checks)
        assertTrue(h.gateLive)
    }

    @Test
    fun aFailedReadIsRetriedWithBackoffUntilTwitchAnswers() = runTest {
        val h = Harness(this)
        h.failWith = HttpStatusCode.ServiceUnavailable
        h.watcher.start()
        runCurrent()
        assertEquals(1, h.checks)

        for ((waitMs, checks) in listOf(5_000L to 2, 10_000L to 3, 20_000L to 4, 40_000L to 5, 60_000L to 6, 60_000L to 7)) {
            advanceTimeBy(waitMs - 1)
            runCurrent()
            assertEquals(checks - 1, h.checks, "not before ${waitMs / 1000} s")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(checks, h.checks, "again after ${waitMs / 1000} s")
        }

        h.failWith = null
        h.live = true
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.gateLive, "the read that answers applies")
    }

    @Test
    fun aStaleOfflineAtStartIsCorrectedByTheConfirmationReads() = runTest {
        val h = Harness(this)
        h.watcher.start()
        runCurrent()
        assertEquals(1, h.checks)
        assertFalse(h.gateLive)

        h.live = true // the stream had just started: Helix lagged, and stream.online came before the subscription
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, h.checks, "confirmed about a minute later")
        assertTrue(h.gateLive)
        assertTrue(h.gate.open.value)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, h.checks, "and again at three minutes")
        advanceTimeBy(30 * 60_000)
        runCurrent()
        assertEquals(3, h.checks, "then the events carry it")
    }

    @Test
    fun aStaleOfflineReadMidStreamIsCorrectedBeforeTheClear() = runTest {
        val h = Harness(this)
        h.watcher.start()
        runCurrent()
        h.events.emit(EventSubEvent.StreamOnline)
        runCurrent()
        advanceTimeBy(30 * 60_000) // well into the stream
        runCurrent()
        assertTrue(h.gateLive)

        h.live = false // Helix lags (the stream restarted while EventSub reconnected)
        h.freshSessions.emit(Unit)
        runCurrent()
        assertFalse(h.gateLive, "the read is all Twitch says")
        h.live = true // Helix catches up
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.gateLive, "the next read ends the grace as a drop")
        advanceTimeBy(20 * 60_000)
        runCurrent()
        assertEquals(0, h.cleared, "no end-of-stream clear during a live stream")
        assertTrue(h.gate.open.value)
    }

    @Test
    fun duringTheGraceTheStatusIsReadEveryMinute() = runTest {
        val h = Harness(this)
        h.live = true
        h.watcher.start()
        runCurrent()
        advanceTimeBy(10 * 60_000)
        runCurrent()
        val before = h.checks

        h.live = false
        h.events.emit(EventSubEvent.StreamOffline)
        runCurrent()
        advanceTimeBy(3 * 60_000)
        runCurrent()
        assertEquals(before + 3, h.checks, "a read every minute while in the grace")

        h.live = true // back on air, its stream.online lost
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(h.gateLive, "four minutes after stream.offline a live answer may still be Helix lagging")
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.gateLive, "five minutes after it, a live answer ends the grace as a drop")
        advanceTimeBy(20 * 60_000)
        runCurrent()
        assertEquals(0, h.cleared)
        val afterDrop = h.checks
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(afterDrop, h.checks, "no reads once live again")
    }

    @Test
    fun aReadNeverOverrulesARecentStreamEvent() = runTest {
        val h = Harness(this)
        h.watcher.start()
        runCurrent()
        h.events.emit(EventSubEvent.StreamOnline) // after the start's read: Helix still says offline
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, h.checks)
        assertTrue(h.gateLive, "the confirmation read lags behind stream.online: the event stands")
        assertTrue(h.gate.open.value)
    }
}
