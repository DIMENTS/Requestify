package nl.diments.requestify.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The one donation platform in use: a streamer never uses two at once. Saved under these names: never change them. */
@Serializable
enum class DonationSourceKind {
    @SerialName("none") None,
    @SerialName("donolink") DonoLink,
    @SerialName("streamelements") StreamElements,
}

/**
 * Donations as song requests. Each minimum is in its source's currency: DonoLink is always euros, StreamElements is
 * the channel's currency ([streamElementsCurrency], read from StreamElements when the token is tested).
 */
@Serializable
data class DonationSettings(
    val source: DonationSourceKind = DonationSourceKind.None,
    val donoLinkMinimumCents: Long = 100,
    val streamElementsMinimumCents: Long = 100,
    val streamElementsCurrency: String? = null,
)
