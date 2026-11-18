package nl.diments.requestify.core.stream

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.BackgroundWriter
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.SaveProblem
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
    /**
     * When the app last saw the stream live: at every live answer from Twitch, every minute while live, and when the
     * app stops. After the app was closed while live, the stream counts as offline since then at the latest.
     */
    val lastSeenLiveMs: Long? = null,
    /** When the stream the gate follows started (Helix's `started_at`, or when it went live): a restart can tell a new one. */
    val streamStartedAtEpochMs: Long? = null,
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

/** A change of the gate, for the Log: reported once it applies (saved, or applied unsaved as a stream event). */
sealed interface GateEvent {
    val atEpochMs: Long

    /** The stream is live: from stream.online, or from a REST read ([byRead]) such as the first check after a start. */
    data class WentLive(override val atEpochMs: Long, val byRead: Boolean) : GateEvent

    data class WentOffline(override val atEpochMs: Long) : GateEvent

    data class OpenedByHand(override val atEpochMs: Long) : GateEvent

    data class ClosedByHand(override val atEpochMs: Long) : GateEvent

    /** The stream really ended and the end-of-stream clear of the free requests starts now. */
    data class StreamEnded(override val atEpochMs: Long) : GateEvent
}

/**
 * Whether requests are open. With Twitch they follow the stream: open when live, closed at once when it goes
 * offline. The stream counts as ended only after [graceMs] offline; a return before that is a drop that changes
 * nothing, a manual close included. At the real end an override set while live ends and [onStreamEnded] clears the
 * free requests. Opening by hand while offline postpones that clear until the streamer closes requests again
 * (after the grace; inside it the grace's end clears); going live cancels it. Without Twitch it is a plain switch,
 * default open.
 *
 * A stream that was live when the app closed is not trusted at the next start: requests stay closed until Twitch
 * says it is live again (a toggle by hand still opens them). When Twitch reads it offline instead, it went offline
 * at the latest when the app last saw it live ([StreamState.lastSeenLiveMs]), so a restart long after the stream
 * ended ends it at once, and a quick restart carries on with the grace where it was.
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
    context: SaveContext,
    /** Whether the Twitch session is connected now: only then does the heartbeat know the stream is still live. */
    private val sessionConnected: () -> Boolean = { twitchConnected.value },
    private val graceMs: Long = 600_000,
    private val heartbeatMs: Long = 60_000,
) {
    private val lock = Any()
    private val loaded: StreamState? = try {
        file.read()
    } catch (_: StoreReadException) {
        null
    }

    /** Saves stream.json in the background. */
    private val writer = BackgroundWriter(file.fileName, file::write, context)

    /** Set while stream.json cannot be saved. */
    val saveProblem: StateFlow<SaveProblem?> get() = writer.problem

    /** Returns once every change made before this call is on disk. */
    suspend fun awaitSaved() = writer.awaitSaved()

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = writer.flush(timeoutMs)

    /** The state that applies; guarded by [lock], like the fields below. */
    private val state = MutableStateFlow(loaded ?: StreamState())

    /** The file could not be read at start: nothing is written over it until a read succeeds. */
    private var loadFailed = loaded == null

    /** [state] holds a stream event the file does not have yet. */
    private var unsaved = false

    /**
     * Twitch told the live status in this run. Until then a saved "live" is a leftover of the last run: requests stay
     * closed unless toggled by hand ([toggledUnconfirmed]).
     */
    private var heard = false

    /** Toggled by hand while a saved "live" was not confirmed yet: the toggle decides, as while live. */
    private var toggledUnconfirmed = false

    /** Between [start] and [stop]: the heartbeat may run. */
    private var running = false

    /** The grace [timer] waits out (its offline moment). */
    private var timerFor: Long? = null
    private var timer: Job? = null

    /** Saves [StreamState.lastSeenLiveMs] every [heartbeatMs] while the stream is live. */
    private var heartbeat: Job? = null

    private val _open = MutableStateFlow(state.value.isOpen(twitchConnected.value))
    private val _status = MutableStateFlow(state.value.status(twitchConnected.value))
    private val _inGrace = MutableStateFlow(state.value.offlineSinceMs != null)

    val open: StateFlow<Boolean> = _open.asStateFlow()
    val status: StateFlow<GateStatus> = _status.asStateFlow()

    /** The stream went offline and the grace runs: a live answer now is a drop. */
    val inGrace: StateFlow<Boolean> = _inGrace.asStateFlow()

    private val _events = MutableSharedFlow<GateEvent>(extraBufferCapacity = Int.MAX_VALUE)

    /** Every change, for the Log. It never drops or waits, and does not replay. */
    val events: SharedFlow<GateEvent> = _events.asSharedFlow()

    /**
     * When the last run saw the stream live, if stream.json said live at this start (the app closed during a stream);
     * null otherwise. For the Log's "possible missed Bits" while the app was off.
     */
    val loadedLastSeenLiveMs: Long? = loaded?.takeIf { it.live }?.lastSeenLiveMs

    init {
        scope.launch {
            twitchConnected.collect {
                synchronized(lock) {
                    publish()
                    syncHeartbeat()
                }
            }
        }
    }

    /**
     * Resumes a grace that was running when the app closed; one that ran out meanwhile ends the stream at once.
     * Saves a stream event that could not be saved before.
     */
    fun start() {
        synchronized(lock) { running = true }
        change(event = true) { it }
    }

    /** The app stops: remembers that the stream was live until now, and stops the heartbeat. */
    fun stop() {
        change(event = true) { s -> if (s.live && heard) s.copy(lastSeenLiveMs = clock()) else s }
        synchronized(lock) {
            running = false
            syncHeartbeat()
        }
    }

    /**
     * The stream's live status: stream.online/offline ([read] false) and the REST check ([read] true). A repeated
     * value changes nothing but when the stream was last seen live.
     */
    fun onLive(live: Boolean, read: Boolean = false, startedAtMs: Long? = null) {
        var clear = false
        var endedFirst = false
        val happened = mutableListOf<GateEvent>()
        change(event = true) { s ->
            val now = clock()
            // A saved "live" not confirmed in this run: Twitch's read can only say it is offline now, not since when.
            val leftover = s.live && !heard
            heard = true
            happened.clear() // [change] runs this once; cleared anyway so a list is never reported twice
            val savedStart = s.streamStartedAtEpochMs
            // The saved stream is not the one live now (it started later): that one ended while the app was closed.
            val newStream = leftover && live && startedAtMs != null && savedStart != null && startedAtMs > savedStart + SAME_STREAM_MS
            when {
                newStream -> {
                    val (afterEnd, clearOld) = ended(s.copy(live = false))
                    clear = clearOld
                    endedFirst = true
                    happened += GateEvent.WentLive(now, read)
                    afterEnd.copy(live = true, offlineSinceMs = null, clearPostponed = false, lastSeenLiveMs = now, streamStartedAtEpochMs = startedAtMs)
                }
                // Helix's started_at for the stream heard in this run is the truth (after a drop it is the reconnect).
                live && s.live -> s.copy(lastSeenLiveMs = now, streamStartedAtEpochMs = (if (!leftover) startedAtMs else null) ?: savedStart ?: startedAtMs ?: now)
                    .also { if (leftover) happened += GateEvent.WentLive(now, read) }
                // A drop: as if nothing happened. An open made during it now belongs to the stream it returns to.
                live && s.offlineSinceMs != null -> s.copy(
                    live = true, offlineSinceMs = null, overrideAfterStream = false, clearPostponed = false, lastSeenLiveMs = now,
                    streamStartedAtEpochMs = startedAtMs ?: now, // Twitch counts the reconnect as the stream's start
                ).also { happened += GateEvent.WentLive(now, read) }
                live -> s.copy(
                    live = true, override = null, overrideAfterStream = false, clearPostponed = false, lastSeenLiveMs = now,
                    streamStartedAtEpochMs = startedAtMs ?: now,
                ).also { happened += GateEvent.WentLive(now, read) }
                !s.live -> s
                else -> {
                    happened += GateEvent.WentOffline(now)
                    val since = if (leftover && read) minOf(now, s.lastSeenLiveMs ?: now) else now
                    val offline = s.copy(live = false, offlineSinceMs = since)
                    // Offline since longer than the grace: the stream ended while the app was closed.
                    if (since + graceMs > now) offline else ended(offline).also { clear = it.second }.first
                }
            }
        }
        if (endedFirst) streamEnded(clear) // the old stream ends before the new one goes live
        happened.forEach { _events.tryEmit(it) }
        if (!endedFirst) streamEnded(clear)
    }

    /** Reports the end of the stream and clears the free requests, when [clear]. Called outside [lock]. */
    private fun streamEnded(clear: Boolean) {
        if (!clear) return
        _events.tryEmit(GateEvent.StreamEnded(clock()))
        scope.launch { onStreamEnded() }
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
        if (!changed) return
        val nowOpen = synchronized(lock) {
            if (twitchConnected.value && !heard) toggledUnconfirmed = true
            publish()
            _open.value
        }
        val now = clock()
        _events.tryEmit(if (nowOpen) GateEvent.OpenedByHand(now) else GateEvent.ClosedByHand(now))
        streamEnded(clear)
    }

    /** The grace that started at [since] ran out: the stream really ended. */
    private fun endGrace(since: Long) {
        var clear = false
        change(event = true) { s ->
            if (s.offlineSinceMs != since) return@change s // the stream came back first
            ended(s).also { clear = it.second }.first
        }
        streamEnded(clear)
    }

    /**
     * [s] with its grace over: an override set while live ends; the free requests are cleared (second: true) unless
     * requests are open by hand, which postpones the clear.
     */
    private fun ended(s: StreamState): Pair<StreamState, Boolean> {
        val ended = s.copy(offlineSinceMs = null, override = if (s.overrideAfterStream) s.override else null)
        val clear = !ended.openWithTwitch()
        return ended.copy(clearPostponed = !clear) to clear
    }

    /**
     * Applies [f] and hands the result to the background save. While the file could not be read yet, a stream [event]
     * still applies in memory ([unsaved]) and anything else is not applied (false); nothing is written over that file.
     */
    private fun change(event: Boolean, f: (StreamState) -> StreamState): Boolean = synchronized(lock) {
        val readable = !loadFailed || reload()
        val current = state.value
        val next = f(current)
        if (next != current || unsaved) {
            if (!readable && !event) return false
            state.value = next
            if (readable) writer.save(next)
            unsaved = !readable
        }
        publish()
        syncTimer()
        syncHeartbeat()
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

    /**
     * Runs the heartbeat while the app runs with Twitch and the stream is live as Twitch confirmed. It never cancels
     * itself: while it runs, its own saves leave it running. Caller holds [lock].
     */
    private fun syncHeartbeat() {
        val wanted = running && twitchConnected.value && state.value.live && heard
        if (wanted == (heartbeat?.isActive == true)) return
        heartbeat?.cancel()
        heartbeat = if (!wanted) {
            null
        } else {
            scope.launch {
                while (true) {
                    delay(heartbeatMs)
                    if (sessionConnected()) change(event = true) { s -> if (s.live && heard) s.copy(lastSeenLiveMs = clock()) else s }
                }
            }
        }
    }

    /** Caller holds [lock]. */
    private fun publish() {
        val s = state.value
        val twitch = twitchConnected.value
        _open.value = s.isOpen(twitch)
        _status.value = s.status(twitch)
        _inGrace.value = s.offlineSinceMs != null
    }

    /** A saved "live" that Twitch has not confirmed in this run (and no toggle decided meanwhile). Caller holds [lock]. */
    private fun StreamState.liveUnconfirmed(): Boolean = live && !heard && !toggledUnconfirmed

    private fun StreamState.openWithTwitch(): Boolean = when {
        offlineSinceMs != null -> override == true && overrideAfterStream
        liveUnconfirmed() -> false
        else -> override ?: live
    }

    private fun StreamState.isOpen(twitch: Boolean): Boolean = if (twitch) openWithTwitch() else openWithoutTwitch

    private fun StreamState.status(twitch: Boolean) = GateStatus(
        open = isOpen(twitch),
        twitch = twitch,
        live = live && heard, // a saved "live" shows as live only once Twitch confirms it
        manual = twitch && override != null && (offlineSinceMs == null || overrideAfterStream) && !liveUnconfirmed(),
        clearAtEpochMs = if (clearPostponed) null else offlineSinceMs?.plus(graceMs),
        clearPostponed = clearPostponed,
    )
}

/** Helix's `started_at` and the moment the app saw `stream.online` differ by seconds: within this, the same stream. */
private const val SAME_STREAM_MS = 120_000L
