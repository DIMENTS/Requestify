package nl.diments.requestify.ui.home

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.intake.RejectedStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlaying
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.ShortLinkTarget
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyInput
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.classifySpotifyInput

/** Why the add field shows no results. */
sealed interface AddMessage {
    data object NoResults : AddMessage
    data class NotATrack(val kind: String) : AddMessage
    data object SpotifyUnavailable : AddMessage
}

/**
 * Everything the Home screen shows. [handedOver] is the request Spotify has queued but not started yet (it is
 * not in [queue]); [upcoming] is what Spotify itself plays after the requests, without [handedOver];
 * [queueDurationMs] counts the requests only. [saveFailing] is true while the queue could not be saved,
 * [loadFailing] while the saved queue could not be read yet (it is retried). While [lookingUp], [results] still
 * answer the previous input and must not be acted on.
 */
data class HomeUiState(
    val queue: List<Request>,
    val queueDurationMs: Long,
    val history: List<PlayedEntry>,
    val nowPlaying: NowPlaying?,
    val health: SpotifyHealth,
    val results: List<Track>,
    val addMessage: AddMessage?,
    val handedOver: Request? = null,
    val saveFailing: Boolean = false,
    val lookingUp: Boolean = false,
    val loadFailing: Boolean = false,
    val upcoming: List<Track> = emptyList(),
    /** The Rejected tab, newest first, and [rejectedBadge]: how many of them are paid. */
    val rejected: List<RejectedEntry> = emptyList(),
    val rejectedBadge: Int = 0,
    /** The last block could not be saved to the blacklist; it clears with the next block that is saved. */
    val blockFailing: Boolean = false,
)

