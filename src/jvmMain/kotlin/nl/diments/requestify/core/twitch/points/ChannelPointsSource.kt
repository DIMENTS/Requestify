package nl.diments.requestify.core.twitch.points

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.IntakeOutcome
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.ChannelPointsRef
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.RequestEvent
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.twitch.Redemption
import nl.diments.requestify.core.twitch.RedemptionStatus
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.chat.ChatReplies
import nl.diments.requestify.core.twitch.chat.ChatReplySender
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

/**
 * Turns Channel Points redemptions of the linked reward into requests while Channel Points are switched on, and
 * settles each redemption of Requestify's own reward ([ChannelPointsRef.managed]) through the [outbox]:
 *
 * - **Unfulfilled** while its request waits, also while it is handed over to Spotify (it can still come back).
 * - **Fulfilled** when it starts playing ([RequestEvent.Started]).
 * - **Canceled** (refunded) when it is rejected (closed requests included), and when it leaves the queue without
 *   playing ([RequestEvent.Removed]: X, `!wrongsong`, `!remove`, blacklist sweep, end-of-stream clear). Its lifecycle
 *   runs on while Channel Points are switched off: only new redemptions are ignored then.
 *
 * A directly linked reward that Requestify did not create (`cpx:` refs) never reaches the outbox (Twitch refuses its
 * updates), is not caught up (Twitch does not list it), and no reply claims a refund for it.
 *
 * Replies are plain chat messages (a redemption has no chat message to reply to) starting with "@name", saying it was
 * added or why not; nothing for a blocked viewer.
 *
 * **Catch-up** ([catchUp]: on [start] and after every fresh EventSub session, whose events in between are lost) reads
 * the reward's unfulfilled redemptions and settles each by what the app knows of it: queued, handed over or in the
 * outbox: left alone; playing or in history: fulfilled; handled earlier in this run but gone from the queue without
 * playing (it left while nobody listened): refunded; otherwise a new redemption.
 *
 * Redemptions and catch-ups are handled one at a time, in order, and each redemption once.
 */
