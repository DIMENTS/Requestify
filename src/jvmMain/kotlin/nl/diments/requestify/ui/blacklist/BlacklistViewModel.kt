package nl.diments.requestify.ui.blacklist

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
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.spotify.ArtistRef
import nl.diments.requestify.core.spotify.ShortLinkTarget
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyInput
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.classifySpotifyInput
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.TwitchUserRef

enum class BlacklistTab { Songs, Artists, Viewers }

/** What the add field offers to block. */
sealed interface BlockCandidate {
    data class Song(val track: Track) : BlockCandidate
    data class Artist(val artist: ArtistRef) : BlockCandidate
    data class Viewer(val user: TwitchUserRef) : BlockCandidate
}

/** Why the add field shows no results. */
sealed interface BlacklistMessage {
    data object NoResults : BlacklistMessage
    data object ViewerNotFound : BlacklistMessage
    data object SpotifyUnavailable : BlacklistMessage
    data object TwitchUnavailable : BlacklistMessage

    /** A Spotify link of another kind than the tab takes; [kind] is what the link is (album, track, ...). */
    data class WrongLink(val kind: String) : BlacklistMessage
}

/**
 * [results] and [message] answer [input]; while [lookingUp], the results are the previous input's and must not be
 * acted on. [twitchConnected] false: the Viewers tab cannot add. [blockFailing]: the last add or remove could not
 * be saved.
 */
data class BlacklistUiState(
    val tab: BlacklistTab = BlacklistTab.Songs,
    val blacklist: Blacklist = Blacklist(),
    val input: String = "",
    val results: List<BlockCandidate> = emptyList(),
    val message: BlacklistMessage? = null,
    val lookingUp: Boolean = false,
    val twitchConnected: Boolean = false,
    val blockFailing: Boolean = false,
)

/**
 * The Blacklist screen's state: one tab at a time, an add field that looks up what was typed for that tab (a
 * track or artist on Spotify, a viewer on Twitch), and removal. Adding goes through [intake] so the queue is
 * swept too. Runs on [scope], an app-lifetime scope.
 */
