package nl.diments.requestify.core.twitch.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.twitch.DropReason

class DropTrackerTest {
    private val followersOnly = DropReason("followers_only_mode", "Followers only")

    @Test
    fun aDropOpensUntilAMessageIsSent() {
        val drops = DropTracker()
        drops.dropped(followersOnly)
        assertEquals("Followers only", drops.dropping.value)
        assertEquals(1, drops.inARow.value)

        drops.sent()
        assertEquals(null, drops.dropping.value)
        assertEquals(0, drops.inARow.value)
    }

    @Test
    fun moderationHoldsNeitherCountNorReset() {
        val drops = DropTracker()
        drops.dropped(followersOnly)
        drops.dropped(DropReason("automod_held", "Held for review"))
        drops.dropped(followersOnly)
        assertEquals(2, drops.inARow.value)
        assertEquals("Followers only", drops.dropping.value)
    }

    @Test
    fun aReasonWithoutWordsShowsItsCode() {
        val drops = DropTracker()
        drops.dropped(DropReason("slow_mode", ""))
        assertEquals("slow_mode", drops.dropping.value)
    }

    @Test
    fun isHeldByModerationCases() {
        assertTrue(isHeldByModeration(DropReason("automod_held", "")))
        assertTrue(isHeldByModeration(DropReason("msg_rejected", "")))
        assertTrue(isHeldByModeration(DropReason("msg_rejected_mandatory", "")))
        assertTrue(isHeldByModeration(DropReason("x", "Held by AutoMod")))
        assertFalse(isHeldByModeration(followersOnly))
        assertFalse(isHeldByModeration(DropReason("msg_duplicate", "Duplicate message.")))
    }

    @Test
    fun aHoldDoesNotChangeTheCountedReason() {
        val drops = DropTracker()
        drops.dropped(followersOnly)
        drops.dropped(DropReason("automod_held", "Held for review"))
        assertEquals("Held for review", drops.dropping.value, "the Log shows the latest")
        assertEquals("Followers only", drops.countedReason.value, "a block names what the channel holds the bot back for")
        drops.sent()
        assertEquals(null, drops.countedReason.value)
    }
}
