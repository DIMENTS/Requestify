package nl.diments.requestify.core.intake

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.diments.requestify.core.model.IgnoredReason
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.model.ChannelPointsRef
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.model.isViewerRequest
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.playback.SubmitOutcome
import nl.diments.requestify.core.rules.RuleContext
import nl.diments.requestify.core.rules.RuleEngine
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.ShortLinkResolver
import nl.diments.requestify.core.spotify.ShortLinkTarget
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyInput
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.classifySpotifyInput
import nl.diments.requestify.core.spotify.follow

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

/** What the intake did with [incoming], reported on [RequestIntake.records] once the outcome is kept. */
/** [ignoredReason]: why an [IntakeOutcome.Ignored] donation was no request (null: it had no Spotify link). */
data class IntakeRecord(
    val incoming: IncomingRequest,
    val outcome: IntakeOutcome,
    val atEpochMs: Long,
    val ignoredReason: IgnoredReason? = null,
)

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
    /** Follows `spotify.link/…` short links to the Spotify link behind them. */
    private val shortLinks: ShortLinkResolver,
    private val retryDelaysMs: List<Long> = listOf(5_000, 15_000, 30_000, 60_000), // then every 60 s
) {
    private val _pendingResolved =
        MutableSharedFlow<Pair<IncomingRequest, IntakeOutcome>>(extraBufferCapacity = Int.MAX_VALUE)

    /** A pending paid request settled (accepted or rejected): for its reply. */
    val pendingResolved: SharedFlow<Pair<IncomingRequest, IntakeOutcome>> = _pendingResolved.asSharedFlow()

    private val _records = MutableSharedFlow<IntakeRecord>(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * Every outcome, for the Log: each [submit] and [rejectBeforeRules], each pending request when it settles, and
     * each "Add anyway". It never drops or waits; it does not replay, so a listener subscribes before [start].
     */
    val records: SharedFlow<IntakeRecord> = _records.asSharedFlow()

    private fun recorded(incoming: IncomingRequest, outcome: IntakeOutcome) {
        _records.tryEmit(IntakeRecord(incoming, outcome, clock()))
    }

    /** A donation that arrived but is no request ([reason]): only the Log hears of it. */
    fun recordIgnored(incoming: IncomingRequest, reason: IgnoredReason) {
        _records.tryEmit(IntakeRecord(incoming, IntakeOutcome.Ignored, clock(), reason))
    }

    /** Viewer id to when their last request was accepted. In memory only: a cooldown paces a stream, not restarts. */
    private val lastAccepted = ConcurrentHashMap<String, Long>()
    private val retrying = ConcurrentHashMap.newKeySet<String>()
    private val savingRejection = ConcurrentHashMap.newKeySet<String>()

    /** How many paid rejections wait to be saved later ([saveRejectionLater]). */
    private val rejectionsWaiting = MutableStateFlow(0)

    /** Bumped to make every waiting rejection try again at once ([awaitRejectionsSavedLater]). */
    private val retryRejectionsNow = MutableStateFlow(0L)

    /** Resumes the paid requests that were waiting for Spotify when the app last ran. */
    fun start() {
        pending.pending.value.forEach { retryLater(it) }
    }

    suspend fun submit(incoming: IncomingRequest): IntakeOutcome = decide(incoming).also { recorded(incoming, it) }

    private suspend fun decide(incoming: IncomingRequest): IntakeOutcome {
        val track = when (val resolved = resolve(incoming)) {
            is Resolved.Found -> resolved.track
            Resolved.Ignore -> return IntakeOutcome.Ignored
            is Resolved.Fail -> return reject(incoming, resolved.reason, null)
            Resolved.Unavailable -> {
                if (incoming.payment == null) return reject(incoming, RejectReason.SpotifyUnavailable, null)
                // A paid request is never rejected for this: when pending.json cannot be written it is still
                // retried in memory (and settling it removes an id that was never saved: a no-op).
                pending.add(incoming)
                retryLater(incoming)
                return IntakeOutcome.Pending
            }
        }
        return process(incoming, track)
    }

    /**
     * Lists a request its source refuses before the rules (Bits below the minimum), with its track when the link
     * resolves now, so the row can "Add anyway". When Spotify cannot be reached, or the link is no track, it is listed
     * without one (the row then offers "Dismiss").
     */
    suspend fun rejectBeforeRules(incoming: IncomingRequest, reason: RejectReason): IntakeOutcome.Rejected =
        reject(incoming, reason, (resolve(incoming) as? Resolved.Found)?.track).also { recorded(incoming, it) }

    /**
     * True when a request with [id] is already here: queued, handed over, playing, played, in the Rejected list or
     * waiting for Spotify. A source that may see the same item twice (a catch-up after a restart) checks this first.
     */
    /**
     * Returns once everything decided so far is on disk: the queue, the Rejected list and the requests waiting for
     * Spotify. A source saves what it has handled (a donation cursor) only after this, so a crash in between makes it
     * fetch the item again (and [isKnown] drops it), never skip it.
     */
    suspend fun awaitSaved() {
        rejected.awaitSaved()
        pending.awaitSaved()
        controller.awaitSaved()
    }

    /**
     * Quit and Windows shutting down: the paid rejections that wait to be saved later (their file could not be read at
     * start) try again at once, and this returns once none waits any more. Bound it with a timeout: while the file
     * stays unreadable it does not return. Not part of [awaitSaved], which a donation source awaits for every donation.
     */
    suspend fun awaitRejectionsSavedLater() {
        retryRejectionsNow.update { it + 1 }
        rejectionsWaiting.first { it == 0 }
        rejected.awaitSaved()
    }

    suspend fun isKnown(id: String): Boolean {
        if (rejected.entries.value.any { it.id == id } || pending.pending.value.any { it.id == id }) return true
        val s = controller.loadedSnapshot()
        return s.queue.any { it.id == id } || s.handedOver?.id == id || s.playing?.id == id ||
            s.history.any { it.request.id == id }
    }

    /**
     * True when the rejection is in the Rejected list, or is a paid one that will be (it is saved again on the retry
     * schedule until it is). False for what is not listed: free requests of blocked viewers, and free rejections
     * that could not be saved.
     */
    private fun record(incoming: IncomingRequest, reason: RejectReason, track: Track?): Boolean {
        if (!isListed(incoming.payment, incoming.requester)) return false
        val entry = RejectedEntry(
            id = incoming.id,
            request = track?.let { buildRequest(incoming, it) },
            input = incoming.input,
            requester = incoming.requester,
            source = incoming.source,
            payment = incoming.payment,
            reason = reason,
            atEpochMs = clock(),
            refunded = isManagedChannelPoints(incoming.sourceRef),
        )
        if (rejected.add(entry)) return true
        if (entry.payment == null) return false
        saveRejectionLater(entry)
        return true
    }

    /** Saves a paid rejection that could not be written yet, on the retry schedule. */
    private fun saveRejectionLater(entry: RejectedEntry) {
        if (!savingRejection.add(entry.id)) return
        rejectionsWaiting.update { it + 1 }
        scope.launch {
            try {
                var attempt = 0
                while (true) {
                    val seen = retryRejectionsNow.value
                    // The retry schedule, cut short by [awaitRejectionsSavedLater].
                    withTimeoutOrNull(retryDelaysMs.getOrElse(attempt) { retryDelaysMs.lastOrNull() ?: 60_000L }) {
                        retryRejectionsNow.first { it != seen }
                    }
                    attempt++
                    if (rejected.add(entry)) return@launch
                }
            } finally {
                savingRejection.remove(entry.id)
                rejectionsWaiting.update { it - 1 }
            }
        }
    }

    /** "Add anyway": queues the rejected request past the rules, then takes it off the list. */
    fun addAnyway(entryId: String) {
        val entry = rejected.entries.value.firstOrNull { it.id == entryId } ?: return
        val request = entry.request?.copy(rulesWaived = true) ?: return
        controller.enqueue(request)
        rejected.remove(entryId)
        recordAdded(
            IncomingRequest(
                id = entry.id, input = entry.input, linkOnly = false, requester = entry.requester, source = entry.source,
                payment = entry.payment, sourceRef = request.sourceRef, receivedAtEpochMs = entry.atEpochMs,
            ),
            request,
        )
    }

    /** The streamer's own add from Home: queued at the end, past the rules, and reported like any other request. */
    fun addOwn(request: Request) {
        controller.enqueue(request)
        recordAdded(
            IncomingRequest(
                id = request.id, input = request.track.uri, linkOnly = false, requester = request.requester,
                source = request.source, payment = request.payment, sourceRef = request.sourceRef,
                receivedAtEpochMs = request.createdAtEpochMs,
            ),
            request,
        )
    }

    /** Reports [request], just enqueued, with where it went (a later read of the same lock-guarded state). */
    private fun recordAdded(incoming: IncomingRequest, request: Request) {
        scope.launch {
            val position = controller.loadedSnapshot().queue.indexOfFirst { it.id == request.id } + 1
            recorded(incoming, IntakeOutcome.Accepted(request, position, waitMs = 0))
        }
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

    /**
     * Moves the queued viewer requests that [matches] to the Rejected list, before it returns (a skip right after a
     * block must not hand one of them over). Own and "added anyway" ones stay.
     */
    private suspend fun sweep(reason: RejectReason, matches: (Request) -> Boolean) {
        val listed = controller.loadedSnapshot().queue
            .filter { it.isViewerRequest && matches(it) }
            .filter { listedOrNeedNot(it, reason) }
            .mapTo(HashSet()) { it.id }
        leaveQueueOnceListed(listed)
    }

    /**
     * Takes the requests with [ids] out of the queue once their Rejected entries are on disk: a request whose entry
     * cannot be saved yet stays queued (and leaves later, in the background, once it is), and a crash in between
     * leaves a duplicate, never a loss. Waits at most [SWEEP_SAVE_WAIT_MS], so a failing disk never holds a block up.
     * A request handed over to Spotify meanwhile plays after all: its entry leaves Rejected again.
     */
    private suspend fun leaveQueueOnceListed(ids: Set<String>) {
        if (ids.isEmpty()) return
        suspend fun leave() {
            val removed = controller.removeMatching(RemoveReason.Blacklisted) { queue -> queue.filter { it.id in ids } }.mapTo(HashSet()) { it.id }
            val notRemoved = ids - removed
            if (notRemoved.isEmpty()) return
            val s = controller.loadedSnapshot()
            notRemoved.filter { id -> s.handedOver?.id == id || s.playing?.id == id || s.history.any { it.request.id == id } }
                .forEach { rejected.remove(it) }
        }
        if (withTimeoutOrNull(SWEEP_SAVE_WAIT_MS) { rejected.awaitSaved() } != null) {
            leave()
        } else {
            scope.launch {
                rejected.awaitSaved()
                leave()
            }
        }
    }

    /**
     * The streamer blocked something from the queue row of [id]: that request leaves the queue too, also when the
     * sweep left it (the streamer's own add, which simply goes, or one let in anyway, which is listed like a swept
     * one: a paid request is never just dropped).
     */
    suspend fun removeBlocked(id: String, reason: RejectReason) {
        val request = controller.loadedSnapshot().queue.firstOrNull { it.id == id } ?: return
        if (request.source == RequestSource.MANUAL) {
            controller.removeMatching(RemoveReason.Blacklisted) { queue -> queue.filter { it.id == id } }
        } else if (listedOrNeedNot(request, reason)) {
            leaveQueueOnceListed(setOf(id))
        }
    }

    /**
     * Lists a request that leaves the queue for [reason]: true once it is listed or needs no entry (a free request of
     * a blocked viewer); false while rejected.json could not be read yet (the request then stays queued).
     */
    private fun listedOrNeedNot(request: Request, reason: RejectReason): Boolean =
        !isListed(request.payment, request.requester) || rejected.add(
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

    private sealed interface Resolved {
        data class Found(val track: Track) : Resolved
        data class Fail(val reason: RejectReason) : Resolved
        data object Ignore : Resolved
        data object Unavailable : Resolved
    }

    private suspend fun resolve(incoming: IncomingRequest): Resolved = resolve(classifySpotifyInput(incoming.input), incoming)

    /**
     * A short link is a Spotify link (so a link-only cheer with one is a request): it is followed and what it leads to
     * is resolved. One that leads to no Spotify link is rejected; one that cannot be followed now counts as Spotify
     * being unreachable (a paid request waits and is tried again).
     */
    private suspend fun resolve(input: SpotifyInput, incoming: IncomingRequest): Resolved = when (input) {
        is SpotifyInput.TrackId -> when (val r = catalog.track(input.id)) {
            is SpotifyResult.Ok -> if (r.value.isPlayable) Resolved.Found(r.value) else Resolved.Fail(RejectReason.Unplayable)
            SpotifyResult.NotFound -> Resolved.Fail(RejectReason.NoMatch)
            else -> Resolved.Unavailable
        }
        is SpotifyInput.ArtistId -> Resolved.Fail(RejectReason.NotATrack("artist"))
        is SpotifyInput.NotATrack -> Resolved.Fail(RejectReason.NotATrack(input.kind))
        is SpotifyInput.ShortLink -> when (val target = followShortLink(input)) {
            is ShortLinkTarget.Link -> resolve(target.input, incoming)
            ShortLinkTarget.Unresolvable -> Resolved.Fail(RejectReason.ShortLinkUnresolved)
            ShortLinkTarget.Unreachable -> Resolved.Unavailable
        }
        is SpotifyInput.SearchText -> if (incoming.linkOnly) Resolved.Ignore else search(input.query)
        SpotifyInput.Empty -> if (incoming.linkOnly) Resolved.Ignore else Resolved.Fail(RejectReason.NoMatch)
    }

    /** Where [link] leads; for the streamer's own add fields too. */
    suspend fun followShortLink(link: SpotifyInput.ShortLink): ShortLinkTarget = shortLinks.follow(link)

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
        return IntakeOutcome.Rejected(reason, track, record(incoming, reason, track))
    }

    /** Every rejection is listed except free requests of blocked viewers. */
    private fun isListed(payment: Payment?, requester: Requester) =
        payment != null || !blacklist.blacklist.value.blocksViewer(requester)

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
        recorded(incoming, outcome)
        return true
    }
}

/** How long a blacklist sweep waits for its Rejected entries to reach disk before the request leaves in the background. */
private const val SWEEP_SAVE_WAIT_MS = 2_000L
