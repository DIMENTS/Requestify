package nl.diments.requestify.core.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState

/** What the gate remembers in stream.json, so a restart carries on where it left off. */
@Serializable
data class StreamState(
    val live: Boolean = false,
    /** Manual open (true) or closed (false); null = follow the stream. */
    val override: Boolean? = null,
    /** The override was set while offline: it survives the stream's end and postpones the clear. */
    val overrideAfterStream: Boolean = false,
    /** When the stream went offline, while in the grace. */
    val offlineSinceMs: Long? = null,
    /** The grace ran out while requests were open by hand: the clear runs when they close. */
    val clearPostponed: Boolean = false,
    /** The plain switch used without a Twitch login. */
    val openWithoutTwitch: Boolean = true,
)

/** The gate as the title bar shows it. */
data class GateStatus(
    val open: Boolean,
    /** Logged in to Twitch: requests follow the stream. */
    val twitch: Boolean,
    val live: Boolean,
    /** With Twitch, a manual open or close decides, not the stream. */
    val manual: Boolean,
    /** When the free requests will be cleared, while in the grace after the stream went offline. */
    val clearAtEpochMs: Long?,
    /** The clear waits until the streamer closes requests. */
    val clearPostponed: Boolean,
)

/**
 * Whether requests are open. With Twitch they follow the stream: open when live, closed at once when it goes
 * offline. The stream counts as ended only after [graceMs] offline; a return before that is a drop that changes
 * nothing, a manual close included. At the real end an override set while live ends and [onStreamEnded] clears the
 * free requests, unless the streamer opened requests by hand after the stream went offline: then the clear waits
 * until they close them. Without Twitch it is a plain switch, default open. Every change is saved before it
 * applies; a change that cannot be saved is not applied.
 */
class RequestsGate(
    file: JsonFileStore<StreamState>,
    private val twitchConnected: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onStreamEnded: suspend () -> Unit,
    private val graceMs: Long = 600_000,
) {
    private val state = PersistedState(file) { StreamState() }
    private val lock = Any()
    private val _open = MutableStateFlow(state.value.value.isOpen(twitchConnected.value))
    private val _status = MutableStateFlow(state.value.value.status(twitchConnected.value))

    /** The grace [timer] waits out (its offline moment), guarded by [lock]. */
    private var timerFor: Long? = null
    private var timer: Job? = null

    val open: StateFlow<Boolean> = _open.asStateFlow()
    val status: StateFlow<GateStatus> = _status.asStateFlow()

    init {
        scope.launch { twitchConnected.collect { synchronized(lock) { publish() } } }
    }

    /** Resumes a grace that was running when the app closed; one that ran out meanwhile ends the stream at once. */
    fun start() = synchronized(lock) { syncTimer() }

    /** The stream's live status: stream.online/offline and the REST check. A repeated value changes nothing. */
    fun onLive(live: Boolean) {
        change { s ->
            when {
                s.live == live -> s
                !live -> s.copy(live = false, offlineSinceMs = clock())
                // A drop: as if nothing happened. An override made during it now belongs to the stream it returns to.
                s.offlineSinceMs != null -> s.copy(live = true, offlineSinceMs = null, overrideAfterStream = false)
                else -> s.copy(live = true, override = null, overrideAfterStream = false, clearPostponed = false)
            }
        }
    }

    /** The title-bar button. */
    fun toggle() {
        var clear = false
        val changed = change { s ->
            when {
                !twitchConnected.value -> s.copy(openWithoutTwitch = !s.openWithoutTwitch)
                s.live -> s.copy(override = !s.openWithTwitch(), overrideAfterStream = false)
                else -> {
                    val opens = !s.openWithTwitch()
                    clear = !opens && s.clearPostponed
                    s.copy(override = opens, overrideAfterStream = true, clearPostponed = s.clearPostponed && !clear)
                }
            }
        }
        if (changed && clear) scope.launch { onStreamEnded() }
    }

    /** The grace that started at [since] ran out: the stream really ended. */
    private fun endGrace(since: Long) {
        var clear = false
        val changed = change { s ->
            if (s.offlineSinceMs != since) return@change s // the stream came back first
            val ended = s.copy(offlineSinceMs = null, override = if (s.overrideAfterStream) s.override else null)
            clear = !ended.openWithTwitch()
            ended.copy(clearPostponed = !clear)
        }
        if (changed && clear) scope.launch { onStreamEnded() }
    }

    /** Saves [f]'s result, then applies it. Returns false when it could not be saved; nothing changed then. */
    private fun change(f: (StreamState) -> StreamState): Boolean = synchronized(lock) {
        if (!state.change(f)) return false
        publish()
        syncTimer()
        true
    }

    /** Runs a timer for the grace in the state, if any. Caller holds [lock]. */
    private fun syncTimer() {
        val since = state.value.value.offlineSinceMs
        if (since == timerFor) return
        timer?.cancel() // harmless at the grace's end, from the timer itself: it does not suspend again
        timerFor = since
        timer = since?.let {
            scope.launch {
                delay(since + graceMs - clock())
                endGrace(since)
            }
        }
    }

    /** Caller holds [lock]. */
    private fun publish() {
        val s = state.value.value
        val twitch = twitchConnected.value
        _open.value = s.isOpen(twitch)
        _status.value = s.status(twitch)
    }

    private fun StreamState.openWithTwitch(): Boolean =
        if (offlineSinceMs != null) override == true && overrideAfterStream else override ?: live

    private fun StreamState.isOpen(twitch: Boolean): Boolean = if (twitch) openWithTwitch() else openWithoutTwitch

    private fun StreamState.status(twitch: Boolean) = GateStatus(
        open = isOpen(twitch),
        twitch = twitch,
        live = live,
        manual = twitch && override != null && (offlineSinceMs == null || overrideAfterStream),
        clearAtEpochMs = offlineSinceMs?.plus(graceMs),
        clearPostponed = clearPostponed,
    )
}
