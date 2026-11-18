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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.spotify.RecentTrack
import nl.diments.requestify.core.spotify.SpotifyPlayer
import nl.diments.requestify.core.spotify.SpotifyResult

/** How many of Spotify's own upcoming tracks [PlaybackController.upcoming] holds at most. */
const val UPCOMING_LIMIT = 15

enum class NowPlayingKind {
    /** What Spotify reports playing (or paused) right now. */
    Live,

    /** Spotify has nothing active: the last song played, shown paused. */
    LastPlayed,
}

/**
 * What Spotify plays right now, or with [kind] [NowPlayingKind.LastPlayed] the last song it played. [request] is
 * null for the streamer's own music. With [requesterKnown] false (Spotify's own recently played song) it is not
 * known whether the song was a request, so [request] is null and neither a requester nor own music is claimed.
 * [progressMs] is the position at [sampledAtEpochMs] (wall time); [positionAt] tells where it is later.
 */
data class NowPlaying(
    val track: Track,
    val progressMs: Long,
    val isPlaying: Boolean,
    val request: Request?,
    val sampledAtEpochMs: Long,
    val kind: NowPlayingKind = NowPlayingKind.Live,
    val requesterKnown: Boolean = true,
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
 * [nearEndPollMs]: how often Spotify is polled while a song plays its last [nearEndWindowMs] (not while it is
 * paused). [playIntentSettleMs]: how long a pause or resume is shown while Spotify still reports the state from
 * before it (its Web API takes a moment to catch up). [upcomingRefreshMs]: how often Spotify's queue is read again
 * when nothing else asked for it, and how often the saved position of the song playing is brought up to date.
 * [recentlyPlayedRefreshMs]: how often Spotify's recently played song is asked for while nothing is active.
 */
data class PlaybackConfig(
    val handoverLeadMs: Long = 15_000,
    val idlePollMs: Long = 3_000,
    val nearEndPollMs: Long = 1_000,
    val nearEndWindowMs: Long = 20_000,
    val playIntentSettleMs: Long = 4_000,
    val upcomingRefreshMs: Long = 30_000,
    val recentlyPlayedRefreshMs: Long = 60_000,
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
 * edits wait in order and are applied on top of it once a later tick or edit reads it; [submit] and
 * [removeMatching] wait for it themselves, as they answer with what they did.
 *
 * Besides the polls, Spotify's queue ([upcoming]) and its recently played songs are read only when something
 * changed or a while has passed, never on every poll. None of these reads ever changes [health], but when Spotify
 * asks to wait after one, the next poll waits as it does after any other call.
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
    private val _upcoming = MutableStateFlow<List<Track>>(emptyList())

    /** The last played song and Spotify's recently played one. Guarded by [stateMutex]. */
    private val lastPlayedBook = LastPlayedBook(config, clock)

    /** When Spotify's queue is read, and what of it is shown. Guarded by [spotifyMutex]. */
    private val upcomingBook = UpcomingBook(config, clock)

    val queue: StateFlow<RequestQueue> = _queue.asStateFlow()

    /**
     * What Spotify plays now. While it has nothing active (and before its first answer), the newer of the saved
     * last played song and Spotify's own recently played one (the saved one when both are the same song), as
     * [NowPlayingKind.LastPlayed].
     */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The request in Spotify's queue that has not started yet: it is in neither [queue] nor [nowPlaying]. */
    val handedOver: StateFlow<Request?> = _handedOver.asStateFlow()

    /**
     * What Spotify itself plays after [handedOver] (which is left out), each track once, at most [UPCOMING_LIMIT].
     * Empty while nothing is active. A failed read keeps the last list.
     */
    val upcoming: StateFlow<List<Track>> = _upcoming.asStateFlow()

    /**
     * The login lacks the permission to read Spotify's recently played songs: only the saved last played song is
     * shown until the streamer logs in again ([onLoginChanged]).
     */
    val needsExtraPermission: StateFlow<Boolean> = lastPlayedBook.needsExtraPermission

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

    private val _events = MutableSharedFlow<RequestEvent>(extraBufferCapacity = Channel.UNLIMITED)

    /**
     * Requests that started playing or were removed from the queue, in order, each once its change is saved: while
     * saving fails they wait for the save that succeeds, so nothing acts on a change a restart would undo. Without
     * replay: a collector sees what is emitted after it subscribed, and a slow one misses nothing.
     */
    val events: SharedFlow<RequestEvent> = _events.asSharedFlow()

    private val spotifyMutex = Mutex()
    private val stateMutex = Mutex()

    // Guarded by [stateMutex], like every write to the state flows above. Only holders of [spotifyMutex]
    // change [handedOver], [playing] and [lastTrackId] (after the first load), so they stay put during a sequence.
    private var loaded = false
    private var playing: Request? = null
    private var lastTrackId: String? = null
    private var lastSaved: PersistedPlayback? = null

    /** When [handedOver] went to Spotify (wall time); set and cleared with it, and saved with it. */
    private var handedOverAt: Long? = null

    /** The pause or resume Spotify has not confirmed yet. Guarded by [stateMutex]; never saved. */
    private var playIntent: PlayIntent? = null

    /**
     * A command was refused for lack of an active device, and since then Spotify has neither played nor taken a
     * command. Guarded by [stateMutex]; never saved.
     */
    private var commandFoundNoDevice = false

    /** Edits made before the saved state could be read, in order. Guarded by [stateMutex]. */
    private val waitingEdits = mutableListOf<() -> Unit>()

    /** Events of changes not saved yet, in order; [persistAndEmit] sends them. Guarded by [stateMutex]. */
    private val unsentEvents = mutableListOf<RequestEvent>()

    /**
     * The head being handed over: from the state section that picks it until the one that applies Spotify's answer
     * ([handOver]). It is still queued, but for removals it counts as handed over already: [removeMatching] does not
     * offer it to its pick, and a [remove] of it is only remembered. When Spotify took it, it is handed over and the
     * removal is dropped (nothing can be pulled back from Spotify's queue), without [RequestEvent.Removed]; when
     * Spotify refused, it stays queued, or is removed now when a removal was remembered. So a request is never both
     * removed and started. Only holders of [spotifyMutex] set it. Guarded by [stateMutex].
     */
    private var handingOver: HandingOver? = null

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
        change { if (!isKnown(request.id)) _queue.value = _queue.value.add(request, paidPriority()) }
    }

    /**
     * Returns once the state as of this call is saved in queue.json: while saving fails ([persistError]) it tries again
     * every [SAVE_RETRY_MS]. A source that marks something as handled (a donation cursor) waits for this first. Before
     * the saved state is loaded nothing of this run can need saving, so it returns at once.
     */
    suspend fun awaitSaved() {
        while (true) {
            val saved = stateMutex.withLock {
                if (!loaded) return@withLock true
                persistAndEmit()
                _persistError.value == null
            }
            if (saved) return
            delay(SAVE_RETRY_MS)
        }
    }

    /**
     * Adds [request] when [decide], given the state as it is now, returns no reason against it. Deciding and adding
     * are one step under [stateMutex], so two requests at once can never both pass a limit. A request whose id is
     * already queued, handed over or playing is not added again, nor decided on: it is accepted where it is. Waits
     * until the saved state is loaded.
     *
     * A caller cancelled while the request is being added and saved can get a [CancellationException] although the
     * request was added and saved: once [decide] accepted it, the addition stands.
     */
    suspend fun submit(request: Request, decide: (PlaybackSnapshot) -> RejectReason?): SubmitOutcome {
        awaitLoaded()
        return withState {
            if (!isKnown(request.id)) {
                decide(stateSnapshot())?.let { return@withState SubmitOutcome.Rejected(it) }
                _queue.value = _queue.value.add(request, paidPriority())
                wake.trySend(Unit) // under the lock, like [syncLocked]
            }
            whereIs(request.id)
        }
    }

    /**
     * Takes the request with [id] out of the queue; one handed over or playing stays. [RequestEvent.Removed] tells
     * [why]. While it is being handed over ([handingOver]) the removal waits for Spotify's answer: when Spotify took
     * it, it stays handed over and nothing is removed.
     */
    fun remove(id: String, why: RemoveReason = RemoveReason.Streamer) {
        change {
            val sending = handingOver
            if (sending != null && sending.request.id == id) {
                if (sending.removal == null) sending.removal = why // the first removal asked for counts
            } else {
                removeQueued(id, why)
            }
        }
    }

    /**
     * Takes the queued requests that [pick] chooses from the queue out of it in one step, and returns them in queue
     * order. Picks that are not queued (handed over, playing or unknown) are left alone, and the request being
     * handed over ([handingOver]) is not offered to [pick]: it counts as handed over. Each removed request gets a
     * [RequestEvent.Removed] for [why]. Waits until the saved state is loaded.
     */
    suspend fun removeMatching(why: RemoveReason, pick: (List<Request>) -> List<Request>): List<Request> {
        awaitLoaded()
        return withState {
            val offered = _queue.value.items.filter { it.id != handingOver?.request?.id }
            val picked = pick(offered).mapTo(HashSet()) { it.id }
            val removed = offered.filter { it.id in picked }
            if (removed.isNotEmpty()) {
                val removedIds = removed.mapTo(HashSet()) { it.id } // never the one being handed over
                _queue.value = RequestQueue(_queue.value.items.filterNot { it.id in removedIds })
                removed.forEach { unsentEvents += RequestEvent.Removed(it, why) }
                wake.trySend(Unit) // under the lock, like [syncLocked]
            }
            removed
        }
    }

    /**
     * The state as shown, for `!song` and `!queue`, read without the lock. [PlaybackSnapshot.playing] is the request
     * of [nowPlaying], so it can lag a poll behind the request that plays, and while Spotify has nothing active it is
     * the request of the [NowPlayingKind.LastPlayed] card.
     */
    fun snapshot(): PlaybackSnapshot {
        val now = _nowPlaying.value
        return PlaybackSnapshot(_queue.value.items, _handedOver.value, now?.request, _history.value, now)
    }

    /**
     * The state as [submit]'s rules see it, once the saved state is loaded (it waits for that, and reads it when
     * nothing did yet): unlike [snapshot], [PlaybackSnapshot.playing] is the request playing now, also before the
     * first poll. For settling what happened to requests while nobody was listening.
     */
    suspend fun loadedSnapshot(): PlaybackSnapshot {
        awaitLoaded()
        return withState { stateSnapshot() }
    }

    fun move(id: String, toIndex: Int) {
        change { _queue.value = _queue.value.move(id, toIndex) }
    }

    /** Caller holds [stateMutex]. */
    private fun isKnown(id: String): Boolean =
        _queue.value.items.any { it.id == id } || _handedOver.value?.id == id || playing?.id == id

    /** Takes the queued request with [id] out, with a [RequestEvent.Removed] for [why]. Caller holds [stateMutex]. */
    private fun removeQueued(id: String, why: RemoveReason) {
        val request = _queue.value.items.firstOrNull { it.id == id } ?: return
        _queue.value = _queue.value.remove(id)
        unsentEvents += RequestEvent.Removed(request, why)
    }

    /** What [decide][submit] sees: the state with the request playing now. Caller holds [stateMutex]. */
    private fun stateSnapshot() =
        PlaybackSnapshot(_queue.value.items, _handedOver.value, playing, _history.value, _nowPlaying.value)

    /**
     * Where the known request with [id] is, and about how long until it plays: the rest of the track Spotify plays
     * now (none while only the last played song is shown), the handed-over one, then the queued ones before it.
     * Caller holds [stateMutex].
     */
    private fun whereIs(id: String): SubmitOutcome.Accepted {
        if (playing?.id == id) return SubmitOutcome.Accepted(0, 0)
        val live = _nowPlaying.value?.takeIf { it.kind == NowPlayingKind.Live }
        val restOfTrackMs = live?.let { it.track.durationMs - it.positionAt(clock()) } ?: 0
        if (_handedOver.value?.id == id) return SubmitOutcome.Accepted(0, restOfTrackMs)
        val index = _queue.value.items.indexOfFirst { it.id == id }
        check(index >= 0) { "request $id is not known" }
        val handedOverMs = _handedOver.value?.track?.durationMs ?: 0
        val queuedBeforeMs = _queue.value.items.take(index).sumOf { it.track.durationMs }
        return SubmitOutcome.Accepted(index + 1, restOfTrackMs + handedOverMs + queuedBeforeMs)
    }

    /**
     * Hands the next request over first (when it may go), so it starts right away, then skips. While the saved
     * queue cannot be read nothing is handed over, but the skip still happens. A refusal shows in [health].
     */
    suspend fun skip() {
        spotifyMutex.withLock { skipLocked() }
    }

    /**
     * [skip], but only while [trackId] is still the track playing (the last poll found it, and no tick can change that
     * while this holds [spotifyMutex]): a block that took a moment never skips the song after it. True when it skipped.
     */
    suspend fun skipIfPlaying(trackId: String): Boolean = spotifyMutex.withLock {
        if (liveTrackId() != trackId) return@withLock false
        skipLocked()
        true
    }

    /** Caller holds [spotifyMutex]. */
    private suspend fun skipLocked() {
        val head = if (syncState()) withState { pickForHandOver(liveTrackId()) } else null
        if (head != null) {
            val result = handOver(head)
            if (result !is SpotifyResult.Ok) withState { _health.value = healthOf(result) }
        }
        val skipped = player.skipNext()
        stateMutex.withLock { commandAnswered(skipped) }
        upcomingBook.markStale()
        wake.trySend(Unit)
    }

    /**
     * Spotify's own previous: a first press restarts the song when it is a few seconds in, a second one goes back.
     * A request that was playing goes to history as on any track change; nothing is queued again, and the
     * handed-over request stays next in Spotify's queue. A refusal shows in [health].
     */
    suspend fun previous() {
        spotifyMutex.withLock {
            val result = player.skipPrevious()
            stateMutex.withLock { commandAnswered(result) }
            upcomingBook.markStale()
            wake.trySend(Unit)
        }
    }

    /** The track the last poll found playing (or paused); null while the last played song is shown. */
    private fun liveTrackId(): String? = _nowPlaying.value?.takeIf { it.kind == NowPlayingKind.Live }?.track?.id

    /** The Spotify login changed: the permission is checked again by asking for the recently played song. */
    fun onLoginChanged() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateMutex.withLock { lastPlayedBook.onLoginChanged() }
        }
    }

    /**
     * Shows paused or playing at once, then tells Spotify. Each call flips what is shown, so quick clicks each
     * count, and their commands reach Spotify in click order. Polls keep showing it until Spotify reports it too
     * (it lags behind a command) or [PlaybackConfig.playIntentSettleMs] has passed. When Spotify refuses, the
     * state flips back and [health] says why. On the last played song it asks Spotify to resume.
     */
    suspend fun togglePause() {
        // Only [nowPlaying] and the intent change here, and neither is saved: no loaded state is needed, and the
        // change shows without waiting for a Spotify sequence in flight.
        val intent = stateMutex.withLock { showToggled() }
        spotifyMutex.withLock {
            // With nothing shown there is nothing to pause: ask Spotify to play.
            val result = if (intent != null && !intent.shown.isPlaying) player.pause() else player.resume()
            stateMutex.withLock {
                if (result !is SpotifyResult.Ok) refused(intent)
                commandAnswered(result)
            }
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
    private fun refused(intent: PlayIntent?) {
        if (intent != null && intent === playIntent) {
            playIntent = null
            val now = clock()
            _nowPlaying.value = _nowPlaying.value?.let {
                it.copy(progressMs = it.positionAt(now), isPlaying = !intent.shown.isPlaying, sampledAtEpochMs = now)
            }
        }
    }

    /**
     * Spotify answered a play, pause, skip or previous command; a refusal shows in [health]. With nothing active a
     * poll cannot tell that a device is missing, so after a refusal for that reason [health] keeps saying so until
     * something plays or a command is taken. Caller holds [stateMutex].
     */
    private fun commandAnswered(result: SpotifyResult<Unit>) {
        when (result) {
            is SpotifyResult.Ok -> commandFoundNoDevice = false
            SpotifyResult.NoActiveDevice -> commandFoundNoDevice = true
            else -> Unit
        }
        if (result !is SpotifyResult.Ok) _health.value = healthOf(result)
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

    /** Reads the saved state if needed, trying again every [PlaybackConfig.idlePollMs] until it can be read. */
    private suspend fun awaitLoaded() {
        while (!syncState()) delay(config.idlePollMs)
    }

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
        persistAndEmit()
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
            persistAndEmit()
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
        // The handed-over request left Spotify's queue: its recently played tells whether it played meanwhile.
        val playedSince = spotifyQueue
            ?.let { ids -> withState { playedCheckSince(ids) } }
            ?.let { since -> player.recentlyPlayedSince(since) }
        val recentAskedUnder = if (cp == null) withState { lastPlayedBook.recentlyPlayedDue() } else null
        val recent = recentAskedUnder?.let { player.recentlyPlayed() }
        val remainingMs = if (cp != null && track != null) track.durationMs - cp.progressMs else null
        val head = withState {
            wake.tryReceive()
            if (recentAskedUnder != null && recent != null) lastPlayedBook.answeredRecentlyPlayed(recent, recentAskedUnder)
            if (track?.id != lastTrackId) {
                settleTrackChange(track?.id, spotifyQueue, (playedSince as? SpotifyResult.Ok)?.value)
            }
            _nowPlaying.value = lastPlayedBook.show(cp, playing, _nowPlaying.value, ::withPlayIntent)
            if (remainingMs != null && remainingMs <= config.handoverLeadMs) pickForHandOver(track?.id) else null
        }
        val handOverResult = head?.let { handOver(it) }
        if (cp == null) upcomingBook.reset() // read again as soon as something is active
        // After the hand-over, so Spotify's queue already has it.
        val upcoming = if (upcomingBook.due(cp != null, track?.id, handOverResult)) {
            upcomingBook.markRead(track?.id)
            player.userQueue()
        } else {
            null
        }
        // Only while Spotify plays: a paused song gets no nearer its end.
        val nearEnd = cp?.isPlaying == true && remainingMs != null && remainingMs <= config.nearEndWindowMs
        return withState {
            if (cp == null) {
                _upcoming.value = emptyList()
            } else {
                commandFoundNoDevice = false
                if (upcoming is SpotifyResult.Ok) _upcoming.value = upcomingBook.from(upcoming.value, _handedOver.value)
            }
            _health.value = when {
                handOverResult != null && handOverResult !is SpotifyResult.Ok -> healthOf(handOverResult)
                cp == null && commandFoundNoDevice -> SpotifyHealth.NoActiveDevice
                cp == null -> SpotifyHealth.NothingPlaying
                else -> SpotifyHealth.Ok
            }
            val pollMs = when {
                playIntent != null -> config.nearEndPollMs // soon picks up Spotify's confirmation
                nearEnd -> config.nearEndPollMs
                else -> config.idlePollMs
            }
            // Spotify asked to wait, also when only an extra read was refused: no sooner than it said, nor than usual.
            val limited = listOf(handOverResult, upcoming, recent, playedSince)
                .firstNotNullOfOrNull { it as? SpotifyResult.RateLimited }
            if (limited != null) NextTick(maxOf(retryAfterMs(limited), pollMs), wakeable = false) else NextTick(pollMs)
        }
    }

    /**
     * What to show for a poll. Right after a pause or resume Spotify can still report the state from before it, so
     * while it reports the opposite of [playIntent] for the same track, and the intent has not expired, the intent
     * is shown: a pause stays frozen where it was, a resume runs on from when it was clicked. A resume of the last
     * played song is shown too while Spotify still reports nothing active. Otherwise the intent ends and Spotify
     * is trusted again. Caller holds [stateMutex].
     */
    private fun withPlayIntent(polled: NowPlaying?): NowPlaying? {
        val intent = playIntent ?: return polled
        val shown = intent.shown
        val settling = clock() < intent.untilEpochMs
        if (polled == null && shown.kind == NowPlayingKind.LastPlayed && settling) return shown
        if (polled == null || polled.track.id != shown.track.id || polled.isPlaying == shown.isPlaying || !settling) {
            playIntent = null
            return polled
        }
        return if (shown.isPlaying) {
            polled.copy(isPlaying = true, sampledAtEpochMs = shown.sampledAtEpochMs)
        } else {
            polled.copy(isPlaying = false, progressMs = shown.progressMs, sampledAtEpochMs = shown.sampledAtEpochMs)
        }
    }

    /**
     * Another track than the handed-over one plays (or none), first seen now, also on the first poll after startup:
     * only Spotify's queue can tell whether it still has the handed-over one.
     */
    private fun needsSpotifyQueueCheck(trackId: String?): Boolean {
        val sent = _handedOver.value ?: return false
        return trackId != lastTrackId && sent.track.id != trackId
    }

    /**
     * When to ask Spotify's recently played from: when [handedOver] went to Spotify, now that [spotifyQueue] no
     * longer has it. Null while it is still there, or when that time is not known (saved before it was kept).
     */
    private fun playedCheckSince(spotifyQueue: List<String>): Long? {
        val sent = _handedOver.value ?: return null
        return handedOverAt?.takeIf { sent.track.id !in spotifyQueue }
    }

    /**
     * Spotify moved on to [trackId]: the playing request is done, and the handed-over one either plays now
     * ([RequestEvent.Started]) or [spotifyQueue] no longer has it. Then, when it is in [playedSince] (Spotify's
     * recently played since its hand-over), it played while nobody watched, e.g. while the app was closed: it gets
     * its [RequestEvent.Started] and goes to history. Otherwise (also when that read failed, [playedSince] null) it
     * goes back to the head. A failed queue check never gets here, so [lastTrackId] stays put and the next tick
     * settles the change again.
     */
    private fun settleTrackChange(trackId: String?, spotifyQueue: List<String>?, playedSince: List<RecentTrack>?) {
        playing?.let {
            if (it.track.id != trackId) {
                addToHistory(it)
                playing = null
            }
        }
        val sent = _handedOver.value
        if (sent != null && sent.track.id == trackId) {
            playing = sent
            clearHandedOver()
            unsentEvents += RequestEvent.Started(sent)
        } else if (sent != null && spotifyQueue != null && sent.track.id !in spotifyQueue) {
            if (playedSince.orEmpty().any { it.track.id == sent.track.id }) {
                unsentEvents += RequestEvent.Started(sent)
                addToHistory(sent) // after the playing one, which ended before it started
            } else {
                _queue.value = _queue.value.addFirst(sent)
            }
            clearHandedOver()
        }
        lastTrackId = trackId
    }

    /** [request] played; it is the newest in [history]. Caller holds [stateMutex]. */
    private fun addToHistory(request: Request) {
        _history.value = (listOf(PlayedEntry(request, clock())) + _history.value).take(QueueStore.MAX_HISTORY)
    }

    /** Caller holds [stateMutex]. */
    private fun clearHandedOver() {
        _handedOver.value = null
        handedOverAt = null
    }

    /**
     * The head may go to Spotify when requests are open, nothing is waiting there yet, and it is not the track
     * playing now: Spotify would report that repeat under the same id, so its start could never be seen.
     */
    private fun handOverCandidate(currentTrackId: String?): Request? =
        _queue.value.head?.takeIf { requestsOpen.value && _handedOver.value == null && it.track.id != currentTrackId }

    /** [handOverCandidate], which is [handingOver] from now on. Caller holds [spotifyMutex] and [stateMutex]. */
    private fun pickForHandOver(currentTrackId: String?): Request? =
        handOverCandidate(currentTrackId)?.also { handingOver = HandingOver(it) }

    /**
     * Sends [head], just picked by [pickForHandOver], to Spotify's queue, and applies the answer at once in a state
     * section of its own ([handOverAnswered]): when Spotify has it, that is saved at once, so it does not wait for
     * (nor is lost with) a later call. The answer is applied even when the call throws or the sequence is cancelled
     * (as refused then), so [handingOver] always ends. Caller holds [spotifyMutex].
     */
    private suspend fun handOver(head: Request): SpotifyResult<Unit> {
        var result: SpotifyResult<Unit>? = null
        try {
            result = player.addToQueue(head.track.uri)
            return result
        } finally {
            withContext(NonCancellable) { withState { handOverAnswered(added = result is SpotifyResult.Ok) } }
        }
    }

    /**
     * Ends [handingOver]: when [added], the request is handed over and a removal asked for meanwhile is dropped;
     * otherwise it stays queued, unless a removal was asked for meanwhile: then it is removed now. Caller holds
     * [stateMutex].
     */
    private fun handOverAnswered(added: Boolean) {
        val sending = handingOver ?: return
        handingOver = null
        if (added) markHandedOver(sending.request) else sending.removal?.let { removeQueued(sending.request.id, it) }
    }

    /**
     * Spotify has [request] now, even if it was moved meanwhile (a removal waits for the answer, see
     * [handingOver]): take it out of the current queue. Caller holds [stateMutex].
     */
    private fun markHandedOver(request: Request) {
        _handedOver.value = request
        handedOverAt = clock()
        _queue.value = _queue.value.remove(request.id)
        unsentEvents += RequestEvent.HandedOver(request)
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
        SpotifyResult.NotFound -> SpotifyHealth.Unreachable("not found")
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
        handedOverAt = saved.handedOverAtEpochMs
        playing = saved.playing
        _history.value = saved.history
        lastPlayedBook.restore(saved)
        if (_nowPlaying.value == null) _nowPlaying.value = lastPlayedBook.lastPlayedCard(null) // at once, before Spotify answers
        lastSaved = saved
        loaded = true
        _loadError.value = null
        return true
    }

    /**
     * [persistIfChanged], then, once everything is saved, emits the [unsentEvents] in order. While saving fails they
     * wait for the save that succeeds. Caller holds [stateMutex].
     */
    private suspend fun persistAndEmit() {
        if (!persistIfChanged() || unsentEvents.isEmpty()) return
        val events = unsentEvents.toList()
        unsentEvents.clear()
        events.forEach { _events.tryEmit(it) } // never fails: the buffer is unlimited
    }

    /**
     * Saves when the state differs from the last successful save; a failure shows in [persistError]. True when
     * everything is saved.
     */
    private suspend fun persistIfChanged(): Boolean {
        val snapshot = PersistedPlayback(
            _queue.value,
            _handedOver.value,
            playing,
            _history.value,
            lastPlayedBook.lastPlayed,
            handedOverAtEpochMs = handedOverAt,
        )
        if (snapshot == lastSaved) {
            _persistError.value = null // e.g. an unsaved edit was undone: nothing is left unsaved
            return true
        }
        return try {
            withContext(ioDispatcher + NonCancellable) { store.save(snapshot) }
            lastSaved = snapshot
            _persistError.value = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _persistError.value = e.toString()
            false
        }
    }

    /** When the loop polls again; a rate-limit wait is not cut short by wake-ups. */
    private class NextTick(val afterMs: Long, val wakeable: Boolean = true)

    /** A pause or resume Spotify has not confirmed yet: [shown] is what it showed, until [untilEpochMs]. */
    private class PlayIntent(val shown: NowPlaying, val untilEpochMs: Long)

    /** [request] is being sent to Spotify's queue; [removal] is the first removal asked for meanwhile. */
    private class HandingOver(val request: Request) {
        var removal: RemoveReason? = null
    }
}

/** How often [PlaybackController.awaitSaved] tries queue.json again while it cannot be saved. */
private const val SAVE_RETRY_MS = 1_000L
