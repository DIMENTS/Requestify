package nl.diments.requestify.core.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.model.IgnoredReason
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.RemoveReason

/**
 * One line of the Log's day files. Records with the same [rowId] make one row: a request with everything that happened
 * to it, one connection outage, one stream change, one gap. Saved for 30 days under the names below: never change them.
 */
@Serializable
sealed interface LogRecord {
    val rowId: String
    val atEpochMs: Long

    /** A request came in; [rowId] is its id. */
    @Serializable
    @SerialName("request_received")
    data class RequestReceived(
        override val rowId: String,
        override val atEpochMs: Long,
        val source: RequestSource,
        val requester: Requester,
        val input: String,
        val payment: Payment? = null,
        val sourceRef: String? = null,
        /** When the donor gave, for a donation (it can arrive later, through a catch-up). */
        val occurredAtEpochMs: Long? = null,
    ) : LogRecord

    @Serializable
    @SerialName("request_step")
    data class RequestStep(override val rowId: String, override val atEpochMs: Long, val step: LogStep) : LogRecord

    /** One outage is one row: [rowId] is `conn:<service>:<start>`. */
    @Serializable
    @SerialName("connection")
    data class ConnectionChanged(
        override val rowId: String,
        override val atEpochMs: Long,
        val service: LogService,
        val state: LogConnectionState,
        val detail: String? = null,
    ) : LogRecord

    @Serializable
    @SerialName("stream")
    data class StreamChanged(override val rowId: String, override val atEpochMs: Long, val change: LogStreamChange) : LogRecord

    /** Requestify did not end properly the last time ([PreviousRun]); one row, always a problem. */
    @Serializable
    @SerialName("app_stopped")
    data class AppStopped(
        override val rowId: String,
        override val atEpochMs: Long,
        val lastAliveEpochMs: Long,
        val crashLine: String? = null,
        val restarted: Boolean = false,
    ) : LogRecord

    /** Requestify updated itself from [from] to [to] (reported at the first start of [to]); good news, no problem. */
    @Serializable
    @SerialName("app_updated")
    data class AppUpdated(override val rowId: String, override val atEpochMs: Long, val from: String, val to: String) : LogRecord

    /**
     * Updating to [version] failed: its install ended with Windows Installer's [exitCode], or its download failed for
     * [reason]. Requestify kept running the version it had.
     */
    @Serializable
    @SerialName("update_failed")
    data class UpdateFailed(
        override val rowId: String,
        override val atEpochMs: Long,
        val version: String,
        val exitCode: Int? = null,
        val reason: String? = null,
    ) : LogRecord

    /** Requests of [source] may have been missed from [fromEpochMs] (null: unknown, "before") to [toEpochMs]. */
    @Serializable
    @SerialName("gap")
    data class GapNoticed(
        override val rowId: String,
        override val atEpochMs: Long,
        val source: RequestSource,
        val fromEpochMs: Long?,
        val toEpochMs: Long,
    ) : LogRecord
}

/** What happened to a request. */
@Serializable
sealed interface LogStep {
    @Serializable @SerialName("queued")
    data class Queued(val position: Int) : LogStep

    @Serializable @SerialName("rejected")
    data class Rejected(val reason: RejectReason, val listed: Boolean) : LogStep

    /** Paid, waiting until Spotify can be reached. */
    @Serializable @SerialName("pending")
    data object Pending : LogStep

    /** Not a request: a cheer or donation without a Spotify link, or a tip StreamElements rejected or failed ([reason]). */
    @Serializable @SerialName("ignored")
    data class Ignored(val reason: IgnoredReason = IgnoredReason.NoLink) : LogStep

    @Serializable @SerialName("handed_over")
    data object HandedOver : LogStep

    @Serializable @SerialName("played")
    data object Played : LogStep

    @Serializable @SerialName("removed")
    data class Removed(val why: RemoveReason) : LogStep

    /** A Channel Points refund that failed [tries] times so far and is tried again. */
    @Serializable @SerialName("refund_pending")
    data class RefundPending(val tries: Int) : LogStep

    @Serializable @SerialName("refunded")
    data object Refunded : LogStep

    @Serializable @SerialName("refund_gave_up")
    data object RefundGaveUp : LogStep

    /** Twitch was told the Channel Points redemption played. */
    @Serializable @SerialName("fulfilled")
    data object Fulfilled : LogStep

    /** The request's track, once known. */
    @Serializable @SerialName("track")
    data class Resolved(val track: Track) : LogStep
}

@Serializable
enum class LogService {
    @SerialName("spotify") Spotify,
    @SerialName("twitch") Twitch,
    @SerialName("donolink") DonoLink,
    @SerialName("streamelements") StreamElements,

    /** The Log itself (it could not save). */
    @SerialName("log") Log,

    /** Requestify's own files (one could not be saved). */
    @SerialName("files") Files,
}

@Serializable
enum class LogConnectionState {
    @SerialName("lost") Lost,
    @SerialName("restored") Restored,
    @SerialName("login_expired") LoginExpired,
    @SerialName("key_refused") KeyRefused,
    @SerialName("key_expiring") KeyExpiring,

    /** The streamer switched to another donation source: the old one's outage is over. */
    @SerialName("source_changed") SourceChanged,

    /** Still open when Requestify last closed: over with that run (the next one does not "restore" it). */
    @SerialName("ended_with_app") EndedWithApp;

    /** Whether this ends an outage row. */
    val closesOutage: Boolean get() = this == Restored || this == SourceChanged || this == EndedWithApp
}

@Serializable
enum class LogStreamChange {
    @SerialName("live") Live,
    @SerialName("offline") Offline,
    @SerialName("opened_by_hand") OpenedByHand,
    @SerialName("closed_by_hand") ClosedByHand,
    @SerialName("stream_ended") StreamEnded,
}
