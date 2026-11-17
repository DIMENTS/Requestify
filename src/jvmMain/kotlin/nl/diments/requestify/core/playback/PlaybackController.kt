package nl.diments.requestify.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.spotify.SpotifyPlayer
import nl.diments.requestify.core.spotify.SpotifyResult

/**
 * What Spotify plays right now. [request] is null for the streamer's own music. [progressMs] is the position at
 * [sampledAtEpochMs] (wall time); [positionAt] tells where it is later.
 */
data class NowPlaying(
    val track: Track,
    val progressMs: Long,
    val isPlaying: Boolean,
    val request: Request?,
    val sampledAtEpochMs: Long,
) {
    /**
     * The position at [nowEpochMs], within the track: it runs on from [progressMs] while playing and stays put
     * while paused.
     */
    fun positionAt(nowEpochMs: Long): Long {
        val sinceSampleMs = if (isPlaying) nowEpochMs - sampledAtEpochMs else 0
        return (progressMs + sinceSampleMs).coerceIn(0, track.durationMs.coerceAtLeast(0))
    }
}

sealed interface SpotifyHealth {
    data object Ok : SpotifyHealth
    data object NothingPlaying : SpotifyHealth
    data object NoActiveDevice : SpotifyHealth

    /** The saved login stopped working: log in again. */
    data object LoginNeeded : SpotifyHealth

    /** Spotify was never connected (or was logged out). */
    data object LoggedOut : SpotifyHealth
    data object PremiumRequired : SpotifyHealth
    data class RateLimited(val untilEpochMs: Long) : SpotifyHealth
    data class Unreachable(val message: String) : SpotifyHealth
}

/**
 * [playIntentSettleMs]: how long a pause or resume is shown while Spotify still reports the state from before
 * it (its Web API takes a moment to catch up).
 */
data class PlaybackConfig(
    val handoverLeadMs: Long = 15_000,
    val idlePollMs: Long = 3_000,
    val nearEndPollMs: Long = 1_000,
    val nearEndWindowMs: Long = 20_000,
    val playIntentSettleMs: Long = 4_000,
)

/**
 * Owns the request queue and hands its head to Spotify shortly before the current track ends. With an
 * empty queue or closed requests nothing is handed over, so Spotify keeps playing the streamer's own music.
 *
 * Two locks, always taken in this order. [spotifyMutex] lets one Spotify sequence (a tick, [skip] or the
 * command of [togglePause]) run at a time and is held across its network calls. [stateMutex] guards all state
 * and every save; it is held only for in-memory work and the save, never across a Spotify call, so queue edits
 * and play/pause show (and edits are saved) at once even while Spotify is slow. A Spotify answer is applied to
 * the state as it is by then.
 * A failed Spotify call never drops a request: it stays where it was and the next tick tries again.
 *
 * Nothing is handed over or saved before the saved state has been read. While it cannot be read ([loadError]),
 * edits wait in order and are applied on top of it once a later tick or edit reads it.
 */
