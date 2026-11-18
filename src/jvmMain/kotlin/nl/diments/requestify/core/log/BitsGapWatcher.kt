package nl.diments.requestify.core.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.stream.GateEvent
import nl.diments.requestify.core.twitch.eventsub.EventSubState

/**
 * Twitch keeps no history of Bits: a cheer made while Requestify could not hear it is gone. This reports such a
 * time as a gap for the Log, so it is never silent:
 * - EventSub was disconnected while the stream was live: from the moment it was lost until it is connected again
 *   (a `session_reconnect` never leaves [EventSubState.Connected], so it is no gap);
 * - the app was closed during the stream ([loadedLastSeenLiveMs] is when the last run last saw it live): from then
 *   until the first live confirmation of this run;
 * - the app started while the stream was already live (its first live confirmation came from a read, not from
 *   stream.online) and nothing was saved: from an unknown moment until [appStartMs].
 */
class BitsGapWatcher(
    private val eventSubState: StateFlow<EventSubState>,
    private val gateEvents: Flow<GateEvent>,
    private val loadedLastSeenLiveMs: Long?,
    private val appStartMs: Long,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _gaps = MutableSharedFlow<LogRecord.GapNoticed>(extraBufferCapacity = Int.MAX_VALUE)
    val gaps: SharedFlow<LogRecord.GapNoticed> = _gaps.asSharedFlow()

    private val lock = Any()
    private var job: Job? = null

    // Guarded by [lock].
    private var live = false
    private var firstLiveSeen = false
    private var lostWhileLiveSince: Long? = null

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) { gateEvents.collect(::onGate) }
                eventSubState.collect(::onEventSub)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    private fun onGate(event: GateEvent) {
        val gap = synchronized(lock) {
            when (event) {
                is GateEvent.WentLive -> {
                    live = true
                    if (firstLiveSeen) return@synchronized null
                    firstLiveSeen = true
                    when {
                        loadedLastSeenLiveMs != null -> gap(loadedLastSeenLiveMs, event.atEpochMs)
                        event.byRead -> gap(null, appStartMs)
                        else -> null
                    }
                }
                is GateEvent.WentOffline -> {
                    live = false
                    null
                }
                else -> null
            }
        }
        gap?.let { _gaps.tryEmit(it) }
    }

    private fun onEventSub(state: EventSubState) {
        val gap = synchronized(lock) {
            when (state) {
                is EventSubState.Disconnected -> {
                    if (live && lostWhileLiveSince == null) lostWhileLiveSince = state.sinceEpochMs
                    null
                }
                EventSubState.Connected -> lostWhileLiveSince?.let { since ->
                    lostWhileLiveSince = null
                    gap(since, clock())
                }
                else -> null
            }
        }
        gap?.let { _gaps.tryEmit(it) }
    }

    private fun gap(from: Long?, to: Long) =
        LogRecord.GapNoticed("gap:${RequestSource.BITS.name}:$to", to, RequestSource.BITS, from, to)
}
