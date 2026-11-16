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
 * not in [queue]); [saveFailing] is true while the queue could not be saved.
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
)

/** Runs on the caller's [scope] (an app-lifetime scope); it starts nothing that outlives it. */
class HomeViewModel(
    private val controller: PlaybackController,
    private val catalog: SpotifyCatalog,
    settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private class AddState(val results: List<Track> = emptyList(), val message: AddMessage? = null)

    private class Playback(
        val queue: List<Request>,
        val queueDurationMs: Long,
        val handedOver: Request?,
        val history: List<PlayedEntry>,
        val nowPlaying: NowPlaying?,
    )

    private val add = MutableStateFlow(AddState())
    private var lookup: Job? = null

    val settings: StateFlow<Settings> = settings.settings

    val state: StateFlow<HomeUiState> = combine(
        combine(controller.queue, controller.handedOver, controller.history, controller.nowPlaying, ::playback),
        controller.health,
        controller.persistError,
        add,
        ::uiState,
    ).stateIn(scope, SharingStarted.Eagerly, initialState())

    /** A track link is looked up at once, search text after a [SEARCH_DEBOUNCE_MS] pause in typing. */
    fun onAddInput(text: String) {
        lookup?.cancel()
        when (val input = classifySpotifyInput(text)) {
            SpotifyInput.Empty -> add.value = AddState()
            is SpotifyInput.NotATrack -> add.value = AddState(message = AddMessage.NotATrack(input.kind))
            is SpotifyInput.TrackId -> lookUp { catalog.track(input.id).valueOrNull()?.let(::listOf) }
            is SpotifyInput.SearchText -> lookUp {
                delay(SEARCH_DEBOUNCE_MS)
                catalog.searchTracks(input.query, SEARCH_LIMIT).valueOrNull()
            }
        }
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

    fun onSkip() {
        scope.launch { controller.skip() }
    }

    fun onTogglePause() {
        scope.launch { controller.togglePause() }
    }

    /** [find] returns null when Spotify could not answer. */
    private fun lookUp(find: suspend () -> List<Track>?) {
        lookup = scope.launch {
            val found = find()
            ensureActive() // newer input replaced this lookup while it ran
            add.value = when {
                found == null -> AddState(message = AddMessage.SpotifyUnavailable)
                found.isEmpty() -> AddState(message = AddMessage.NoResults)
                else -> AddState(found)
            }
        }
    }

    private fun <T> SpotifyResult<T>.valueOrNull(): T? = (this as? SpotifyResult.Ok)?.value

    private fun initialState() = uiState(
        playback(controller.queue.value, controller.handedOver.value, controller.history.value, controller.nowPlaying.value),
        controller.health.value,
        controller.persistError.value,
        add.value,
    )

    private fun playback(queue: RequestQueue, handedOver: Request?, history: List<PlayedEntry>, nowPlaying: NowPlaying?) =
        Playback(queue.items, queue.totalDurationMs, handedOver, history, nowPlaying)

    private fun uiState(playback: Playback, health: SpotifyHealth, persistError: String?, add: AddState) = HomeUiState(
        queue = playback.queue,
        queueDurationMs = playback.queueDurationMs,
        history = playback.history,
        nowPlaying = playback.nowPlaying,
        health = health,
        results = add.results,
        addMessage = add.message,
        handedOver = playback.handedOver,
        saveFailing = persistError != null,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SEARCH_LIMIT = 5
    }
}
