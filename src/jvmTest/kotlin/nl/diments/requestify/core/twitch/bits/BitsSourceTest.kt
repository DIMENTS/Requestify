package nl.diments.requestify.core.twitch.bits

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.twitch.chat.TwitchTestRig
import nl.diments.requestify.core.twitch.chat.awaitUntil
import nl.diments.requestify.core.twitch.chat.settle
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class BitsSourceTest {
    private lateinit var dir: Path
    private lateinit var savedLocale: Locale

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("requestify-bits")
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(savedLocale)
        dir.deleteRecursively()
    }

    private val idA = "A".repeat(22)
    private fun link(id: String) = "https://open.spotify.com/track/$id"

    private class Harness(val rig: TwitchTestRig, val bits: BitsSource) {
        val sends get() = rig.twitch.sends

        fun cheer(
            id: String,
            message: String,
            bits: Int = 100,
            anonymous: Boolean = false,
            viewer: String = "u1",
        ) {
            rig.events.tryEmit(
                if (anonymous) {
                    EventSubEvent.Cheer(id, true, null, null, null, message, bits)
                } else {
                    EventSubEvent.Cheer(id, false, viewer, "login_$viewer", "Name $viewer", message, bits)
                },
            )
        }
    }

    private fun TestScope.harness(): Harness {
        val rig = TwitchTestRig(this, dir)
        rig.catalog.tracks[idA] = rig.track(idA, "Alpha")
        val bits = BitsSource(
            events = rig.events,
            intake = rig.intake,
            settings = rig.settings,
            replies = rig.replies,
            sender = rig.sender,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        rig.sender.start()
        bits.start()
        runCurrent()
        return Harness(rig, bits)
    }

    @Test
    fun cheerWithTrackLinkIsPaidRequest() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer100 ${link(idA)} please", bits = 250)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val added = h.rig.queued.single()
        assertEquals("Alpha", added.track.name)
        assertEquals("c1", added.id)
        assertEquals(RequestSource.BITS, added.source)
        assertEquals(Payment.Bits(250), added.payment)
        assertEquals("Name u1", added.requester.displayName)
        assertEquals("login_u1", added.requester.login)
        assertEquals("u1", added.requester.twitchUserId)
        val reply = h.sends.single()
        assertNull(reply.replyTo)
        assertTrue(reply.message.startsWith("@Name u1 "), reply.message)
        assertTrue("Alpha" in reply.message && "#1" in reply.message, reply.message)
    }

    @Test
    fun cheerWithoutLinkIgnored() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer100 great stream, play something nice")
        h.cheer("c2", "Cheer1", bits = 1)

        settle()
        assertTrue(h.sends.isEmpty())
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.rig.rejectedEntries.isEmpty())
        assertTrue(h.rig.catalog.calls.isEmpty())
    }

    @Test
    fun cheerWithShortLinkIsFollowedAndQueued() = runTest {
        val h = harness()
        h.rig.shortLinks.leadsTo("https://spotify.link/abc", "${link(idA)}?si=x")
        h.cheer("c1", "Cheer100 spotify.link/abc", bits = 100)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertEquals("Alpha", h.rig.queued.single().track.name)
        assertEquals(Payment.Bits(100), h.rig.queued.single().payment)
        assertTrue("Alpha" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun cheerWithUnresolvableShortLinkIsListedAndAnswered() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer100 https://spotify.link/nowhere", bits = 100)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val entry = h.rig.rejectedEntries.single()
        assertEquals("c1", entry.id)
        assertEquals(RejectReason.ShortLinkUnresolved, entry.reason)
        assertEquals(Payment.Bits(100), entry.payment)
        assertTrue(h.rig.queued.isEmpty())
        assertEquals("@Name u1 Use the open.spotify.com link.", h.sends.single().message)
    }

    @Test
    fun belowMinimumIsRejectedPaidAndListed() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer50 ${link(idA)}", bits = 50)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val entry = h.rig.rejectedEntries.single()
        assertEquals("c1", entry.id)
        assertEquals(RejectReason.BelowMinimum(100), entry.reason)
        assertEquals(Payment.Bits(50), entry.payment)
        assertEquals(RequestSource.BITS, entry.source)
        assertTrue(h.rig.queued.isEmpty())
        assertEquals("Alpha", entry.request?.track?.name, "listed with its track, so it can be added anyway")
        val reply = h.sends.single()
        assertNull(reply.replyTo)
        assertTrue(reply.message.startsWith("@Name u1 "), reply.message)
        assertTrue("100 Bits" in reply.message, reply.message)

        h.rig.intake.addAnyway("c1")
        awaitUntil("added anyway") { h.rig.queued.isNotEmpty() }
        assertEquals(Payment.Bits(50), h.rig.queued.single().payment)
        assertTrue(h.rig.rejectedEntries.isEmpty())
    }

    @Test
    fun belowMinimumWhileSpotifyIsDownIsStillListed() = runTest {
        val h = harness()
        h.rig.catalog.failure = SpotifyResult.Failed("boom")
        h.cheer("c1", "Cheer50 ${link(idA)}", bits = 50)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val entry = h.rig.rejectedEntries.single()
        assertEquals(RejectReason.BelowMinimum(100), entry.reason)
        assertNull(entry.request, "no track to add: the row offers Dismiss")
        assertTrue("100 Bits" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun albumLinkIsRejectedPaid() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer100 https://open.spotify.com/album/$idA", bits = 100)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val entry = h.rig.rejectedEntries.single()
        assertEquals(RejectReason.NotATrack("album"), entry.reason)
        assertEquals(Payment.Bits(100), entry.payment)
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.sends.single().message.startsWith("@Name u1 "), h.sends.single().message)
    }

    @Test
    fun anonymousCheerHasNoNameInReply() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer100 ${link(idA)}", anonymous = true)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val added = h.rig.queued.single()
        assertEquals("Anonymous", added.requester.displayName)
        assertNull(added.requester.twitchUserId)
        assertNull(added.requester.login)
        val reply = h.sends.single()
        assertFalse("@" in reply.message, reply.message)
        assertFalse("Anonymous" in reply.message, reply.message)
        assertTrue("Alpha" in reply.message, reply.message)
    }

    @Test
    fun anonymousBelowMinimumIsListedAndAnswered() = runTest {
        val h = harness()
        h.cheer("c1", "Cheer10 ${link(idA)}", bits = 10, anonymous = true)

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertEquals("Anonymous", h.rig.rejectedEntries.single().requester.displayName)
        assertFalse("@" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun switchedOffIgnoresCheers() = runTest {
        val h = harness()
        h.rig.settings.update { it.copy(twitch = it.twitch.copy(bitsEnabled = false)) }
        h.cheer("c1", "Cheer100 ${link(idA)}")

        settle()
        assertTrue(h.sends.isEmpty())
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.rig.rejectedEntries.isEmpty())
    }

    @Test
    fun spotifyDownCheerWaitsThenRepliesWhenQueued() = runTest {
        val h = harness()
        h.rig.catalog.failure = SpotifyResult.Failed("boom")
        h.cheer("c1", "Cheer100 ${link(idA)}")

        awaitUntil("waiting for Spotify") { h.rig.catalog.calls.isNotEmpty() }
        settle()
        assertTrue(h.sends.isEmpty()) // nothing to say yet
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.rig.rejectedEntries.isEmpty())

        h.rig.catalog.failure = null
        advanceTimeBy(5_001)
        awaitUntil("reply once queued") { h.sends.isNotEmpty() }
        assertEquals("Alpha", h.rig.queued.single().track.name)
        val reply = h.sends.single()
        assertNull(reply.replyTo)
        assertTrue(reply.message.startsWith("@Name u1 "), reply.message)
        assertTrue("Alpha" in reply.message, reply.message)
    }

    @Test
    fun stopEndsTheSource() = runTest {
        val h = harness()
        h.bits.stop()
        runCurrent()
        h.cheer("c1", "Cheer100 ${link(idA)}")

        settle()
        assertTrue(h.sends.isEmpty())
        assertTrue(h.rig.queued.isEmpty())
    }
}
