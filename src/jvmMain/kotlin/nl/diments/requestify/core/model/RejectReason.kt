package nl.diments.requestify.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a request was not let into the queue. Saved in rejected.json under the stable names below: never change
 * them (nor the field names), or saved rejections are lost.
 */
@Serializable
sealed interface RejectReason {
    /** A Spotify link to something other than a track; [kind] is e.g. "album", "playlist" or "artist". */
    @Serializable
    @SerialName("not_a_track")
    data class NotATrack(val kind: String) : RejectReason

    /** The search found nothing, or the linked track does not exist. */
    @Serializable
    @SerialName("no_match")
    data object NoMatch : RejectReason

    /** Spotify says the track cannot be played (e.g. not in the streamer's country). */
    @Serializable
    @SerialName("unplayable")
    data object Unplayable : RejectReason

    /** Spotify could not be asked about the track. */
    @Serializable
    @SerialName("spotify_unavailable")
    data object SpotifyUnavailable : RejectReason

    @Serializable
    @SerialName("viewer_blocked")
    data object ViewerBlocked : RejectReason

    @Serializable
    @SerialName("track_blocked")
    data object TrackBlocked : RejectReason

    @Serializable
    @SerialName("artist_blocked")
    data object ArtistBlocked : RejectReason

    @Serializable
    @SerialName("explicit")
    data object Explicit : RejectReason

    @Serializable
    @SerialName("too_short")
    data class TooShort(val minMs: Long) : RejectReason

    @Serializable
    @SerialName("too_long")
    data class TooLong(val maxMs: Long) : RejectReason

    /** Requests are closed. */
    @Serializable
    @SerialName("closed")
    data object Closed : RejectReason

    /** The queue already holds [max] requests. */
    @Serializable
    @SerialName("queue_full")
    data class QueueFull(val max: Int) : RejectReason

    /** The viewer already has [max] requests waiting. */
    @Serializable
    @SerialName("viewer_limit")
    data class ViewerLimit(val max: Int) : RejectReason

    /** The viewer may request again in [remainingMs]. */
    @Serializable
    @SerialName("cooldown")
    data class Cooldown(val remainingMs: Long) : RejectReason

    /** The track is queued, handed over or playing already, or played too recently. */
    @Serializable
    @SerialName("duplicate")
    data object Duplicate : RejectReason

    /** Paid with fewer Bits than the minimum of [minBits]. */
    @Serializable
    @SerialName("below_minimum")
    data class BelowMinimum(val minBits: Int) : RejectReason

    /** A Spotify short link (`spotify.link/…`) that does not lead to a Spotify link: use the open.spotify.com link. */
    @Serializable
    @SerialName("short_link")
    data object ShortLinkUnresolved : RejectReason

    /** A donation below the minimum of [minCents] in [currency]. */
    @Serializable
    @SerialName("below_minimum_amount")
    data class BelowMinimumAmount(val minCents: Long, val currency: String) : RejectReason

    /** A donation in [currency], which the minimum (in another currency) cannot be compared with: the streamer decides. */
    @Serializable
    @SerialName("other_currency")
    data class OtherCurrency(val currency: String) : RejectReason
}