/** Runs on the caller's [scope] (an app-lifetime scope); it starts nothing that outlives it. */
class HomeViewModel(
    private val controller: PlaybackController,
    private val catalog: SpotifyCatalog,
    settings: SettingsStore,
    private val intake: RequestIntake,
    private val rejected: RejectedStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** [results] and [message] answer [input]; while [lookingUp], the results are the previous input's. */
    private class AddState(
        val input: String = "",
        val results: List<Track> = emptyList(),
        val message: AddMessage? = null,
        val lookingUp: Boolean = false,
    )

    private class Playback(
        val queue: List<Request>,
        val queueDurationMs: Long,
        val handedOver: Request?,
        val upcoming: List<Track>,
        val history: List<PlayedEntry>,
        val nowPlaying: NowPlaying?,
    )

    private class Storage(val persistError: String?, val loadError: String?, val blockFailed: Boolean)

    private class Rejections(val entries: List<RejectedEntry>, val paidCount: Int)

    private val add = MutableStateFlow(AddState())
    private val blockFailed = MutableStateFlow(false)
    private var lookup: Job? = null

    val settings: StateFlow<Settings> = settings.settings

    val state: StateFlow<HomeUiState> = combine(
        combine(
            controller.queue,
            controller.handedOver,
            controller.upcoming,
            controller.history,
            controller.nowPlaying,
            ::playback,
        ),
        controller.health,
        combine(controller.persistError, controller.loadError, blockFailed, ::Storage),
        combine(rejected.entries, rejected.paidCount, ::Rejections),
        add,
        ::uiState,
    ).stateIn(scope, SharingStarted.Eagerly, initialState())

    /**
     * A track link is looked up at once, search text after a [SEARCH_DEBOUNCE_MS] pause in typing. Until the
     * answer arrives the previous results stay on screen, marked as stale ([HomeUiState.lookingUp]).
     */
    fun onAddInput(text: String) {
        lookup?.cancel()
        when (val input = classifySpotifyInput(text)) {
            SpotifyInput.Empty -> add.value = AddState()
            is SpotifyInput.NotATrack -> add.value = AddState(text, message = AddMessage.NotATrack(input.kind))
            is SpotifyInput.ArtistId -> add.value = AddState(text, message = AddMessage.NotATrack("artist"))
            is SpotifyInput.TrackId -> lookUp(text) { catalog.track(input.id).valueOrNull()?.let(::listOf) }
            // A short link is followed first; one that leads to no track finds nothing.
            is SpotifyInput.ShortLink -> lookUp(text) {
                when (val target = intake.followShortLink(input)) {
                    is ShortLinkTarget.Link -> (target.input as? SpotifyInput.TrackId)
                        ?.let { catalog.track(it.id).valueOrNull()?.let(::listOf) }
                        ?: emptyList()
                    ShortLinkTarget.Unresolvable -> emptyList()
                    ShortLinkTarget.Unreachable -> null
                }
            }
            is SpotifyInput.SearchText -> lookUp(text) {
                delay(SEARCH_DEBOUNCE_MS)
                catalog.searchTracks(input.query, SEARCH_LIMIT).valueOrNull()
            }
        }
    }

    /**
     * Enter in the add field: queues the first result, but only when the results answer exactly [input] (so
     * never a previous search's while the next one is still pending). Returns whether a track was queued.
     */
    fun onAddSubmit(input: String): Boolean {
        val current = add.value
        val first = current.results.firstOrNull()
        if (input.isBlank() || current.lookingUp || current.input != input || first == null) return false
        onAddTrack(first)
        return true
    }

    /** Queues [track] as the streamer's own free request, at the end, and clears the results. */
    fun onAddTrack(track: Track) {
        lookup?.cancel()
        add.value = AddState()
        controller.enqueue(
            Request(
                id = newId(),
                track = track,
                requester = Requester(""),
                source = RequestSource.MANUAL,
                payment = null,
                createdAtEpochMs = clock(),
            ),
        )
    }

    fun onRemove(id: String) = controller.remove(id)

    fun onMove(id: String, toIndex: Int) = controller.move(id, toIndex)

    /** "Add anyway" on a rejected request: it is queued past the rules and leaves the Rejected list. */
    fun onAddAnyway(id: String) = intake.addAnyway(id)

    /** Takes a rejected request off the list. */

    fun onDismiss(id: String) = intake.dismiss(id)
    /** Blocks [target] for good: the queue is swept of what it now matches. Nothing is skipped. */
    fun onBlock(target: BlockTarget) {
        scope.launch { block(target) }
    }

    /**
     * Now Playing's block menu: blocks [target], and skips the song when it is the song or its artist that was
     * blocked. Blocking a viewer leaves what is playing alone. A block that could not be saved skips nothing.
     */
    fun onBlockCurrentAndSkip(target: BlockTarget) {
        scope.launch {
            if (block(target) && target !is BlockTarget.Viewer) controller.skip()
        }
    }

    /**
     * The streamer blocks: no moderator is named. A viewer without a login is blocked under their name. Returns
     * false when the blacklist could not be written (then [HomeUiState.blockFailing] is set until a block succeeds);
     * blocking what is blocked already counts as done.
     */
    private suspend fun block(target: BlockTarget): Boolean {
        val saved = when (target) {
            is BlockTarget.Song -> intake.blockTrack(target.track, by = null)
            is BlockTarget.Artist -> {
                val id = target.track.artistIds.firstOrNull()
                val name = target.track.artists.firstOrNull().orEmpty()
                if (id != null || name.isNotBlank()) intake.blockArtist(id, name, by = null) else true
            }
            is BlockTarget.Viewer -> target.requester.twitchUserId?.let { userId ->
                intake.blockViewer(userId, target.requester.login ?: target.requester.displayName, by = null)
            } ?: true
        }
        blockFailed.value = !saved
        return saved
    }

    fun onPrevious() {
        scope.launch { controller.previous() }
    }

    fun onSkip() {
        scope.launch { controller.skip() }
    }

    fun onTogglePause() {
        scope.launch { controller.togglePause() }
    }

    /** Looks up the results for [input]; [find] returns null when Spotify could not answer. */
    private fun lookUp(input: String, find: suspend () -> List<Track>?) {
        add.value = AddState(input, results = add.value.results, lookingUp = true)
        lookup = scope.launch {
            val found = find()
            ensureActive() // newer input replaced this lookup while it ran
            add.value = when {
                found == null -> AddState(input, message = AddMessage.SpotifyUnavailable)
                found.isEmpty() -> AddState(input, message = AddMessage.NoResults)
                else -> AddState(input, results = found)
            }
        }
    }

    private fun <T> SpotifyResult<T>.valueOrNull(): T? = (this as? SpotifyResult.Ok)?.value

    private fun initialState() = uiState(
        playback(
            controller.queue.value,
            controller.handedOver.value,
            controller.upcoming.value,
            controller.history.value,
            controller.nowPlaying.value,
        ),
        controller.health.value,
        Storage(controller.persistError.value, controller.loadError.value, blockFailed.value),
        Rejections(rejected.entries.value, rejected.paidCount.value),
        add.value,
    )

    private fun playback(
        queue: RequestQueue,
        handedOver: Request?,
        upcoming: List<Track>,
        history: List<PlayedEntry>,
        nowPlaying: NowPlaying?,
    ) = Playback(queue.items, queue.totalDurationMs, handedOver, upcoming, history, nowPlaying)

    private fun uiState(
        playback: Playback,
        health: SpotifyHealth,
        storage: Storage,
        rejections: Rejections,
        add: AddState,
    ) = HomeUiState(
        queue = playback.queue,
        queueDurationMs = playback.queueDurationMs,
        history = playback.history,
        nowPlaying = playback.nowPlaying,
        health = health,
        results = add.results,
        addMessage = add.message,
        handedOver = playback.handedOver,
        saveFailing = storage.persistError != null,
        lookingUp = add.lookingUp,
        loadFailing = storage.loadError != null,
        blockFailing = storage.blockFailed,
        upcoming = playback.upcoming,
        rejected = rejections.entries,
        rejectedBadge = rejections.paidCount,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SEARCH_LIMIT = 5
    }
}

/** What a block menu blocks: a song, a song's (first) artist, or the viewer who asked for it. */
sealed interface BlockTarget {
    data class Song(val track: Track) : BlockTarget
    data class Artist(val track: Track) : BlockTarget
    data class Viewer(val requester: Requester) : BlockTarget
}