class PlaybackController(
    private val player: SpotifyPlayer,
    private val store: QueueStore,
    private val requestsOpen: StateFlow<Boolean>,
    private val paidPriority: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: PlaybackConfig = PlaybackConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _queue = MutableStateFlow(RequestQueue())
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _history = MutableStateFlow<List<PlayedEntry>>(emptyList())
    private val _health = MutableStateFlow<SpotifyHealth>(SpotifyHealth.NothingPlaying)
    private val _persistError = MutableStateFlow<String?>(null)
    private val _loadError = MutableStateFlow<String?>(null)
    private val _handedOver = MutableStateFlow<Request?>(null)

    val queue: StateFlow<RequestQueue> = _queue.asStateFlow()
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The request in Spotify's queue that has not started yet: it is in neither [queue] nor [nowPlaying]. */
    val handedOver: StateFlow<Request?> = _handedOver.asStateFlow()

    /** Newest first, at most [QueueStore.MAX_HISTORY] entries. */
    val history: StateFlow<List<PlayedEntry>> = _history.asStateFlow()
    val health: StateFlow<SpotifyHealth> = _health.asStateFlow()

    /**
     * Why the last save failed, or null when everything is saved. A failed save is retried by the next change or
     * tick.
     */
    val persistError: StateFlow<String?> = _persistError.asStateFlow()

    /**
     * Why the saved state could not be read, or null once it is loaded. Until then nothing is handed over or
     * saved; the next tick or edit tries again.
     */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val spotifyMutex = Mutex()
    private val stateMutex = Mutex()

    // Guarded by [stateMutex], like every write to the state flows above. Only holders of [spotifyMutex]
    // change [handedOver], [playing] and [lastTrackId] (after the first load), so they stay put during a sequence.
    private var loaded = false
    private var playing: Request? = null
    private var lastTrackId: String? = null
    private var lastSaved: PersistedPlayback? = null

    /** The pause or resume Spotify has not confirmed yet. Guarded by [stateMutex]; never saved. */
    private var playIntent: PlayIntent? = null

    /** Edits made before the saved state could be read, in order. Guarded by [stateMutex]. */
    private val waitingEdits = mutableListOf<() -> Unit>()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    /** Starts polling Spotify; does nothing when the loop already runs. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            launch { requestsOpen.drop(1).collect { open -> if (open) wake.trySend(Unit) } }
            while (true) {
                val next = tickSafely()
                if (next.wakeable) withTimeoutOrNull(next.afterMs) { wake.receive() } else delay(next.afterMs)
            }
        }
    }

    /**
     * Stops polling. Every accepted change has been saved or is being saved; when the app exits right after, a
     * save still in flight can be cut off. The atomic write keeps the previous file then, so at most the last
     * change is lost.
     */
    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Adds [request] unless a request with its id is already queued, handed over or playing. */
    fun enqueue(request: Request) {
        change {
            val known = _queue.value.items.any { it.id == request.id } ||
                _handedOver.value?.id == request.id ||
                playing?.id == request.id
            if (!known) _queue.value = _queue.value.add(request, paidPriority())
        }
    }

    fun remove(id: String) {
        change { _queue.value = _queue.value.remove(id) }
    }

    fun move(id: String, toIndex: Int) {
        change { _queue.value = _queue.value.move(id, toIndex) }
    }

    /**
     * Hands the next request over first (when it may go), so it starts right away, then skips. While the saved
     * queue cannot be read nothing is handed over, but the skip still happens.
     */
    suspend fun skip() {
        spotifyMutex.withLock {
            val head = if (syncState()) withState { handOverCandidate(_nowPlaying.value?.track?.id) } else null
            if (head != null) {
                val result = player.addToQueue(head.track.uri)
                withState { if (result is SpotifyResult.Ok) markHandedOver(head) else _health.value = healthOf(result) }
            }
            player.skipNext()
            wake.trySend(Unit)
        }
    }

    /**
     * Shows paused or playing at once, then tells Spotify. Each call flips what is shown, so quick clicks each
     * count, and their commands reach Spotify in click order. Polls keep showing it until Spotify reports it too
     * (it lags behind a command) or [PlaybackConfig.playIntentSettleMs] has passed. When Spotify refuses, the
     * state flips back and [health] says why.
     */
    suspend fun togglePause() {
        // Only [nowPlaying] and the intent change here, and neither is saved: no loaded state is needed, and the
        // change shows without waiting for a Spotify sequence in flight.
        val intent = stateMutex.withLock { showToggled() }
        spotifyMutex.withLock {
            // With nothing shown there is nothing to pause: ask Spotify to play.
            val result = if (intent != null && !intent.shown.isPlaying) player.pause() else player.resume()
            if (result !is SpotifyResult.Ok) stateMutex.withLock { refused(intent, result) }
            wake.trySend(Unit)
        }
    }

    /**
     * Shows [nowPlaying] flipped between paused and playing, from where it is now, and keeps that as the intent
     * Spotify has to confirm. Null when nothing is shown. Caller holds [stateMutex].
     */
    private fun showToggled(): PlayIntent? {
        val current = _nowPlaying.value ?: return null
        val now = clock()
        val toggled = current.copy(progressMs = current.positionAt(now), isPlaying = !current.isPlaying, sampledAtEpochMs = now)
        _nowPlaying.value = toggled
        return PlayIntent(toggled, untilEpochMs = now + config.playIntentSettleMs).also { playIntent = it }
    }

    /**
     * Spotify did not take [intent]'s command: unless a later click or Spotify itself replaced the intent, it
     * ends and the state flips back. Caller holds [stateMutex].
     */
    private fun refused(intent: PlayIntent?, result: SpotifyResult<*>) {
        if (intent != null && intent === playIntent) {
            playIntent = null
            val now = clock()
            _nowPlaying.value = _nowPlaying.value?.let {
                it.copy(progressMs = it.positionAt(now), isPlaying = !intent.shown.isPlaying, sampledAtEpochMs = now)
            }
        }
        _health.value = healthOf(result)
    }

    /**
     * Applies [update], saves it and wakes the loop; before the saved state could be read, the edit waits for it
     * (see [syncLocked]). The coroutine starts undispatched, so edits take the lock in call order and show at once
     * when the lock is free. Once accepted, an edit is applied and saved even if [scope] is cancelled meanwhile.
     */
    private fun change(update: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    waitingEdits += update
                    syncLocked()
                }
            }
        }
    }

    /** [syncLocked] under [stateMutex]. */
    private suspend fun syncState(): Boolean = stateMutex.withLock { syncLocked() }

    /**
     * Reads the saved state the first time, applies the edits that waited for it and saves when the state changed
     * (so it also retries a failed save). False while the saved state cannot be read: nothing is applied or saved
     * then, so the saved file is never replaced by an empty queue. Caller holds [stateMutex].
     */
    private suspend fun syncLocked(): Boolean {
        if (!loaded && !load()) return false
        if (waitingEdits.isNotEmpty()) {
            val edits = waitingEdits.toList()
            waitingEdits.clear()
            edits.forEach { it() }
            wake.trySend(Unit) // under the lock, so a tick that reads the queue after this drains it
        }
        persistIfChanged()
        return true
    }

    /**
     * Runs [block] under [stateMutex] on the loaded state (only after [syncState] returned true), then saves the
     * state if it changed, so every exit also retries a failed save. [block] cannot suspend, so it can never call
     * Spotify while holding the lock.
     */
    private suspend fun <T> withState(block: () -> T): T = stateMutex.withLock {
        check(loaded) { "the saved state is not loaded" }
        try {
            block()
        } finally {
            persistIfChanged()
        }
    }

    private suspend fun tickSafely(): NextTick = spotifyMutex.withLock {
        // Without the saved state nothing can be handed over safely: the next poll tries to read it again.
        if (!syncState()) return@withLock NextTick(config.idlePollMs)
        try {
            tick()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // stopped: end the loop. A stray one from a callee: carry on.
            withState { crashed(e) }
        } catch (e: Exception) {
            withState { crashed(e) }
        }
    }

    private fun crashed(e: Exception): NextTick {
        wake.tryReceive() // like [failed]
        _health.value = SpotifyHealth.Unreachable(e.message ?: e::class.simpleName ?: "error")
        return NextTick(config.idlePollMs)
    }

    /**
     * Spotify calls run between the state sections; health is published once, at the end. The section that
     * picks the head drains [wake]: it sees every change made before it, and a later change wakes the loop again.
     */
    private suspend fun tick(): NextTick {
        val cp = when (val result = player.currentlyPlaying()) {
            is SpotifyResult.Ok -> result.value
            else -> return withState { failed(result) }
        }
        val track = cp?.track
        val spotifyQueue = if (withState { needsSpotifyQueueCheck(track?.id) }) {
            when (val result = player.userQueueTrackIds()) {
                is SpotifyResult.Ok -> result.value
                else -> return withState { failed(result) }
            }
        } else {
            null
        }
        val remainingMs = if (cp != null && track != null) track.durationMs - cp.progressMs else null
        val head = withState {
            wake.tryReceive()
            if (track?.id != lastTrackId) settleTrackChange(track?.id, spotifyQueue)
            val polled = if (cp != null && track != null) {
                NowPlaying(track, cp.progressMs, cp.isPlaying, playing?.takeIf { it.track.id == track.id }, clock())
            } else {
                null
            }
            _nowPlaying.value = withPlayIntent(polled)
            if (remainingMs != null && remainingMs <= config.handoverLeadMs) handOverCandidate(track?.id) else null
        }
        val handOverResult = head?.let { player.addToQueue(it.track.uri) }
        return withState {
            if (head != null && handOverResult is SpotifyResult.Ok) markHandedOver(head)
            _health.value = when {
                handOverResult != null && handOverResult !is SpotifyResult.Ok -> healthOf(handOverResult)
                cp == null -> SpotifyHealth.NothingPlaying
                else -> SpotifyHealth.Ok
            }
            when {
                handOverResult is SpotifyResult.RateLimited -> NextTick(retryAfterMs(handOverResult), wakeable = false)
                playIntent != null -> NextTick(config.nearEndPollMs) // soon picks up Spotify's confirmation
                remainingMs != null && remainingMs <= config.nearEndWindowMs -> NextTick(config.nearEndPollMs)
                else -> NextTick(config.idlePollMs)
            }
        }
    }

    /**
     * What to show for a poll. Right after a pause or resume Spotify can still report the state from before it, so
     * while it reports the opposite of [playIntent] for the same track, and the intent has not expired, the intent
     * is shown: a pause stays frozen where it was, a resume runs on from when it was clicked. Otherwise the intent
     * ends and Spotify is trusted again. Caller holds [stateMutex].
     */
    private fun withPlayIntent(polled: NowPlaying?): NowPlaying? {
        val intent = playIntent ?: return polled
        val shown = intent.shown
        if (polled == null || polled.track.id != shown.track.id || polled.isPlaying == shown.isPlaying ||
            clock() >= intent.untilEpochMs
        ) {
            playIntent = null
            return polled
        }
        return if (shown.isPlaying) {
            polled.copy(isPlaying = true, sampledAtEpochMs = shown.sampledAtEpochMs)
        } else {
            polled.copy(isPlaying = false, progressMs = shown.progressMs, sampledAtEpochMs = shown.sampledAtEpochMs)
        }
    }

    /** A track ended that wasn't the handed-over one: only Spotify's queue can tell whether it still has it. */
    private fun needsSpotifyQueueCheck(trackId: String?): Boolean {
        val sent = _handedOver.value ?: return false
        return trackId != lastTrackId && lastTrackId != null && sent.track.id != trackId
    }

    /**
     * Spotify moved on to [trackId]: the playing request is done, and the handed-over one either plays now or,
     * when [spotifyQueue] no longer has it, goes back to the head. A failed queue check never gets here, so
     * [lastTrackId] stays put and the next tick settles the change again.
     */
    private fun settleTrackChange(trackId: String?, spotifyQueue: List<String>?) {
        playing?.let {
            if (it.track.id != trackId) {
                _history.value = (listOf(PlayedEntry(it, clock())) + _history.value).take(QueueStore.MAX_HISTORY)
                playing = null
            }
        }
        val sent = _handedOver.value
        if (sent != null && sent.track.id == trackId) {
            playing = sent
            _handedOver.value = null
        } else if (sent != null && spotifyQueue != null && sent.track.id !in spotifyQueue) {
            _queue.value = _queue.value.addFirst(sent)
            _handedOver.value = null
        }
        lastTrackId = trackId
    }

    /**
     * The head may go to Spotify when requests are open, nothing is waiting there yet, and it is not the track
     * playing now: Spotify would report that repeat under the same id, so its start could never be seen.
     */
    private fun handOverCandidate(currentTrackId: String?): Request? =
        _queue.value.head?.takeIf { requestsOpen.value && _handedOver.value == null && it.track.id != currentTrackId }

    /** Spotify has [request] now, even if it was moved or removed meanwhile: take it out of the current queue. */
    private fun markHandedOver(request: Request) {
        _handedOver.value = request
        _queue.value = _queue.value.remove(request.id)
    }

    /**
     * Ends a tick that could not go on. It drains [wake] too: a change made meanwhile is already saved and is
     * picked up by the next poll, without hitting a failing Spotify again at once.
     */
    private fun failed(result: SpotifyResult<*>): NextTick {
        wake.tryReceive()
        _health.value = healthOf(result)
        return if (result is SpotifyResult.RateLimited) {
            NextTick(retryAfterMs(result), wakeable = false)
        } else {
            NextTick(config.idlePollMs)
        }
    }

    private fun healthOf(result: SpotifyResult<*>): SpotifyHealth = when (result) {
        is SpotifyResult.Ok -> SpotifyHealth.Ok
        SpotifyResult.NoActiveDevice -> SpotifyHealth.NoActiveDevice
        SpotifyResult.PremiumRequired -> SpotifyHealth.PremiumRequired
        SpotifyResult.LoginNeeded -> SpotifyHealth.LoginNeeded
        SpotifyResult.LoggedOut -> SpotifyHealth.LoggedOut
        is SpotifyResult.RateLimited -> SpotifyHealth.RateLimited(clock() + retryAfterMs(result))
        is SpotifyResult.Failed -> SpotifyHealth.Unreachable(result.message)
        SpotifyResult.MissingPermission -> SpotifyHealth.Unreachable("missing permission")
    }

    /** Never shorter than [PlaybackConfig.nearEndPollMs], so a `Retry-After: 0` cannot make the loop spin. */
    private fun retryAfterMs(result: SpotifyResult.RateLimited): Long = maxOf(result.retryAfterMs, config.nearEndPollMs)

    /**
     * Reads the saved state before the first change or tick, so nothing written earlier is overwritten. False
     * (with [loadError] set) when it cannot be read for now. Caller holds [stateMutex].
     */
    private suspend fun load(): Boolean {
        val saved = try {
            withContext(ioDispatcher) { store.load() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _loadError.value = e.toString()
            return false
        }
        _queue.value = saved.queue
        _handedOver.value = saved.handedOver
        playing = saved.playing
        _history.value = saved.history
        lastSaved = saved
        loaded = true
        _loadError.value = null
        return true
    }

    /** Saves when the state differs from the last successful save; a failure shows in [persistError]. */
    private suspend fun persistIfChanged() {
        val snapshot = PersistedPlayback(_queue.value, _handedOver.value, playing, _history.value)
        if (snapshot == lastSaved) {
            _persistError.value = null // e.g. an unsaved edit was undone: nothing is left unsaved
            return
        }
        try {
            withContext(ioDispatcher + NonCancellable) { store.save(snapshot) }
            lastSaved = snapshot
            _persistError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _persistError.value = e.toString()
        }
    }

    /** When the loop polls again; a rate-limit wait is not cut short by wake-ups. */
    private class NextTick(val afterMs: Long, val wakeable: Boolean = true)

    /** A pause or resume Spotify has not confirmed yet: [shown] is what it showed, until [untilEpochMs]. */
    private class PlayIntent(val shown: NowPlaying, val untilEpochMs: Long)
}
