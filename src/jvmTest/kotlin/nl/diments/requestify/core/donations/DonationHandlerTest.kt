package nl.diments.requestify.core.donations

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.twitch.chat.TwitchTestRig
import nl.diments.requestify.core.twitch.chat.awaitUntil
import nl.diments.requestify.core.twitch.chat.settle

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class DonationHandlerTest {
    private lateinit var dir: Path
    private lateinit var savedLocale: Locale

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("requestify-donations")
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

    private val twitch = MutableStateFlow(true)

    private class Harness(val rig: TwitchTestRig, val handler: DonationHandler) {
        val sends get() = rig.twitch.sends
    }

    private fun TestScope.harness(): Harness {
        val rig = TwitchTestRig(this, dir)
        rig.catalog.tracks[idA] = rig.track(idA, "Alpha")
        val handler = DonationHandler(
            intake = rig.intake,
            settings = rig.settings,
            replies = rig.replies,
            sender = rig.sender,
            twitchConnected = twitch,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        rig.sender.start()
        handler.start()
        settle()
        return Harness(rig, handler)
    }

    private fun donation(
        id: String = "dl-1",
        message: String = "voor jou ${link(idA)}",
        cents: Long = 300,
        currency: String = "EUR",
        name: String? = "Jan",
        source: RequestSource = RequestSource.DONOLINK,
    ) = Donation(id, source, name, message, cents, currency, occurredAtEpochMs = 0)

    /** Hands [d] over and waits for the answer (Spotify, Twitch and strings answer on real threads). */
    private fun TestScope.handled(h: Harness, d: Donation): IntakeOutcome? {
        val result = CompletableDeferred<IntakeOutcome?>()
        backgroundScope.launch { result.complete(h.handler.handle(d)) }
        awaitUntil("the donation handled") { result.isCompleted }
        return result.getCompleted()
    }

    @Test
    fun aDonationWithALinkIsQueuedAsPaid() = runTest {
        val h = harness()
        val out = handled(h, donation())

        assertIs<IntakeOutcome.Accepted>(out)
        val queued = h.rig.queued.single()
        assertEquals("dl-1", queued.id)
        assertEquals(RequestSource.DONOLINK, queued.source)
        assertEquals(Payment.Money(300, "EUR"), queued.payment)
        assertEquals("Jan", queued.requester.displayName)
        assertEquals(null, queued.requester.twitchUserId, "a donor is no Twitch identity")
        awaitUntil("the reply") { h.sends.isNotEmpty() }
        assertTrue(h.sends.single().message.startsWith("Donation request from Jan: "), h.sends.single().message)
        assertNull(h.sends.single().replyTo)
    }

    @Test
    fun withoutALinkItIsIgnoredSilently() = runTest {
        val h = harness()
        assertEquals(IntakeOutcome.Ignored, handled(h, donation(message = "bedankt voor de stream!")))
        settle()
        assertTrue(h.rig.queued.isEmpty())
        assertTrue(h.rig.rejectedEntries.isEmpty())
        assertTrue(h.sends.isEmpty())
    }

    @Test
    fun belowTheMinimumIsListedWithItsTrack() = runTest {
        val h = harness()
        h.rig.settings.tryUpdate { it.copy(donations = it.donations.copy(donoLinkMinimumCents = 500)) }
        val out = handled(h, donation(cents = 300))

        assertEquals(RejectReason.BelowMinimumAmount(500, "EUR"), assertIs<IntakeOutcome.Rejected>(out).reason)
        val entry = h.rig.rejectedEntries.single()
        assertEquals("Alpha", entry.request?.track?.name, "listed with its track")
        h.rig.intake.addAnyway(entry.id)
        awaitUntil("added anyway") { h.rig.queued.isNotEmpty() }
        assertEquals(Payment.Money(300, "EUR"), h.rig.queued.single().payment)
    }

    @Test
    fun anotherCurrencyIsListed() = runTest {
        val h = harness()
        val out = handled(h, donation(currency = "usd"))

        assertEquals(RejectReason.OtherCurrency("USD"), assertIs<IntakeOutcome.Rejected>(out).reason)
        assertTrue(out.listed)
        assertEquals(Payment.Money(300, "USD"), h.rig.rejectedEntries.single().payment)
    }

    @Test
    fun streamElementsComparesInTheChannelCurrency() = runTest {
        val h = harness()
        h.rig.settings.tryUpdate { it.copy(donations = it.donations.copy(streamElementsMinimumCents = 200, streamElementsCurrency = "USD")) }
        val ok = handled(h, donation(id = "se-1", currency = "USD", cents = 250, source = RequestSource.STREAMELEMENTS))
        val low = handled(h, donation(id = "se-2", currency = "USD", cents = 150, source = RequestSource.STREAMELEMENTS))

        assertIs<IntakeOutcome.Accepted>(ok)
        assertEquals(RejectReason.BelowMinimumAmount(200, "USD"), assertIs<IntakeOutcome.Rejected>(low).reason)
    }

    @Test
    fun aDonationSeenTwiceIsQueuedOnce() = runTest {
        val h = harness()
        assertIs<IntakeOutcome.Accepted>(handled(h, donation()))
        assertNull(handled(h, donation()), "already here: nothing happens")
        settle()
        assertEquals(1, h.rig.queued.size)
        awaitUntil("the reply") { h.sends.isNotEmpty() }
        settle()
        assertEquals(1, h.sends.size)
    }

    @Test
    fun noReplyWithoutTwitch() = runTest {
        twitch.value = false
        val h = harness()
        assertIs<IntakeOutcome.Accepted>(handled(h, donation()))
        settle()
        assertTrue(h.sends.isEmpty())
    }

    @Test
    fun aPendingDonationRepliesWhenItSettles() = runTest {
        val h = harness()
        h.rig.catalog.failure = SpotifyResult.Failed("down")
        assertEquals(IntakeOutcome.Pending, handled(h, donation()))
        settle()
        assertTrue(h.sends.isEmpty(), "nothing to say yet")

        h.rig.catalog.failure = null
        advanceTimeBy(5_001)
        awaitUntil("the reply once it settled") { h.sends.isNotEmpty() }
        assertTrue(h.sends.single().message.startsWith("Donation request from Jan: "), h.sends.single().message)
        assertEquals(1, h.rig.queued.size)
    }

    @Test
    fun anEmptyNameIsAnonymous() = runTest {
        val h = harness()
        handled(h, donation(name = "   "))
        assertEquals("Anonymous", h.rig.queued.single().requester.displayName)
        handled(h, donation(id = "dl-2", name = null))
        assertEquals("Anonymous", h.rig.queued.last().requester.displayName)
    }

    @Test
    fun donorNamesAreCleaned() = runTest {
        val h = harness()
        handled(h, donation(id = "dl-1", name = "@victim"))
        handled(h, donation(id = "dl-2", name = "Jan\n!skip\t\u0007"))
        handled(h, donation(id = "dl-3", name = " @@ "))

        assertEquals(listOf("victim", "Jan !skip", "Anonymous"), h.rig.queued.map { it.requester.displayName })
        awaitUntil("the replies") { h.sends.size == 3 }
        for (reply in h.sends.map { it.message }) {
            assertTrue('@' !in reply, "no one is tagged: $reply")
            assertTrue('\n' !in reply, "one line: $reply")
        }
    }
}
