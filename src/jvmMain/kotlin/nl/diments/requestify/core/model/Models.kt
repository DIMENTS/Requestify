package nl.diments.requestify.core.model

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

@Serializable
sealed interface Payment {
    @Serializable
    data class Bits(val count: Int) : Payment

    @Serializable
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
