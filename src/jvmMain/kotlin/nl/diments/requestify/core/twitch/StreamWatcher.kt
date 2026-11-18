package nl.diments.requestify.core.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.diments.requestify.core.stream.RequestsGate
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

/**
 * Tells the [gate] whether the stream is live, while the Twitch session is connected:
 *
 * - `stream.online` / `stream.offline` apply at once.
 * - The live status is read over Helix ([TwitchApi.isLive]) at [start] and after every fresh EventSub session, whose
 *   events in between are lost. A read that fails is tried again ([retryDelaysMs], then every 60 s) until Twitch
 *   answers; then it is read again [confirmAfterMs] later, so a stale answer (Helix lags behind a stream that just
 *   started or ended) does not stay in force.
 * - While the gate is in its offline grace, it is read every [graceReadMs]: a stream that is back (its
 *   `stream.online` lost) ends the grace as a drop, before the end-of-stream clear.
 * - Reads run one at a time. A read never overrules a stream event that came in while it was under way, nor one of
 *   the last [eventTrustMs] that says otherwise: the event is newer, and Helix lags behind the events.
 *
 * The wiring runs it only while the Twitch session is connected, so the gate never hears of the stream without Twitch.
 * After [stop] nothing of it reaches the gate, a read still under way included.
 */
class StreamWatcher(
    private val events: Flow<EventSubEvent>,
    private val freshSessions: Flow<Unit>,
    private val api: TwitchApi,
    private val gate: RequestsGate,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val retryDelaysMs: List<Long> = listOf(5_000, 10_000, 20_000, 40_000, 60_000), // then every 60 s
    private val confirmAfterMs: List<Long> = listOf(60_000, 180_000),
    private val graceReadMs: Long = 60_000,
    private val eventTrustMs: Long = 300_000,
) {
    constructor(eventSub: EventSubClient, api: TwitchApi, gate: RequestsGate, scope: CoroutineScope, clock: () -> Long) :
        this(eventSub.events, eventSub.freshSessions, api, gate, scope, clock)

    private val lock = Any()

    /** Guarded by [lock]. */
    private var job: Job? = null

    /** Bumped by [start] and [stop]: only the current run reaches the gate. Guarded by [lock]. */
    private var run = 0L

    /** Stream events applied so far: a read applies only when none came in while it was under way. Guarded by [lock]. */
    private var streamEvents = 0L

    /** The last stream event (live or not) and when it came. Guarded by [lock]. */
    private var lastEvent: Pair<Boolean, Long>? = null

    /** One read at a time. */
    private val reading = Mutex()

    /** Starts watching and reads the live status; does nothing while already running. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val current = ++run
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Both are subscribed before start() returns: neither flow replays.
                val reads = Channel<Unit>(Channel.CONFLATED)
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) { freshSessions.collect { reads.trySend(Unit) } }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        events.collect { event ->
                            when (event) {
                                EventSubEvent.StreamOnline -> streamEvent(current, live = true)
                                EventSubEvent.StreamOffline -> streamEvent(current, live = false)
                                else -> Unit
                            }
                        }
                    }
                    reads.trySend(Unit)
                    // Each start or fresh session: a read now and its confirmations; a newer one starts them over.
                    launch {
                        var checks: Job? = null
                        for (read in reads) {
                            checks?.cancelAndJoin()
                            checks = launch { readAndConfirm(current) }
                        }
                    }
                    launch {
                        gate.inGrace.collectLatest { inGrace ->
                            if (inGrace) {
                                while (true) {
                                    delay(graceReadMs)
                                    read(current)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Stops watching; a read still under way is dropped. */
    fun stop() {
        synchronized(lock) {
            run++
            job?.cancel()
            job = null
        }
    }

    private fun streamEvent(current: Long, live: Boolean) = synchronized(lock) {
        if (current != run) return@synchronized
        streamEvents++
        lastEvent = live to clock()
        gate.onLive(live)
    }

    /** Reads until Twitch answers, then again after each of [confirmAfterMs]. */
    private suspend fun readAndConfirm(current: Long) {
        readUntilAnswered(current)
        var waited = 0L
        for (after in confirmAfterMs) {
            delay(after - waited)
            waited = after
            readUntilAnswered(current)
        }
    }

    private suspend fun readUntilAnswered(current: Long) {
        var attempt = 0
        while (!read(current)) {
            delay(retryDelaysMs.getOrElse(attempt) { retryDelaysMs.lastOrNull() ?: 60_000L })
            attempt++
        }
    }

    /** Reads again at [atMs] (one pending re-read at a time), unless a stream event came meanwhile. Caller holds [lock]. */
    private fun readAgainAt(current: Long, atMs: Long) {
        if (reReadPending) return
        reReadPending = true
        val eventsNow = streamEvents
        scope.launch {
            delay((atMs - clock()).coerceAtLeast(0) + 1)
            val stillDoubtful = synchronized(lock) { reReadPending = false; current == run && streamEvents == eventsNow }
            if (stillDoubtful) readUntilAnswered(current)
        }
    }

    /** Guarded by [lock]: a re-read after a contradicting answer is waiting. */
    private var reReadPending = false

    /** One read of the live status, applied unless a stream event says otherwise; false when Twitch did not answer. */
    private suspend fun read(current: Long): Boolean = reading.withLock {
        val eventsBefore = synchronized(lock) { streamEvents }
        val result = try {
            api.liveStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock false
        }
        val status = (result as? TwitchResult.Ok)?.value ?: return@withLock false
        val live = status.live
        synchronized(lock) {
            val event = lastEvent
            val contradictsRecentEvent = event != null && event.first != live && clock() - event.second < eventTrustMs
            if (current == run && streamEvents == eventsBefore && !contradictsRecentEvent) {
                gate.onLive(live, read = true, startedAtMs = status.startedAtEpochMs)
            }
            // Not dropped for good: once the event is old enough to doubt, Twitch is asked again.
            if (contradictsRecentEvent && current == run) readAgainAt(current, event!!.second + eventTrustMs)
        }
        true
    }
}
