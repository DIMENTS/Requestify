package nl.diments.requestify.core.donations.donolink

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.model.RequestSource

/** The cases of Signal-Catchall's parseDonolink.test.ts, kept. */
class DonoLinkParseTest {
    private val now = 1_700_000_000_000L

    private val base: JsonObject = buildJsonObject {
        put("id", "don_abc123def456ghi")
        put("type", "donation")
        put("platform", "donolink")
        put("occurred_at", "2026-09-11T10:00:00.000Z")
        put("recorded_at", "2026-09-11T10:00:01.000Z")
        put("actor", buildJsonObject { put("name", "Alice") })
        put("message", "bedankt!")
        put("value", 250)
        put("unit", "cents")
        put("currency", "EUR")
        put("refunded_amount_cents", JsonNull)
    }

    private fun with(vararg changes: Pair<String, Any?>) = JsonObject(
        base + changes.associate { (k, v) ->
            k to when (v) {
                null -> JsonNull
                is JsonObject -> v
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    @Test
    fun mapsADonoLinkDonation() {
        assertEquals(
            Donation(
                id = "dl-don_abc123def456ghi",
                source = RequestSource.DONOLINK,
                donorName = "Alice",
                message = "bedankt!",
                amountCents = 250,
                currency = "EUR",
                occurredAtEpochMs = Instant.parse("2026-09-11T10:00:00.000Z").toEpochMilli(),
            ),
            parseDonoLinkDonation(base, now),
        )
        assertEquals("2026-09-11T10:00:01.000Z", recordedAtOf(base))
    }

    @Test
    fun defaultsForMissingParts() {
        val d = parseDonoLinkDonation(with("actor" to buildJsonObject { put("name", JsonNull) }, "message" to null, "currency" to null), now)
        assertEquals(null, d?.donorName, "the handler makes it anonymous")
        assertEquals("", d?.message)
        assertEquals("EUR", d?.currency)
        assertEquals(null, parseDonoLinkDonation(with("actor" to null), now)?.donorName)
        assertEquals(null, parseDonoLinkDonation(with("actor" to buildJsonObject { put("name", "   ") }), now)?.donorName)
    }

    @Test
    fun anUnreadableTimeIsNow() {
        assertEquals(now, parseDonoLinkDonation(with("occurred_at" to "gisteren"), now)?.occurredAtEpochMs)
    }

    @Test
    fun everythingOutsideTheScopeIsNotADonation() {
        assertNull(parseDonoLinkDonation(with("unit" to "bits"), now))
        assertNull(parseDonoLinkDonation(with("type" to "follow"), now))
        assertNull(parseDonoLinkDonation(with("platform" to "twitch"), now), "mirrored Twitch events come from Twitch")
        assertNull(parseDonoLinkDonation(with("platform" to "streamelements"), now), "imported tips are not DonoLink's own")
        assertNull(parseDonoLinkDonation(with("value" to 2.5), now))
        assertNull(parseDonoLinkDonation(with("value" to -1), now))
        assertNull(parseDonoLinkDonation(with("id" to 42), now))
    }

    @Test
    fun aRefundIsIgnored() {
        assertEquals(250, parseDonoLinkDonation(with("refunded_amount_cents" to 250), now)?.amountCents)
    }
}
