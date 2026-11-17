package nl.diments.requestify.core.intake

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.model.ChannelPointsRef
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.model.isViewerRequest
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.playback.SubmitOutcome
import nl.diments.requestify.core.rules.RuleContext
import nl.diments.requestify.core.rules.RuleEngine
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyInput
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.classifySpotifyInput

/** What [RequestIntake.submit] did with a request. */
sealed interface IntakeOutcome {
    data class Accepted(val request: Request, val position: Int, val waitMs: Long) : IntakeOutcome

    /** [listed]: it is in the Rejected list (false for free requests of blocked viewers). */
    data class Rejected(val reason: RejectReason, val track: Track?, val listed: Boolean) : IntakeOutcome

    /** Link-only input without any Spotify link: not a request. */
    data object Ignored : IntakeOutcome

    /** Paid, Spotify unreachable: waits in pending.json. */
    data object Pending : IntakeOutcome
}

/**
 * Where every source hands its requests: resolves the track, applies the rules atomically through the controller,
 * records rejections, and keeps paid requests safe while Spotify cannot be reached (they wait in pending.json and
 * are retried, across restarts too).
 */
class RequestIntake(
    private val controller: PlaybackController,
    private val catalog: SpotifyCatalog,
    private val settings: SettingsStore,
    private val blacklist: BlacklistStore,
    private val rejected: RejectedStore,
    private val pending: PendingResolveStore,
    private val requestsOpen: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val retryDelaysMs: List<Long> = listOf(5_000, 15_000, 30_000, 60_000), // then every 60 s
) {
    private val _pendingResolved =
        MutableSharedFlow<Pair<IncomingRequest, IntakeOutcome>>(extraBufferCapacity = Int.MAX_VALUE)

    /** A pending paid request settled (accepted or rejected): for its reply. */
    val pendingResolved: SharedFlow<Pair<IncomingRequest, IntakeOutcome>> = _pendingResolved.asSharedFlow()

    /** Viewer id to when their last request was accepted. In memory only: a cooldown paces a stream, not restarts. */
    private val lastAccepted = ConcurrentHashMap<String, Long>()
    private val retrying = ConcurrentHashMap.newKeySet<String>()

    /** Resumes the paid requests that were waiting for Spotify when the app last ran. */
    fun start() {
        pending.pending.value.forEach { retryLater(it) }
    }

    suspend fun submit(incoming: IncomingRequest): IntakeOutcome {
        val track = when (val resolved = resolve(incoming)) {
            is Resolved.Found -> resolved.track
            Resolved.Ignore -> return IntakeOutcome.Ignored
            is Resolved.Fail -> return reject(incoming, resolved.reason, null)
            Resolved.Unavailable -> {
                if (incoming.payment == null) return reject(incoming, RejectReason.SpotifyUnavailable, null)
                // Not kept in pending.json: say so, in the Rejected list, rather than lose a paid request.
                if (!pending.add(incoming)) return reject(incoming, RejectReason.SpotifyUnavailable, null)
                retryLater(incoming)
                return IntakeOutcome.Pending
            }
        }
        return process(incoming, track)
    }

    /** Adds a rejection to the Rejected list (not for a free request of a blocked viewer). */
    fun recordRejection(incoming: IncomingRequest, reason: RejectReason, track: Track? = null) {
        if (!isListed(incoming, reason)) return
        rejected.add(
            RejectedEntry(
                id = incoming.id,
                request = track?.let { buildRequest(incoming, it) },
                input = incoming.input,
                requester = incoming.requester,
                source = incoming.source,
                payment = incoming.payment,
                reason = reason,
                atEpochMs = clock(),
                refunded = isManagedChannelPoints(incoming.sourceRef),
            ),
        )
    }

    /** "Add anyway": queues the rejected request past the rules, then takes it off the list. */
    fun addAnyway(entryId: String) {
        val entry = rejected.entries.value.firstOrNull { it.id == entryId } ?: return
        val request = entry.request ?: return
        controller.enqueue(request.copy(rulesWaived = true))
        rejected.remove(entryId)
    }

    fun dismiss(entryId: String) {
        rejected.remove(entryId)
    }

    suspend fun blockTrack(track: Track, by: String?): Boolean {
        if (!blacklist.addTrack(track, by, clock())) return false
        sweep(RejectReason.TrackBlocked) { r -> blacklist.blacklist.value.blocksTrack(r.track) }
        return true
    }

    suspend fun blockArtist(id: String?, name: String, by: String?): Boolean {
        if (!blacklist.addArtist(id, name, by, clock())) return false
        sweep(RejectReason.ArtistBlocked) { r -> blacklist.blacklist.value.blocksArtistOf(r.track) }
        return true
    }

    suspend fun blockViewer(userId: String, login: String, by: String?): Boolean {
        if (!blacklist.addViewer(userId, login, by, clock())) return false
        sweep(RejectReason.ViewerBlocked) { r -> blacklist.blacklist.value.blocksViewer(r.requester) }
        return true
    }

    /** Moves the queued viewer requests that [matches] to the Rejected list. Own and "added anyway" ones stay. */
    private suspend fun sweep(reason: RejectReason, matches: (Request) -> Boolean) {
        val removed = controller.removeMatching(RemoveReason.Blacklisted) { queue ->
            queue.filter { it.isViewerRequest && matches(it) }
        }
        for (request in removed) {
            if (request.payment == null && reason == RejectReason.ViewerBlocked) continue // not listed
            rejected.add(
                RejectedEntry(
                    id = request.id,
                    request = request,
                    input = "${request.track.artists.joinToString(", ")} - ${request.track.name}",
                    requester = request.requester,
                    source = request.source,
                    payment = request.payment,
                    reason = reason,
                    atEpochMs = clock(),
                    refunded = isManagedChannelPoints(request.sourceRef),
                ),
            )
        }
    }

    private sealed interface Resolved {
        data class Found(val track: Track) : Resolved
        data class Fail(val reason: RejectReason) : Resolved
        data object Ignore : Resolved
        data object Unavailable : Resolved
    }

    private suspend fun resolve(incoming: IncomingRequest): Resolved =
        when (val input = classifySpotifyInput(incoming.input)) {
            is SpotifyInput.TrackId -> when (val r = catalog.track(input.id)) {
                is SpotifyResult.Ok -> if (r.value.isPlayable) Resolved.Found(r.value) else Resolved.Fail(RejectReason.Unplayable)
                SpotifyResult.NotFound -> Resolved.Fail(RejectReason.NoMatch)
                else -> Resolved.Unavailable
            }
            is SpotifyInput.ArtistId -> Resolved.Fail(RejectReason.NotATrack("artist"))
            is SpotifyInput.NotATrack -> Resolved.Fail(RejectReason.NotATrack(input.kind))
            is SpotifyInput.SearchText -> if (incoming.linkOnly) Resolved.Ignore else search(input.query)
            SpotifyInput.Empty -> if (incoming.linkOnly) Resolved.Ignore else Resolved.Fail(RejectReason.NoMatch)
        }

    private suspend fun search(query: String): Resolved = when (val r = catalog.searchTracks(query, 5)) {
        is SpotifyResult.Ok -> {
            val first = r.value.firstOrNull { it.isPlayable }
            when {
                first != null -> Resolved.Found(first)
                r.value.isEmpty() -> Resolved.Fail(RejectReason.NoMatch)
                else -> Resolved.Fail(RejectReason.Unplayable)
            }
        }
        SpotifyResult.NotFound -> Resolved.Fail(RejectReason.NoMatch)
        else -> Resolved.Unavailable
    }

    private fun buildRequest(incoming: IncomingRequest, track: Track) = Request(
        id = incoming.id,
        track = track,
        requester = incoming.requester,
        source = incoming.source,
        payment = incoming.payment,
        createdAtEpochMs = incoming.receivedAtEpochMs,
        sourceRef = incoming.sourceRef,
    )

    /** Runs the rules and the add as one step; the cooldown memory is updated in that same step. */
    private suspend fun process(incoming: IncomingRequest, track: Track): IntakeOutcome {
        val request = buildRequest(incoming, track)
        val viewerId = request.requester.twitchUserId
        val outcome = controller.submit(request) { snapshot ->
            val reason = if (!request.isViewerRequest) {
                null
            } else {
                RuleEngine.decide(
                    request,
                    RuleContext(
                        rules = settings.settings.value.rules,
                        requestsOpen = requestsOpen.value,
                        snapshot = snapshot,
                        lastAcceptedAtMs = viewerId?.let { lastAccepted[it] },
                        blacklist = blacklist.blacklist.value,
                        nowMs = clock(),
                    ),
                )
            }
            if (reason == null && viewerId != null) lastAccepted[viewerId] = clock()
            reason
        }
        return when (outcome) {
            is SubmitOutcome.Accepted -> IntakeOutcome.Accepted(request, outcome.position, outcome.waitMs)
            is SubmitOutcome.Rejected -> reject(incoming, outcome.reason, track)
        }
    }

    private fun reject(incoming: IncomingRequest, reason: RejectReason, track: Track?): IntakeOutcome.Rejected {
        recordRejection(incoming, reason, track)
        return IntakeOutcome.Rejected(reason, track, isListed(incoming, reason))
    }

    private fun isListed(incoming: IncomingRequest, reason: RejectReason) =
        !(reason == RejectReason.ViewerBlocked && incoming.payment == null)

    private fun isManagedChannelPoints(sourceRef: String?) = ChannelPointsRef.parse(sourceRef)?.managed == true

    /** Tries a pending paid request again on [retryDelaysMs] (then every 60 s) until Spotify answers. */
    private fun retryLater(incoming: IncomingRequest) {
        if (!retrying.add(incoming.id)) return
        scope.launch {
            try {
                var attempt = 0
                while (true) {
                    delay(retryDelaysMs.getOrElse(attempt) { retryDelaysMs.lastOrNull() ?: 60_000L })
                    attempt++
                    if (settle(incoming)) return@launch
                }
            } finally {
                retrying.remove(incoming.id)
            }
        }
    }

    /**
     * True once the pending request is settled and removed from pending.json. It is removed only after its result
     * is kept (queued, or in the Rejected list); a failed save keeps it pending, and the next attempt settles it
     * again (the same ids make that harmless).
     */
    private suspend fun settle(incoming: IncomingRequest): Boolean {
        val outcome = when (val resolved = resolve(incoming)) {
            Resolved.Unavailable -> return false
            Resolved.Ignore -> return pending.remove(incoming.id) // not possible for a paid request; never loop on it
            is Resolved.Fail -> reject(incoming, resolved.reason, null)
            is Resolved.Found -> process(incoming, resolved.track)
        }
        val kept = outcome !is IntakeOutcome.Rejected || !outcome.listed || rejected.entries.value.any { it.id == incoming.id }
        if (!kept || !pending.remove(incoming.id)) return false
        _pendingResolved.tryEmit(incoming to outcome)
        return true
    }
}
