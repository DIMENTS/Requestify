package nl.diments.requestify.core.rules

import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.playback.NowPlayingKind
import nl.diments.requestify.core.playback.PlaybackSnapshot

/** Everything [RuleEngine.decide] looks at. [lastAcceptedAtMs] is when this viewer's last request was accepted. */
data class RuleContext(
    val rules: Rules,
    val requestsOpen: Boolean,
    val snapshot: PlaybackSnapshot,
    val lastAcceptedAtMs: Long?,
    val blacklist: Blacklist,
    val nowMs: Long,
)

/** The rules as pure code. Only requests the rules apply to are passed in (see `isViewerRequest`). */
object RuleEngine {
    /** The reason the request is not let in, or null to accept it. Content filters apply to paid requests too. */
    fun decide(request: Request, ctx: RuleContext): RejectReason? {
        val rules = ctx.rules
        val track = request.track
        if (ctx.blacklist.blocksViewer(request.requester)) return RejectReason.ViewerBlocked
        if (ctx.blacklist.blocksTrack(track)) return RejectReason.TrackBlocked
        if (ctx.blacklist.blocksArtistOf(track)) return RejectReason.ArtistBlocked
        if (rules.blockExplicit && track.explicit) return RejectReason.Explicit
        if (track.durationMs < rules.minDurationMs) return RejectReason.TooShort(rules.minDurationMs)
        if (track.durationMs > rules.maxDurationMs) return RejectReason.TooLong(rules.maxDurationMs)
        if (request.payment != null) return null

        val snap = ctx.snapshot
        if (!ctx.requestsOpen) return RejectReason.Closed
        if (snap.queue.size >= rules.maxQueueLength) return RejectReason.QueueFull(rules.maxQueueLength)

        val viewerId = request.requester.twitchUserId
        if (viewerId != null) {
            val waiting = (snap.queue + listOfNotNull(snap.handedOver)).count { it.requester.twitchUserId == viewerId }
            if (waiting >= rules.maxPerViewer) return RejectReason.ViewerLimit(rules.maxPerViewer)
        }

        if (rules.cooldownMinutes > 0 && ctx.lastAcceptedAtMs != null) {
            val remaining = rules.cooldownMinutes * MINUTE_MS - (ctx.nowMs - ctx.lastAcceptedAtMs)
            if (remaining > 0) return RejectReason.Cooldown(remaining)
        }

        if (rules.noDuplicates && isDuplicate(track.id, ctx)) return RejectReason.Duplicate
        return null
    }

    private fun isDuplicate(trackId: String, ctx: RuleContext): Boolean {
        val snap = ctx.snapshot
        if (snap.queue.any { it.track.id == trackId }) return true
        if (snap.handedOver?.track?.id == trackId || snap.playing?.track?.id == trackId) return true
        if (snap.nowPlaying?.takeIf { it.kind == NowPlayingKind.Live }?.track?.id == trackId) return true
        val since = ctx.nowMs - ctx.rules.duplicateWindowMinutes * MINUTE_MS
        return snap.history.any { it.request.track.id == trackId && it.playedAtEpochMs >= since }
    }

    private const val MINUTE_MS = 60_000L
}
