package nl.diments.requestify.core.spotify

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpotifyCallGateTest {
    private var now = 1_000_000L
    private val gate = SpotifyCallGate { now }

    private val poll = "GET me/player/currently-playing"
    private val search = "GET search"

    @Test
    fun aShortRateLimitHoldsEveryCallUntilItIsOver() {
        assertNull(gate.heldFor(poll))
        val refused = gate.refused(poll, retryAfterMs = 7_000, quota = false)
        assertEquals(7_000, refused.retryAfterMs)
        assertEquals(false, refused.quota)

        now += 2_000
        assertEquals(5_000, gate.heldFor(search)?.retryAfterMs, "Spotify's 30 s window counts the whole app")
        now += 4_999
        assertNotNull(gate.heldFor(poll))
        now += 1
        assertNull(gate.heldFor(poll))
        assertNull(gate.heldFor(search))
    }

    @Test
    fun aQuotaHoldsOnlyItsOwnEndpoint() {
        gate.refused(poll, retryAfterMs = 61_887_000, quota = true)
        assertEquals(true, gate.heldFor(poll)?.quota)
        assertNull(gate.heldFor(search))
    }

    @Test
    fun aWaitTheRateWindowCannotExplainCountsAsAQuota() {
        assertEquals(true, gate.refused(poll, retryAfterMs = 68_167_000, quota = false).quota)
        assertNull(gate.heldFor(search))
        assertEquals(false, gate.refused(search, retryAfterMs = 60_000, quota = false).quota)
    }

    @Test
    fun withoutAWaitItGrowsTo15Minutes() {
        val waits = List(10) { gate.refused(poll, retryAfterMs = null, quota = false).retryAfterMs }
        assertEquals(listOf(5_000L, 10_000, 20_000, 40_000, 80_000, 160_000, 320_000, 640_000, 900_000, 900_000), waits)
    }

    @Test
    fun aQuotaWithoutAWaitStartsAtAMinute() {
        val waits = List(6) { gate.refused(poll, retryAfterMs = null, quota = true).retryAfterMs }
        assertEquals(listOf(60_000L, 120_000, 240_000, 480_000, 900_000, 900_000), waits)
    }

    @Test
    fun aWaitOfZeroIsNoWaitSpotifyNamed() {
        assertEquals(5_000, gate.refused(poll, retryAfterMs = 0, quota = false).retryAfterMs)
    }

    @Test
    fun aCallThatIsTakenEndsTheGrowth() {
        repeat(4) { gate.refused(poll, retryAfterMs = null, quota = false) }
        gate.taken(poll)
        assertEquals(5_000, gate.refused(poll, retryAfterMs = null, quota = false).retryAfterMs)
    }

    @Test
    fun aShorterRefusalNeverShortensAWait() {
        gate.refused(poll, retryAfterMs = 40_000, quota = false)
        gate.refused(search, retryAfterMs = 3_000, quota = false) // was under way already
        assertEquals(40_000, gate.heldFor(poll)?.retryAfterMs)
    }

    @Test
    fun aClockSetBackEndsTheWait() {
        gate.refused(poll, retryAfterMs = 60_000, quota = true)
        now -= 3_600_000
        assertNull(gate.heldFor(poll), "or the wait would last an hour longer")
    }

    @Test
    fun aNewLoginLiftsEveryWait() {
        gate.refused(poll, retryAfterMs = 60_000_000, quota = true)
        gate.refused(search, retryAfterMs = 20_000, quota = false)
        gate.reset()
        assertNull(gate.heldFor(poll))
        assertNull(gate.heldFor(search))
        assertEquals(5_000, gate.refused(search, retryAfterMs = null, quota = false).retryAfterMs)
    }

    @Test
    fun theDetailSaysWhichCallWhyAndAfterHowMany() {
        repeat(20) { gate.sent() }
        now += 24 * 3_600_000L - 10_000 // those 20 are still within a day
        repeat(3) { gate.sent() }
        val detail = assertNotNull(gate.refused(poll, retryAfterMs = 61_887_000, quota = true).detail)
        assertEquals("QUOTA_EXCEEDED, $poll, Retry-After 61887 s, 3 calls in 30 s, 23 in 24 h", detail)

        now += 20_000 // now the first 20 are more than a day ago
        assertContains(assertNotNull(gate.refused(search, retryAfterMs = null, quota = false).detail), "HTTP 429, $search, no Retry-After, 3 calls in 30 s, 3 in 24 h")
    }

    @Test
    fun theDetailOfAHeldCallIsThatOfTheRefusal() {
        val refused = gate.refused(poll, retryAfterMs = 61_887_000, quota = true)
        now += 5_000
        assertEquals(refused.detail, gate.heldFor(poll)?.detail)
    }

    @Test
    fun theLimitHeldIsToldUntilItsEndpointTakesACallAgain() {
        assertNull(gate.limit.value)
        val refused = gate.refused(poll, retryAfterMs = 61_887_000, quota = true)
        assertEquals(refused.detail, gate.limit.value)
        gate.heldFor(search)
        gate.taken(search)
        assertEquals(refused.detail, gate.limit.value, "another endpoint says nothing about it")

        now += 61_887_000
        assertNull(gate.heldFor(poll))
        assertEquals(refused.detail, gate.limit.value, "not known to be over yet")
        gate.taken(poll)
        assertNull(gate.limit.value)
    }

    @Test
    fun aLimitNobodyTriesAgainIsOverAfterAWhile() {
        gate.refused(search, retryAfterMs = 20_000, quota = false)
        now += 139_999
        gate.heldFor(poll)
        assertNotNull(gate.limit.value)
        now += 1
        gate.heldFor(poll)
        assertNull(gate.limit.value)
    }

    @Test
    fun anAnswerToACallFromBeforeTheRefusalEndsNoWait() {
        gate.refused(search, retryAfterMs = 20_000, quota = false)
        gate.refused(poll, retryAfterMs = 61_887_000, quota = true)
        gate.taken(search)
        gate.taken(poll)
        assertNotNull(gate.heldFor(search))
        assertEquals(true, gate.heldFor(poll)?.quota)
    }
}
