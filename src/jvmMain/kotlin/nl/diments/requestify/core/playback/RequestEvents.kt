package nl.diments.requestify.core.playback

import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.queue.PlayedEntry

/**
 * The requests as [PlaybackController] holds them at one moment: what the rules decide on, and what `!song` and
 * `!queue` tell. [history] is newest first.
 */
data class PlaybackSnapshot(
    val queue: List<Request>,
    val handedOver: Request?,
    val playing: Request?,
    val history: List<PlayedEntry>,
    val nowPlaying: NowPlaying?,
)

/** What [PlaybackController.submit] did with a request. */
sealed interface SubmitOutcome {
    /**
     * In the queue (or already further along). [position] is 1-based in the queue; 0 when the id is already handed
     * over or playing. [waitMs] is about how long until it plays.
     */
    data class Accepted(val position: Int, val waitMs: Long) : SubmitOutcome

    data class Rejected(val reason: RejectReason) : SubmitOutcome
}

/** Who or what took a request out of the queue before it played. */
enum class RemoveReason {
    /** The streamer's X. */
    Streamer,

    /** The viewer's own `!wrongsong`. */
    WrongSong,

    /** A moderator's `!remove`. */
    ModRemove,

    /** The end-of-stream clear. */
    StreamEnded,

    /** A blacklist sweep moved it to the Rejected list. */
    Blacklisted,
}

/** What happened to a request, emitted by [PlaybackController.events] once the change is saved. */
sealed interface RequestEvent {
    /**
     * Spotify started playing the handed-over [request]. For one that played while the app was away (closed), it
     * comes late, once Spotify's recently played shows it, and the request is in history by then.
     */
    data class Started(val request: Request) : RequestEvent

    /** [request] left the queue without playing. Handing a request over to Spotify does not remove it. */
    data class Removed(val request: Request, val why: RemoveReason) : RequestEvent
}
