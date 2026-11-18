package nl.diments.requestify.core.twitch.bits

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.bits_anonymous
import org.jetbrains.compose.resources.getString

/**
 * Turns `channel.cheer` events into paid requests while Bits are switched on. Only a cheer with a Spotify link in its
 * message is a request (Bits are link-only; anything else is a plain cheer and ignored). With fewer Bits than the
 * minimum it is a rejected paid request: it goes to the Rejected list and the cheerer is told in chat.
 *
 * Replies are plain chat messages (not threaded: a cheer has no chat message to reply to), starting with "@name"; an
 * anonymous cheerer is shown as "Anonymous" and addressed by no name. A request that had to wait for Spotify is
 * answered when it settles (`pendingResolved`), the others at once. Cheers are handled one at a time, in order.
 */
class BitsSource(
    private val events: Flow<EventSubEvent>,
    private val intake: RequestIntake,
    private val settings: SettingsStore,
    private val replies: ChatReplies,
    private val sender: ChatReplySender,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    constructor(
        eventSub: EventSubClient,
        intake: RequestIntake,
        settings: SettingsStore,
        replies: ChatReplies,
        sender: ChatReplySender,
        scope: CoroutineScope,
        clock: () -> Long,
    ) : this(eventSub.events, intake, settings, replies, sender, scope, clock)

    private val lock = Any()
    private var job: Job? = null

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Both are subscribed before anything can arrive or settle (neither flow replays).
                val inbox = Channel<EventSubEvent.Cheer>(Channel.UNLIMITED)
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        intake.pendingResolved.collect { (incoming, outcome) -> settled(incoming, outcome) }
                    }
                    launch { for (cheer in inbox) handleSafely(cheer) }
                    try {
                        events.collect { event -> if (event is EventSubEvent.Cheer) inbox.trySend(event) }
                    } finally {
                        inbox.close()
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

    private suspend fun handleSafely(cheer: EventSubEvent.Cheer) {
        try {
            handle(cheer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // one broken cheer never ends the source
        }
    }

    private suspend fun handle(cheer: EventSubEvent.Cheer) {
        val twitch = settings.settings.value.twitch
        if (!twitch.bitsEnabled) return
        // Bits are link-only: a cheer without a Spotify link is just a cheer (a spotify.link short link counts).
        val input = classifySpotifyInput(cheer.message)
        if (input is SpotifyInput.SearchText || input is SpotifyInput.Empty) return

        val incoming = IncomingRequest(
            id = cheer.messageId,
            input = cheer.message,
            linkOnly = true,
            requester = requesterOf(cheer),
            source = RequestSource.BITS,
            payment = Payment.Bits(cheer.bits),
            receivedAtEpochMs = clock(),
        )
        val outcome = if (cheer.bits < twitch.bitsMinimum) {
            // Listed with its track when the link resolves, so the streamer can still "Add anyway".
            intake.rejectBeforeRules(incoming, RejectReason.BelowMinimum(twitch.bitsMinimum))
        } else {
            intake.submit(incoming)
        }
        reply(incoming, outcome)
    }

    /** A paid request that waited for Spotify has settled. */
    private suspend fun settled(incoming: IncomingRequest, outcome: IntakeOutcome) {
        if (incoming.source != RequestSource.BITS) return
        try {
            reply(incoming, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // never let one reply end the collector
        }
    }

    private suspend fun reply(incoming: IncomingRequest, outcome: IntakeOutcome) {
        // A cheerer with no id is the anonymous one: addressed by no name.
        val mention = incoming.requester.takeIf { it.twitchUserId != null }?.let { "@${it.displayName} " }
        replies.outcome(outcome, mention, refundedPoints = false)?.let { sender.send(it, replyTo = null) }
    }

    private suspend fun requesterOf(cheer: EventSubEvent.Cheer): Requester {
        val id = cheer.userId
        return if (cheer.isAnonymous || id == null) {
            Requester(getString(Res.string.bits_anonymous))
        } else {
            Requester(cheer.userName ?: cheer.userLogin ?: id, cheer.userLogin, id)
        }
    }
}
