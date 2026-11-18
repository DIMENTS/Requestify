package nl.diments.requestify.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why a donation or cheer that arrived became no request. Names are kept in the Log's day files: never change them. */
@Serializable
enum class IgnoredReason {
    /** Its message holds no Spotify link. */
    @SerialName("no_link") NoLink,

    /** The streamer's StreamElements moderation rejected the tip. */
    @SerialName("moderation_rejected") ModerationRejected,

    /** StreamElements reports the tip's payment as failed. */
    @SerialName("tip_failed") TipFailed,
}
