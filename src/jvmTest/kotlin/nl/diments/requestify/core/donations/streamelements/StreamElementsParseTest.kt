package nl.diments.requestify.core.donations.streamelements

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import nl.diments.requestify.core.model.IgnoredReason
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.storage.AppJson

class StreamElementsParseTest {
    private val now = 1_700_000_000_000L

    private val tip: JsonObject = run {
        val text = checkNotNull(javaClass.getResourceAsStream("/streamelements/tip-message.json")).use { it.readBytes().decodeToString() }
        AppJson.instance.parseToJsonElement(text).jsonObject.getValue("data").jsonObject
    }

    private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))

    private fun withAmount(amount: Double) = JsonObject(
        tip + ("donation" to JsonObject(tip.getValue("donation").jsonObject + ("amount" to JsonPrimitive(amount)))),
    )

    @Test
    fun parsesATip() {
        assertEquals(
            Donation(
                id = "se-6730a1b2c3d4e5f6a7b8c9d0",
                source = RequestSource.STREAMELEMENTS,
                donorName = "Jan",
                message = "voor jou https://open.spotify.com/track/AAAAAAAAAAAAAAAAAAAAAA",
                amountCents = 350,
                currency = "USD",
                occurredAtEpochMs = Instant.parse("2026-11-18T20:09:58Z").toEpochMilli(),
            ),
            parseStreamElementsTip(tip, now),
        )
    }

    @Test
    fun amountsBecomeCents() {
        assertEquals(350, parseStreamElementsTip(withAmount(3.5), now)?.amountCents)
        assertEquals(10, parseStreamElementsTip(withAmount(0.1), now)?.amountCents)
        assertEquals(268, parseStreamElementsTip(withAmount(2.675), now)?.amountCents, "half up")
        assertEquals(500, parseStreamElementsTip(withAmount(5.0), now)?.amountCents)
        assertNull(parseStreamElementsTip(withAmount(-1.0), now))
    }

    @Test
    fun skippedTipsSayWhy() {
        assertEquals(IgnoredReason.ModerationRejected, skippedStreamElementsTip(tip.with("approved", "rejected"), now)?.second)
        assertEquals(IgnoredReason.TipFailed, skippedStreamElementsTip(tip.with("status", "failed"), now)?.second)
        assertEquals(350, skippedStreamElementsTip(tip.with("status", "failed"), now)?.first?.amountCents, "who and how much, for the Log")
        assertNull(skippedStreamElementsTip(tip, now), "a paid tip is no skipped one")
    }

    @Test
    fun aRejectedOrFailedTipIsNoRequest() {
        assertNull(parseStreamElementsTip(tip.with("approved", "rejected"), now))
        assertNull(parseStreamElementsTip(tip.with("status", "failed"), now))
        assertEquals(350, parseStreamElementsTip(tip.with("approved", "pending"), now)?.amountCents, "paid, awaiting moderation")
    }

    @Test
    fun missingPartsHaveDefaults() {
        val noName = JsonObject(tip + ("donation" to JsonObject(tip.getValue("donation").jsonObject - "user")))
        assertNull(parseStreamElementsTip(noName, now)?.donorName)
        assertEquals(now, parseStreamElementsTip(tip.with("createdAt", "soon"), now)?.occurredAtEpochMs)
        assertNull(parseStreamElementsTip(JsonObject(tip - "_id"), now))
    }
}
