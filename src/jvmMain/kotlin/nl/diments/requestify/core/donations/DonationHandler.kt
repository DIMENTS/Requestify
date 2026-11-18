package nl.diments.requestify.core.donations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.diments.requestify.core.model.IgnoredReason
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyInput
import nl.diments.requestify.core.spotify.classifySpotifyInput
import nl.diments.requestify.core.twitch.chat.ChatReplies
import nl.diments.requestify.core.twitch.chat.ChatReplySender
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.donation_anonymous
import org.jetbrains.compose.resources.getString

/**
 * Turns a [Donation] from either platform into a paid, link-only request, the same way for both:
 * - one already here ([RequestIntake.isKnown]: a catch-up seeing it again) changes nothing;
 * - no Spotify link: not a request, ignored (the intake reports it for the Log), nothing said;
 * - a currency the minimum cannot be compared with, or an amount below the minimum: listed in Rejected with its track,
 *   so the streamer can still "Add anyway" (paid, never lost);
 * - otherwise queued with paid priority.
 * A short chat message from the broadcaster says what happened, only while Twitch is connected, and never starts with
 * the donor's name ([ChatReplies.donationOutcome]).
 */
class DonationHandler(
    private val intake: RequestIntake,
    private val settings: SettingsStore,
    private val replies: ChatReplies,
    private val sender: ChatReplySender,
    private val twitchConnected: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private var job: Job? = null

    /** Replies to donations that waited for Spotify once they settle. */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                intake.pendingResolved.collect { (incoming, outcome) ->
                    if (incoming.source == RequestSource.DONOLINK || incoming.source == RequestSource.STREAMELEMENTS) {
                        replySafely(incoming.requester.displayName, outcome)
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    /**
     * What became of [d]; null when it was here already. Returns only once that is on disk, so the source saves its
     * cursor past [d] only then ([RequestIntake.awaitSaved]).
     */
    suspend fun handle(d: Donation): IntakeOutcome? = decide(d).also { intake.awaitSaved() }

    private suspend fun decide(d: Donation): IntakeOutcome? {
        if (intake.isKnown(d.id)) return null
        val currency = d.currency.uppercase()
        val incoming = incomingOf(d)
        val input = classifySpotifyInput(d.message)
        if (input is SpotifyInput.SearchText || input is SpotifyInput.Empty) return intake.submit(incoming)

        val (minimum, minimumCurrency) = minimumFor(d.source, currency)
        val outcome = when {
            currency != minimumCurrency -> intake.rejectBeforeRules(incoming, RejectReason.OtherCurrency(currency))
            d.amountCents < minimum -> intake.rejectBeforeRules(incoming, RejectReason.BelowMinimumAmount(minimum, minimumCurrency))
            else -> intake.submit(incoming)
        }
        replySafely(incoming.requester.displayName, outcome)
        return outcome
    }

    /** A tip StreamElements rejected or failed: no request, but the Log shows it arrived ([reason]). */
    suspend fun ignore(d: Donation, reason: IgnoredReason) {
        intake.recordIgnored(incomingOf(d), reason)
    }

    private suspend fun incomingOf(d: Donation) = IncomingRequest(
        id = d.id,
        input = d.message,
        linkOnly = true,
        requester = Requester(cleanName(d.donorName) ?: getString(Res.string.donation_anonymous)),
        source = d.source,
        payment = Payment.Money(d.amountCents, d.currency.uppercase()),
        receivedAtEpochMs = clock(),
        occurredAtEpochMs = d.occurredAtEpochMs,
    )

    /** The minimum in cents and its currency: DonoLink is euros, StreamElements the channel's currency. */
    private fun minimumFor(source: RequestSource, currency: String): Pair<Long, String> {
        val s = settings.settings.value.donations
        return if (source == RequestSource.STREAMELEMENTS) {
            s.streamElementsMinimumCents to (s.streamElementsCurrency?.uppercase() ?: currency)
        } else {
            s.donoLinkMinimumCents to DONOLINK_CURRENCY
        }
    }

    private suspend fun replySafely(donorName: String, outcome: IntakeOutcome) {
        if (!twitchConnected.value) return
        try {
            replies.donationOutcome(outcome, donorName)?.let { sender.send(it, replyTo = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // a reply that cannot be made never loses the request
        }
    }

    /**
     * A donor's name as it may be shown and said in chat: free text, so without "@" (the broadcaster's account would
     * tag whoever has that name) and without control characters (a new line could start a command), on one line. Null
     * when nothing is left.
     */
    private fun cleanName(name: String?): String? = name
        ?.replace("@", "")
        ?.replace(Regex("\\p{Cntrl}+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val DONOLINK_CURRENCY = "EUR"
    }
}
