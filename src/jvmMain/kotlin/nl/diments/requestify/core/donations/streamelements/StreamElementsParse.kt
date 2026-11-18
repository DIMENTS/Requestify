package nl.diments.requestify.core.donations.streamelements

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.donations.donolink.parseInstant
import nl.diments.requestify.core.model.RequestSource

/**
 * The `data` of a `channel.tips` message as a [Donation], or null when it is no request: no id, an amount that is
 * not a positive number, a failed payment, or a tip the streamer's moderation rejected. A tip still `pending`
 * moderation is paid, so it counts. Amounts become cents, rounded half up.
 */
fun parseStreamElementsTip(data: JsonObject, now: Long): Donation? {
    val id = data.text("_id") ?: return null
    if (data.text("approved") == "rejected") return null
    data.text("status")?.let { if (it != "success") return null }
    val donation = data["donation"] as? JsonObject ?: return null
    val amount = (donation["amount"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBigDecimalOrNull() ?: return null
    if (amount.signum() < 0) return null
    val user = donation["user"] as? JsonObject
    return Donation(
        id = "se-$id",
        source = RequestSource.STREAMELEMENTS,
        donorName = user?.text("username")?.trim()?.takeIf { it.isNotEmpty() },
        message = donation.text("message").orEmpty(),
        amountCents = amount.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).toLong(),
        currency = donation.text("currency")?.takeIf { it.isNotBlank() }?.uppercase() ?: "USD",
        occurredAtEpochMs = data.text("createdAt")?.let(::parseInstant) ?: now,
    )
}

private val HUNDRED = BigDecimal(100)

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
