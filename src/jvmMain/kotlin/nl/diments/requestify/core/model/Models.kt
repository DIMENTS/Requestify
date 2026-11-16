package nl.diments.requestify.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val explicit: Boolean,
    val albumArtUrl: String?,
)

@Serializable
enum class RequestSource { MANUAL, CHAT, CHANNEL_POINTS, BITS, STREAMELEMENTS, DONOLINK }

/** Saved in queue.json under the stable names below: never change them, or saved paid requests are lost. */
@Serializable
sealed interface Payment {
    @Serializable
    @SerialName("bits")
    data class Bits(val count: Int) : Payment

    @Serializable
    @SerialName("money")
    data class Money(val amountCents: Long, val currency: String) : Payment
}

@Serializable
data class Requester(val displayName: String, val login: String? = null)

@Serializable
data class Request(
    val id: String,
    val track: Track,
    val requester: Requester,
    val source: RequestSource,
    val payment: Payment?,
    val createdAtEpochMs: Long,
    val sourceRef: String? = null,
) {
    val isPaid: Boolean get() = payment != null
}