class BlacklistViewModel(
    private val store: BlacklistStore,
    private val intake: RequestIntake,
    private val catalog: SpotifyCatalog,
    private val lookupViewer: suspend (login: String) -> TwitchResult<TwitchUserRef?>,
    private val twitchConnected: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private class AddState(
        val input: String = "",
        val results: List<BlockCandidate> = emptyList(),
        val message: BlacklistMessage? = null,
        val lookingUp: Boolean = false,
    )

    private val tab = MutableStateFlow(BlacklistTab.Songs)
    private val add = MutableStateFlow(AddState())
    private val blockFailed = MutableStateFlow(false)
    private var lookup: Job? = null

    val state: StateFlow<BlacklistUiState> = combine(
        tab,
        store.blacklist,
        add,
        twitchConnected,
        blockFailed,
    ) { tab, list, add, connected, failed ->
        BlacklistUiState(tab, list, add.input, add.results, add.message, add.lookingUp, connected, failed)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        BlacklistUiState(blacklist = store.blacklist.value, twitchConnected = twitchConnected.value),
    )

    fun onTab(new: BlacklistTab) {
        if (new == tab.value) return
        lookup?.cancel()
        add.value = AddState()
        tab.value = new
    }

    /**
     * Looks up what was typed for the current tab: a link at once, text after a [SEARCH_DEBOUNCE_MS] pause in
     * typing. Until the answer arrives the previous results stay, marked as stale ([BlacklistUiState.lookingUp]).
     */
    fun onInput(text: String) {
        lookup?.cancel()
        when (tab.value) {
            BlacklistTab.Songs -> songs(text)
            BlacklistTab.Artists -> artists(text)
            BlacklistTab.Viewers -> viewers(text)
        }
    }

    /** Enter in the add field: blocks the first result, when the results answer exactly [input]. */
    fun onSubmit(input: String): Boolean {
        val current = add.value
        val first = current.results.firstOrNull()
        if (input.isBlank() || current.lookingUp || current.input != input || first == null) return false
        onAdd(first)
        return true
    }

    /** Blocks [candidate] as the streamer, sweeping the queue, and clears the field. */
    fun onAdd(candidate: BlockCandidate) {
        lookup?.cancel()
        add.value = AddState()
        scope.launch {
            blockFailed.value = !when (candidate) {
                is BlockCandidate.Song -> intake.blockTrack(candidate.track, by = null)
                is BlockCandidate.Artist -> intake.blockArtist(candidate.artist.id, candidate.artist.name, by = null)
                is BlockCandidate.Viewer -> intake.blockViewer(candidate.user.id, candidate.user.login, by = null)
            }
        }
    }

    /** Unblocks the entry with [key] on the current tab: a track id, an artist id (or name) or a Twitch user id. */
    fun onRemove(key: String) {
        blockFailed.value = !when (tab.value) {
            BlacklistTab.Songs -> store.removeTrack(key)
            BlacklistTab.Artists -> store.removeArtist(key)
            BlacklistTab.Viewers -> store.removeViewer(key)
        }
    }

    private fun songs(text: String) {
        when (val input = classifySpotifyInput(text)) {
            SpotifyInput.Empty -> add.value = AddState()
            is SpotifyInput.NotATrack -> wrongLink(text, input.kind)
            is SpotifyInput.ArtistId -> wrongLink(text, "artist")
            is SpotifyInput.TrackId -> lookUp(text, BlacklistMessage.SpotifyUnavailable) {
                catalog.track(input.id).valueOrNull()?.let { listOf(BlockCandidate.Song(it)) }
            }
            is SpotifyInput.ShortLink -> lookUpShortLink(text, input, wanted = "track") { target ->
                (target as? SpotifyInput.TrackId)?.let { catalog.track(it.id).valueOrNull()?.let { t -> listOf(BlockCandidate.Song(t)) } }
            }
            is SpotifyInput.SearchText -> lookUp(text, BlacklistMessage.SpotifyUnavailable) {
                delay(SEARCH_DEBOUNCE_MS)
                catalog.searchTracks(input.query, SEARCH_LIMIT).valueOrNull()?.map(BlockCandidate::Song)
            }
        }
    }

    private fun artists(text: String) {
        when (val input = classifySpotifyInput(text)) {
            SpotifyInput.Empty -> add.value = AddState()
            is SpotifyInput.NotATrack -> wrongLink(text, input.kind)
            is SpotifyInput.TrackId -> wrongLink(text, "track")
            is SpotifyInput.ArtistId -> lookUp(text, BlacklistMessage.SpotifyUnavailable) {
                catalog.artist(input.id).valueOrNull()?.let { listOf(BlockCandidate.Artist(it)) }
            }
            is SpotifyInput.ShortLink -> lookUpShortLink(text, input, wanted = "artist") { target ->
                (target as? SpotifyInput.ArtistId)?.let { catalog.artist(it.id).valueOrNull()?.let { a -> listOf(BlockCandidate.Artist(a)) } }
            }
            is SpotifyInput.SearchText -> lookUp(text, BlacklistMessage.SpotifyUnavailable) {
                delay(SEARCH_DEBOUNCE_MS)
                catalog.searchArtists(input.query, SEARCH_LIMIT).valueOrNull()?.map(BlockCandidate::Artist)
            }
        }
    }

    /** A Twitch name, with or without the @. Without Twitch nothing is looked up. */
    private fun viewers(text: String) {
        val login = text.trim().removePrefix("@").lowercase()
        when {
            !twitchConnected.value || login.isEmpty() -> add.value = AddState()
            !LOGIN.matches(login) -> add.value = AddState(text, message = BlacklistMessage.ViewerNotFound)
            else -> {
                add.value = AddState(text, results = add.value.results, lookingUp = true)
                lookup = scope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    val answer = lookupViewer(login)
                    ensureActive() // newer input replaced this lookup while it ran
                    add.value = when (answer) {
                        is TwitchResult.Ok -> answer.value?.let { AddState(text, results = listOf(BlockCandidate.Viewer(it))) }
                            ?: AddState(text, message = BlacklistMessage.ViewerNotFound)
                        TwitchResult.NotFound -> AddState(text, message = BlacklistMessage.ViewerNotFound)
                        else -> AddState(text, message = BlacklistMessage.TwitchUnavailable)
                    }
                }
            }
        }
    }

    /**
     * Follows a short link, then [find]s what it leads to. A link to another kind than [wanted] (an album where a song is
     * looked for) says so, like a full link does; one that cannot be followed now is "Spotify unavailable".
     */
    private fun lookUpShortLink(
        text: String,
        link: SpotifyInput.ShortLink,
        wanted: String,
        find: suspend (SpotifyInput) -> List<BlockCandidate>?,
    ) {
        add.value = AddState(text, results = add.value.results, lookingUp = true)
        lookup = scope.launch {
            val state = when (val target = intake.followShortLink(link)) {
                is ShortLinkTarget.Link -> {
                    val kind = when (val linked = target.input) {
                        is SpotifyInput.TrackId -> "track"
                        is SpotifyInput.ArtistId -> "artist"
                        is SpotifyInput.NotATrack -> linked.kind
                        else -> null
                    }
                    if (kind != null && kind != wanted) {
                        AddState(text, message = BlacklistMessage.WrongLink(kind))
                    } else {
                        val found = find(target.input)
                        when {
                            found == null -> AddState(text, message = BlacklistMessage.SpotifyUnavailable)
                            found.isEmpty() -> AddState(text, message = BlacklistMessage.NoResults)
                            else -> AddState(text, results = found)
                        }
                    }
                }
                ShortLinkTarget.Unresolvable -> AddState(text, message = BlacklistMessage.NoResults)
                ShortLinkTarget.Unreachable -> AddState(text, message = BlacklistMessage.SpotifyUnavailable)
            }
            ensureActive() // newer input replaced this lookup while it ran
            add.value = state
        }
    }

    private fun wrongLink(text: String, kind: String) {
        add.value = AddState(text, message = BlacklistMessage.WrongLink(kind))
    }

    /** [find] returns null when Spotify could not answer. */
    private fun lookUp(input: String, unavailable: BlacklistMessage, find: suspend () -> List<BlockCandidate>?) {
        add.value = AddState(input, results = add.value.results, lookingUp = true)
        lookup = scope.launch {
            val found = find()
            ensureActive() // newer input replaced this lookup while it ran
            add.value = when {
                found == null -> AddState(input, message = unavailable)
                found.isEmpty() -> AddState(input, message = BlacklistMessage.NoResults)
                else -> AddState(input, results = found)
            }
        }
    }

    private fun <T> SpotifyResult<T>.valueOrNull(): T? = (this as? SpotifyResult.Ok)?.value

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SEARCH_LIMIT = 5
        val LOGIN = Regex("[a-z0-9_]{1,25}")
    }
}
