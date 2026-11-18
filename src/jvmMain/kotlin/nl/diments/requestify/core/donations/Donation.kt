package nl.diments.requestify.core.donations

import nl.diments.requestify.core.model.RequestSource

/**
 * One donation as a donation platform reports it. [id] is already unique across platforms (`dl-…`, `se-…`);
 * [donorName] is free text (null or blank: anonymous), never a Twitch identity. Money is in cents of [currency].
 */
data class Donation(
    val id: String,
    val source: RequestSource,
    val donorName: String?,
    val message: String,
    val amountCents: Long,
    val currency: String,
    val occurredAtEpochMs: Long,
)
