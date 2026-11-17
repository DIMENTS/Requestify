package nl.diments.requestify.core.stream

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.StoreReadException

/** What the gate remembers in stream.json, so a restart carries on where it left off. */
@Serializable
data class StreamState(
    val live: Boolean = false,
    /** Manual open (true) or closed (false); null = follow the stream. */
    val override: Boolean? = null,
    /** The override was set while offline: it survives the stream's end. */
    val overrideAfterStream: Boolean = false,
    /** When the stream went offline, while in the grace. */
    val offlineSinceMs: Long? = null,
    /** Requests were opened by hand while offline: the end-of-stream clear waits until they close again. */
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
    /** The clear waits until the streamer closes requests (then [clearAtEpochMs] is null). */
    val clearPostponed: Boolean,
)

/**
 * Whether requests are open. With Twitch they follow the stream: open when live, closed at once when it goes
 * offline. The stream counts as ended only after [graceMs] offline; a return before that is a drop that changes
 * nothing, a manual close included. At the real end an override set while live ends and [onStreamEnded] clears the
 * free requests. Opening by hand while offline postpones that clear until the streamer closes requests again
 * (after the grace; inside it the grace's end clears); going live cancels it. Without Twitch it is a plain switch,
 * default open.
 *
 * Every change is saved before it applies. A stream event applies even when it cannot be saved, so a failed save
 * never leaves requests open while offline; the file catches up at the next change or [start]. A toggle that
 * cannot be saved is not applied.
 */
class RequestsGate(
    private val file: JsonFileStore<StreamState>,
    private val twitchConnected: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onStreamEnded: suspend () -> Unit,
    private val graceMs: Long = 600_000,
) {
    private val lock = Any()
    private val loaded: StreamState? = try {
        file.read()
    } catch (_: StoreReadException) {
        null
    }

    /** The state that applies; guarded by [lock], like the fields below. */
    private val state = MutableStateFlow(loaded ?: StreamState())

    /** The file could not be read at start: nothing is written over it until a read succeeds. */
    private var loadFailed = loaded == null

    /** [state] holds a stream event the file does not have yet. */
    private var unsaved = false

    /** The grace [timer] waits out (its offline moment). */
    private var timerFor: Long? = null
    private var timer: Job? = null

    private val _open = MutableStateFlow(state.value.isOpen(twitchConnected.value))
    private val _status = MutableStateFlow(state.value.status(twitchConnected.value))

    val open: StateFlow<Boolean> = _open.asStateFlow()
    val status: StateFlow<GateStatus> = _status.asStateFlow()

    init {
        scope.launch { twitchConnected.collect { synchronized(lock) { publish() } } }
    }

    /**
     * Resumes a grace that was running when the app closed; one that ran out meanwhile ends the stream at once.
     * Saves a stream event that could not be saved before.
     */
    fun start() {
        change(event = true) { it }
    }

    /** The stream's live status: stream.online/offline and the REST check. A repeated value changes nothing. */
    fun onLive(live: Boolean) {
        change(event = true) { s ->
            when {
                s.live == live -> s
                !live -> s.copy(live = false, offlineSinceMs = clock())
                // A drop: as if nothing happened. An open made during it now belongs to the stream it returns to.
                s.offlineSinceMs != null ->
                    s.copy(live = true, offlineSinceMs = null, overrideAfterStream = false, clearPostponed = false)
                else -> s.copy(live = true, override = null, overrideAfterStream = false, clearPostponed = false)
            }
        }
    }

    /** The title-bar button. */
    fun toggle() {
        var clear = false
        val changed = change(event = false) { s ->
            when {
                !twitchConnected.value -> s.copy(openWithoutTwitch = !s.openWithoutTwitch)
                s.live -> s.copy(override = !s.openWithTwitch(), overrideAfterStream = false)
                else -> {
                    val opens = !s.openWithTwitch()
                    // Inside the grace a close leaves the clear to the grace's end, so a drop still changes nothing.
                    clear = !opens && s.clearPostponed && s.offlineSinceMs == null
                    s.copy(override = opens, overrideAfterStream = true, clearPostponed = opens)
                }
            }
        }
        if (changed && clear) scope.launch { onStreamEnded() }
    }

    /** The grace that started at [since] ran out: the stream really ended. */
    private fun endGrace(since: Long) {
        var clear = false
        change(event = true) { s ->
            if (s.offlineSinceMs != since) return@change s // the stream came back first
            val ended = s.copy(offlineSinceMs = null, override = if (s.overrideAfterStream) s.override else null)
            clear = !ended.openWithTwitch()
            ended.copy(clearPostponed = !clear)
        }
        if (clear) scope.launch { onStreamEnded() }
    }

    /**
     * Applies [f] and saves the result before anything acts on it. A stream [event] applies even when it cannot be
     * saved ([unsaved]); anything else is then not applied and this returns false.
     */
    private fun change(event: Boolean, f: (StreamState) -> StreamState): Boolean = synchronized(lock) {
        val readable = !loadFailed || reload()
        val current = state.value
        val next = f(current)
        if (next != current || unsaved) {
            val saved = readable && save(next) // never over a file that could not be read
            if (!saved && !event) return false
            state.value = next
            unsaved = !saved
        }
        publish()
        syncTimer()
        true
    }

    /** Reads the file again after it could not be read at start; true when it now can. Caller holds [lock]. */
    private fun reload(): Boolean {
        val saved = try {
            file.read()
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        loadFailed = false
        // Stream events applied meanwhile are newer than the file; the switch without Twitch only the file knows.
        state.value = if (unsaved) state.value.copy(openWithoutTwitch = saved.openWithoutTwitch) else saved
        return true
    }

    private fun save(value: StreamState): Boolean = try {
        file.write(value)
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Runs a timer for the grace in the state, if any. Caller holds [lock]. */
    private fun syncTimer() {
        val since = state.value.offlineSinceMs
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
        val s = state.value
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
        clearAtEpochMs = if (clearPostponed) null else offlineSinceMs?.plus(graceMs),
        clearPostponed = clearPostponed,
    )
}
