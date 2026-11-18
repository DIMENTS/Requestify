package nl.diments.requestify.core.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import nl.diments.requestify.core.stream.RequestsGate
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

/**
 * Tells the [gate] whether the stream is live, while the Twitch session is connected:
 *
 * - `stream.online` / `stream.offline` apply at once.
 * - The live status is read over Helix ([TwitchApi.isLive]) at [start] and after every fresh EventSub session, whose
 *   events in between are lost. Reads run one at a time; a failed read leaves the gate as it is.
 * - A read that a stream event overtook while it was under way is dropped: the event is newer (and Helix lags behind
 *   the events).
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
) {
    constructor(eventSub: EventSubClient, api: TwitchApi, gate: RequestsGate, scope: CoroutineScope) :
        this(eventSub.events, eventSub.freshSessions, api, gate, scope)

    private val lock = Any()

    /** Guarded by [lock]. */
    private var job: Job? = null

    /** Bumped by [start] and [stop]: only the current run reaches the gate. Guarded by [lock]. */
    private var run = 0L

    /** Stream events applied so far: a read applies only when none came in while it was under way. Guarded by [lock]. */
    private var streamEvents = 0L

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
                    launch { for (read in reads) readLiveStatus(current) }
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
        gate.onLive(live)
    }

    private suspend fun readLiveStatus(current: Long) {
        val eventsBefore = synchronized(lock) { streamEvents }
        val result = try {
            api.isLive()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // like any failed read: the gate stays as it is
        }
        val live = (result as? TwitchResult.Ok)?.value ?: return
        synchronized(lock) {
            if (current == run && streamEvents == eventsBefore) gate.onLive(live)
        }
    }
}
