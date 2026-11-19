package nl.diments.requestify.core.update

import nl.diments.requestify.core.stream.GateStatus
import nl.diments.requestify.core.stream.RequestsGate
import nl.diments.requestify.core.stream.StreamState

/** What `stream.json` said about the stream when Requestify started, before anything connected. */
sealed interface StreamAtStart {
    /** The file could not be read: whether a stream runs is unknown, so treat it as live. */
    data object Unreadable : StreamAtStart

    /** No live stream on record (a missing file reads as this too: no stream was ever seen). */
    data object NeverSeenLive : StreamAtStart

    /** The stream was live when Requestify last saw it, at [atMs] (every minute while live, and at Quit). */
    data class LastSeenLive(val atMs: Long) : StreamAtStart
}

/**
 * What [state] (stream.json as read at start; null: unreadable) says about a stream: live, with when it was last seen
 * live (unknown: [now]), or dropped and in its grace since `offlineSinceMs` (it may come back any moment).
 */
fun streamAtStart(state: StreamState?, now: Long): StreamAtStart = when {
    state == null -> StreamAtStart.Unreadable
    state.live -> StreamAtStart.LastSeenLive(state.lastSeenLiveMs ?: now)
    state.offlineSinceMs != null -> StreamAtStart.LastSeenLive(state.offlineSinceMs)
    else -> StreamAtStart.NeverSeenLive
}

fun streamAtStart(gate: RequestsGate, now: Long): StreamAtStart = streamAtStart(gate.loadedState, now)

/**
 * Whether a start installs the waiting update ([pending]) before anything connects. Never in a build that does not
 * update itself ([running] null or local), never on a crash restart (it must come back fast and may be mid-stream),
 * never while a stream may be running (last seen live within [graceMs], the gate's own grace, or unknown), and not
 * after [maxAttempts] failed tries (then only by hand).
 */
fun installAtStart(
    afterCrash: Boolean,
    running: AppVersion?,
    pending: PendingUpdate?,
    stream: StreamAtStart,
    now: Long,
    graceMs: Long = 600_000,
    maxAttempts: Int = 2,
): Boolean {
    if (afterCrash || running == null || running.isLocalBuild || pending == null) return false
    val target = AppVersion.parse(pending.version) ?: return false
    if (target <= running || pending.attempts >= maxAttempts) return false
    return when (stream) {
        StreamAtStart.Unreadable -> false
        StreamAtStart.NeverSeenLive -> true
        is StreamAtStart.LastSeenLive -> now - stream.atMs > graceMs
    }
}

/** "Nu bijwerken" only while the stream is not live and requests are closed: an update never interrupts a stream. */
fun canInstallNow(status: GateStatus): Boolean = !status.live && !status.open
