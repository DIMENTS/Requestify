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

/** What Spotify plays right now. [request] is null for the streamer's own music. */
data class NowPlaying(val track: Track, val progressMs: Long, val isPlaying: Boolean, val request: Request?)

sealed interface SpotifyHealth {
    data object Ok : SpotifyHealth
    data object NothingPlaying : SpotifyHealth
    data object NoActiveDevice : SpotifyHealth
    data object LoginNeeded : SpotifyHealth
    data object PremiumRequired : SpotifyHealth
    data class RateLimited(val untilEpochMs: Long) : SpotifyHealth
    data class Unreachable(val message: String) : SpotifyHealth
}

data class PlaybackConfig(
    val handoverLeadMs: Long = 15_000,
    val idlePollMs: Long = 3_000,
    val nearEndPollMs: Long = 1_000,
    val nearEndWindowMs: Long = 20_000,
)

/**
 * Owns the request queue and hands its head to Spotify shortly before the current track ends. With an
 * empty queue or closed requests nothing is handed over, so Spotify keeps playing the streamer's own music.
 *
 * Every state change and every save happens under one [Mutex], and each change is saved right away. A failed
 * Spotify call never drops a request: it stays where it was and the next tick tries again.
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

    val queue: StateFlow<RequestQueue> = _queue.asStateFlow()
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** Newest first. */
    val history: StateFlow<List<PlayedEntry>> = _history.asStateFlow()
    val health: StateFlow<SpotifyHealth> = _health.asStateFlow()

    private val mutex = Mutex()

    // Guarded by [mutex], like every write to the state flows above.
    private var loaded = false
    private var handedOver: Request? = null // in Spotify's queue, not playing yet
    private var playing: Request? = null
    private var lastTrackId: String? = null
    private var lastSaved: PersistedPlayback? = null

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

    /** Stops polling. Every change has already been saved. */
    fun stop() {
        loop?.cancel()
        loop = null
    }

    fun enqueue(request: Request) {
        change { _queue.value = _queue.value.add(request, paidPriority()) }
    }

    fun remove(id: String) {
        change { _queue.value = _queue.value.remove(id) }
    }

    fun move(id: String, toIndex: Int) {
        change { _queue.value = _queue.value.move(id, toIndex) }
    }

    /** Hands the next request over first (when it may go), so it starts right away, then skips. */
    suspend fun skip() {
        mutex.withLock {
            ensureLoaded()
            handOverCandidate(_nowPlaying.value?.track?.id)?.let { handOver(it) }
            persistIfChanged()
            player.skipNext()
            wake.trySend(Unit)
        }
    }

    suspend fun togglePause() {
        mutex.withLock {
            val current = _nowPlaying.value
            val wasPlaying = current?.isPlaying == true
            val result = if (wasPlaying) player.pause() else player.resume()
            if (result is SpotifyResult.Ok && current != null) _nowPlaying.value = current.copy(isPlaying = !wasPlaying)
            wake.trySend(Unit)
        }
    }

    /**
     * Applies [update] under the lock and saves it. The coroutine starts undispatched, so calls take the lock
     * in call order, and the change shows at once when nothing else holds the lock.
     */
    private fun change(update: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                ensureLoaded()
                update()
                persistIfChanged()
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun tickSafely(): NextTick = mutex.withLock {
        try {
            tick()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // stopped: end the loop. A stray one from a callee: carry on.
            crashed(e)
        } catch (e: Exception) {
            crashed(e)
        }
    }

    private fun crashed(e: Exception): NextTick {
        _health.value = SpotifyHealth.Unreachable(e.message ?: e::class.simpleName ?: "error")
        return NextTick(config.idlePollMs)
    }

    private suspend fun tick(): NextTick {
        wake.tryReceive() // this tick already sees every change made before it took the lock
        ensureLoaded()
        val cp = when (val result = player.currentlyPlaying()) {
            is SpotifyResult.Ok -> result.value
            else -> return failed(result)
        }
        _health.value = if (cp == null) SpotifyHealth.NothingPlaying else SpotifyHealth.Ok
        val track = cp?.track
        if (track?.id != lastTrackId) {
            settleTrackChange(track?.id)?.let { return failed(it) }
        }
        if (cp == null || track == null) {
            _nowPlaying.value = null
            persistIfChanged()
            return NextTick(config.idlePollMs)
        }
        _nowPlaying.value = NowPlaying(track, cp.progressMs, cp.isPlaying, playing?.takeIf { it.track.id == track.id })
        val remainingMs = track.durationMs - cp.progressMs
        val handOverResult =
            if (remainingMs <= config.handoverLeadMs) handOverCandidate(track.id)?.let { handOver(it) } else null
        persistIfChanged()
        return when {
            handOverResult is SpotifyResult.RateLimited -> NextTick(handOverResult.retryAfterMs, wakeable = false)
            remainingMs <= config.nearEndWindowMs -> NextTick(config.nearEndPollMs)
            else -> NextTick(config.idlePollMs)
        }
    }

    /**
     * Spotify moved on to [trackId]: the playing request is done, and the handed-over one either plays now or,
     * when a track ended that wasn't ours and Spotify's queue no longer has it, goes back to the head.
     * Returns the failed result when Spotify's queue could not be read; nothing changes then, so the next
     * tick settles the change again.
     */
    private suspend fun settleTrackChange(trackId: String?): SpotifyResult<*>? {
        val sent = handedOver
        val spotifyQueue = if (sent != null && sent.track.id != trackId && lastTrackId != null) {
            when (val result = player.userQueueTrackIds()) {
                is SpotifyResult.Ok -> result.value
                else -> return result
            }
        } else {
            null
        }
        playing?.let {
            if (it.track.id != trackId) {
                _history.value = listOf(PlayedEntry(it, clock())) + _history.value
                playing = null
            }
        }
        if (sent != null && sent.track.id == trackId) {
            playing = sent
            handedOver = null
        } else if (sent != null && spotifyQueue != null && sent.track.id !in spotifyQueue) {
            _queue.value = _queue.value.addFirst(sent)
            handedOver = null
        }
        lastTrackId = trackId
        return null
    }

    /**
     * The head may go to Spotify when requests are open, nothing is waiting there yet, and it is not the track
     * playing now: Spotify would report that repeat under the same id, so its start could never be seen.
     */
    private fun handOverCandidate(currentTrackId: String?): Request? =
        _queue.value.head?.takeIf { requestsOpen.value && handedOver == null && it.track.id != currentTrackId }

    /** On failure the request simply stays the head. */
    private suspend fun handOver(head: Request): SpotifyResult<Unit> {
        val result = player.addToQueue(head.track.uri)
        if (result is SpotifyResult.Ok) {
            handedOver = head
            _queue.value = _queue.value.remove(head.id)
        } else {
            _health.value = healthOf(result)
        }
        return result
    }

    private fun failed(result: SpotifyResult<*>): NextTick {
        _health.value = healthOf(result)
        return if (result is SpotifyResult.RateLimited) {
            NextTick(result.retryAfterMs, wakeable = false)
        } else {
            NextTick(config.idlePollMs)
        }
    }

    private fun healthOf(result: SpotifyResult<*>): SpotifyHealth = when (result) {
        is SpotifyResult.Ok -> SpotifyHealth.Ok
        SpotifyResult.NoActiveDevice -> SpotifyHealth.NoActiveDevice
        SpotifyResult.PremiumRequired -> SpotifyHealth.PremiumRequired
        SpotifyResult.LoginNeeded -> SpotifyHealth.LoginNeeded
        is SpotifyResult.RateLimited -> SpotifyHealth.RateLimited(clock() + result.retryAfterMs)
        is SpotifyResult.Failed -> SpotifyHealth.Unreachable(result.message)
    }

    /** Loads the saved state before the first change or tick, so nothing written earlier is overwritten. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        val saved = withContext(ioDispatcher) { store.load() }
        _queue.value = saved.queue
        handedOver = saved.handedOver
        playing = saved.playing
        _history.value = saved.history
        lastSaved = saved
        loaded = true
    }

    /** A failed save is not lost: the state stays unsaved and the next change or tick writes it again. */
    private suspend fun persistIfChanged() {
        val snapshot = PersistedPlayback(_queue.value, handedOver, playing, _history.value)
        if (snapshot == lastSaved) return
        try {
            withContext(ioDispatcher + NonCancellable) { store.save(snapshot) }
            lastSaved = snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** When the loop polls again; a rate-limit wait is not cut short by wake-ups. */
    private class NextTick(val afterMs: Long, val wakeable: Boolean = true)
}