class ChannelPointsSource(
    private val events: Flow<EventSubEvent>,
    private val freshSessions: Flow<Unit>,
    private val intake: RequestIntake,
    private val controller: PlaybackController,
    private val api: TwitchApi,
    private val outbox: RedemptionOutbox,
    private val settings: SettingsStore,
    private val replies: ChatReplies,
    private val sender: ChatReplySender,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    constructor(
        eventSub: EventSubClient,
        intake: RequestIntake,
        controller: PlaybackController,
        api: TwitchApi,
        outbox: RedemptionOutbox,
        settings: SettingsStore,
        replies: ChatReplies,
        sender: ChatReplySender,
        scope: CoroutineScope,
        clock: () -> Long,
    ) : this(eventSub.events, eventSub.freshSessions, intake, controller, api, outbox, settings, replies, sender, scope, clock)

    private sealed interface Work {
        data class Redeem(val redemption: EventSubEvent.Redemption) : Work
        data object CatchUp : Work
    }

    private val lock = Any()
    private var job: Job? = null

    /** One redemption or catch-up at a time. */
    private val turn = Mutex()

    /** Redemption ids handled or seen as requests in this run. Guarded by [turn]. */
    private val known = RecentIds(KNOWN_IDS)

    /**
     * Starts listening, then catches up. Safe before the controller has started or read its saved state: the
     * request events are subscribed to before anything can happen, and catch-up waits for the saved state.
     */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Every flow is subscribed before start() returns: none of them replays.
                val inbox = Channel<Work>(Channel.UNLIMITED)
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { settle(it) } }
                    launch(start = CoroutineStart.UNDISPATCHED) { freshSessions.collect { inbox.trySend(Work.CatchUp) } }
                    inbox.trySend(Work.CatchUp)
                    launch { for (work in inbox) doSafely(work) }
                    try {
                        events.collect { event -> if (event is EventSubEvent.Redemption) inbox.trySend(Work.Redeem(event)) }
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

    /** Settles the linked reward's unfulfilled redemptions (Requestify's own reward only); see the class comment. */
    suspend fun catchUp() {
        val channelPoints = settings.settings.value.twitch.channelPoints
        val rewardId = channelPoints.rewardId ?: return
        if (!channelPoints.managed) return
        turn.withLock {
            val waiting = when (val result = api.unfulfilledRedemptions(rewardId)) {
                is TwitchResult.Ok -> result.value
                else -> return // the next start or fresh session tries again
            }
            for (redemption in waiting) caughtUp(redemption)
        }
    }

    private suspend fun doSafely(work: Work) {
        try {
            when (work) {
                is Work.Redeem -> turn.withLock { redeemed(work.redemption) }
                Work.CatchUp -> catchUp()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // one broken redemption never ends the source; catch-up settles it later
        }
    }

    /** Caller holds [turn]. */
    private suspend fun redeemed(event: EventSubEvent.Redemption) {
        val channelPoints = settings.settings.value.twitch.channelPoints
        if (!channelPoints.enabled || channelPoints.rewardId == null || event.rewardId != channelPoints.rewardId) return
        if (!known.add(event.id)) return
        submit(event.id, event.rewardId, event.userId, event.userLogin, event.userName, event.userInput, channelPoints.managed)
    }

    /** One unfulfilled redemption of Requestify's own reward. Caller holds [turn]. */
    private suspend fun caughtUp(redemption: Redemption) {
        val id = redemption.id
        val snapshot = controller.loadedSnapshot()
        val waits = (snapshot.queue + listOfNotNull(snapshot.handedOver)).any { isFor(it, id) }
        val played = snapshot.playing?.let { isFor(it, id) } == true || snapshot.history.any { isFor(it.request, id) }
        when {
            waits || outbox.entries.value.any { it.redemptionId == id } -> known.add(id)
            played -> {
                known.add(id)
                outbox.enqueue(redemption.rewardId, id, RedemptionStatus.FULFILLED)
            }
            // Handled in this run, and gone without playing: it left the queue while nobody was listening.
            id in known -> outbox.enqueue(redemption.rewardId, id, RedemptionStatus.CANCELED)
            !settings.settings.value.twitch.channelPoints.enabled -> Unit // new, but switched off: it waits
            else -> {
                known.add(id)
                submit(id, redemption.rewardId, redemption.userId, redemption.userLogin, redemption.userName,
                    redemption.userInput, managed = true)
            }
        }
    }

    private fun isFor(request: Request, redemptionId: String) =
        ChannelPointsRef.parse(request.sourceRef)?.redemptionId == redemptionId

    /** A new redemption: through the rules, refunded when rejected (own reward only), and answered in chat. */
    private suspend fun submit(
        redemptionId: String,
        rewardId: String,
        userId: String,
        userLogin: String,
        userName: String,
        userInput: String,
        managed: Boolean,
    ) {
        val incoming = IncomingRequest(
            id = redemptionId,
            input = userInput,
            linkOnly = false,
            requester = Requester(userName, userLogin, userId),
            source = RequestSource.CHANNEL_POINTS,
            payment = null,
            sourceRef = ChannelPointsRef(rewardId, redemptionId, managed).format(),
            receivedAtEpochMs = clock(),
        )
        val outcome = intake.submit(incoming)
        val refunded = managed && outcome is IntakeOutcome.Rejected
        if (refunded) outbox.enqueue(rewardId, redemptionId, RedemptionStatus.CANCELED)
        replies.outcome(outcome, "@$userName ", refundedPoints = refunded)?.let { sender.send(it, replyTo = null) }
    }

    /** A request of Requestify's own reward started playing, or left the queue without playing. */
    private fun settle(event: RequestEvent) {
        try {
            val (request, status) = when (event) {
                is RequestEvent.Started -> event.request to RedemptionStatus.FULFILLED
                is RequestEvent.Removed -> event.request to RedemptionStatus.CANCELED
            }
            val ref = ChannelPointsRef.parse(request.sourceRef)?.takeIf { it.managed } ?: return
            outbox.enqueue(ref.rewardId, ref.redemptionId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // never let one event end the collector
        }
    }

    /** The last [capacity] ids, oldest dropped first. */
    private class RecentIds(private val capacity: Int) {
        private val ids = LinkedHashSet<String>()

        operator fun contains(id: String) = id in ids

        /** False when [id] is known already. */
        fun add(id: String): Boolean {
            if (!ids.add(id)) return false
            if (ids.size > capacity) ids.remove(ids.first())
            return true
        }
    }

    private companion object {
        const val KNOWN_IDS = 1_000
    }
}
