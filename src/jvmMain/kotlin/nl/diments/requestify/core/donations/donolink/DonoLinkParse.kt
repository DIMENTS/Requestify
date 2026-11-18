package nl.diments.requestify.core.donations.donolink

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.model.RequestSource

/**
 * One item of DonoLink's `GET /activity` feed as a [Donation], or null when it is not one of ours: only DonoLink's
 * own donations (`type` donation, `platform` donolink, `unit` cents, a whole number of cents). DonoLink also mirrors
 * Twitch events and imports StreamElements tips; Requestify gets those elsewhere or not at all. A refund
 * (`refunded_amount_cents`) is never acted on. Ported from Signal-Catchall's `parseDonolink`.
 */
fun parseDonoLinkDonation(item: JsonObject, now: Long): Donation? {
    val id = item.string("id") ?: return null
    if (item.string("type") != "donation" || item.string("platform") != "donolink" || item.string("unit") != "cents") return null
    val value = item["value"] as? JsonPrimitive ?: return null
    if (value.isString) return null
    val cents = value.longOrNull ?: return null
    if (cents < 0 || value.content.contains('.')) return null
    val actor = item["actor"] as? JsonObject
    val name = (actor?.get("name") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
    return Donation(
        id = "dl-$id",
        source = RequestSource.DONOLINK,
        donorName = name,
        message = item.string("message").orEmpty(),
        amountCents = cents,
        currency = item.string("currency")?.takeIf { it.isNotBlank() } ?: "EUR",
        occurredAtEpochMs = item.string("occurred_at")?.let(::parseInstant) ?: now,
    )
}

/** The item's `recorded_at`, which DonoLink pages and filters on. */
fun recordedAtOf(item: JsonObject): String? = item.string("recorded_at")

/** A string field; null when missing, null, or not a string. */
private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** UTC ISO 8601, also with a space instead of the `T` (DonoLink's own example writes it so). */
internal fun parseInstant(text: String): Long? = try {
    Instant.parse(text.trim().replace(' ', 'T')).toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}
