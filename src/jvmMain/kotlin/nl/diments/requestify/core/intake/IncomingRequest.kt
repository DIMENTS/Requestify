package nl.diments.requestify.core.intake

import kotlinx.serialization.Serializable
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester

/** A request as a source hands it over, before the track is resolved. Saved in pending.json. */
@Serializable
data class IncomingRequest(
    val id: String,
    val input: String,
    val linkOnly: Boolean,
    val requester: Requester,
    val source: RequestSource,
    val payment: Payment?,
    val sourceRef: String? = null,
    val receivedAtEpochMs: Long,
    /** The chat message id to reply to. */
    val replyTo: String? = null,
)
