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
 *   playing ([RequestEvent.Removed]: X, `!wrongsong`, `!remove`, blacklist sweep, end-of-stream clear).
 * - **A refund is final:** a redemption refunded in this run is never fulfilled afterwards, also when the streamer
 *   lets the rejected request in anyway and it plays (the viewer was told the points came back).
 *
 * Two parts, started separately by the wiring:
 * - [startLifecycle], **once when the app starts**, before anything can change the queue (before
 *   `PlaybackController.start()` and before the UI is shown), and [stopLifecycle] only at exit: it turns the
 *   controller's [RequestEvent]s into outbox entries, whatever the Twitch login, and also while Channel Points are
 *   switched off (the redemptions still waiting stay refundable). It needs no login: the outbox keeps the entries
 *   until it can send them.
 * - [startSession] / [stopSession] **with the Twitch session** (logged in, EventSub running): new redemptions and
 *   catch-up. [startSession] also starts the lifecycle part when it is not running yet; [stopSession] leaves it
 *   running.
 *
 * A directly linked reward that Requestify did not create (`cpx:` refs) never reaches the outbox (Twitch refuses its
 * updates), is not caught up (Twitch does not list it), and no reply claims a refund for it.
 *
 * Replies are plain chat messages (a redemption has no chat message to reply to) starting with "@name", saying it was
 * added or why not; nothing for a blocked viewer.
 *
 * **Catch-up** ([catchUp]: on [startSession] and after every fresh EventSub session, whose events in between are
 * lost) reads the reward's unfulfilled redemptions and settles each by what the app knows of it: queued, handed over
 * or in the outbox: left alone; playing or in history: fulfilled (unless refunded); known in this run (handled,
 * settled or sent) but gone from the queue without playing: refunded; otherwise a new redemption.
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
    private var lifecycle: Job? = null
    private var session: Job? = null

    /** One redemption or catch-up at a time. */
    private val turn = Mutex()

    /** Redemption ids handled, caught up or settled in this run. */
    private val known = RecentIds(KNOWN_IDS)

    /** Redemption ids refunded in this run: never fulfilled afterwards. */
    private val refunded = RecentIds(KNOWN_IDS)

    /**
     * Starts turning request events into outbox entries, for the whole app lifetime; does nothing when it runs
     * already. Call it once at app start, before anything can change the queue: the events do not replay.
     */
    fun startLifecycle() {
        synchronized(lock) { startLifecycleLocked() }
    }

    /** Only at app exit: request events after this are not settled (catch-up recovers them within this run). */
    fun stopLifecycle() {
        synchronized(lock) {
            lifecycle?.cancel()
            lifecycle = null
        }
    }

    /**
     * With the Twitch session: starts listening to redemptions, then catches up; also starts the lifecycle part when
     * it does not run yet. Safe before the controller has started or read its saved state: catch-up waits for it.
     */
    fun startSession() {
        synchronized(lock) {
            startLifecycleLocked()
            if (session?.isActive == true) return
            session = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Both flows are subscribed before startSession() returns: neither replays.
                val inbox = Channel<Work>(Channel.UNLIMITED)
                coroutineScope {
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

    /** The Twitch session ended: no redemptions or catch-ups until [startSession]. The lifecycle part keeps running. */
    fun stopSession() {
        synchronized(lock) {
            session?.cancel()
            session = null
        }
    }

    /** Caller holds [lock]. */
    private fun startLifecycleLocked() {
        if (lifecycle?.isActive == true) return
        lifecycle = scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { settle(it) } }
    }

    /** Settles the linked reward's unfulfilled redemptions (Requestify's own reward only); see the class comment. */
    suspend fun catchUp() {
        val channelPoints = settings.settings.value.twitch.channelPoints
        val rewardId = channelPoints.rewardId ?: return
        if (!channelPoints.managed) return
        turn.withLock {
            val waiting = when (val result = api.unfulfilledRedemptions(rewardId)) {
                is TwitchResult.Ok -> result.value
                else -> return // the next session start or fresh session tries again
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
            played -> fulfil(redemption.rewardId, id)
            // Known in this run and gone without playing: it left the queue while nobody listened, or Twitch's list
            // was read just before its refund landed. Refunding again is harmless; running it through the rules is not.
            id in known || outbox.wasSent(id) -> refund(redemption.rewardId, id)
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
        val refundedNow = managed && outcome is IntakeOutcome.Rejected
        if (refundedNow) refund(rewardId, redemptionId)
        replies.outcome(outcome, "@$userName ", refundedPoints = refundedNow)?.let { sender.send(it, replyTo = null) }
    }

    /** A request of Requestify's own reward started playing, or left the queue without playing. */
    private fun settle(event: RequestEvent) {
        try {
            val request = when (event) {
                is RequestEvent.Started -> event.request
                is RequestEvent.Removed -> event.request
            }
            val ref = ChannelPointsRef.parse(request.sourceRef)?.takeIf { it.managed } ?: return
            when (event) {
                is RequestEvent.Started -> fulfil(ref.rewardId, ref.redemptionId)
                is RequestEvent.Removed -> refund(ref.rewardId, ref.redemptionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // never let one event end the collector
        }
    }

    /** Every refund goes through here: it is final. */
    private fun refund(rewardId: String, redemptionId: String) {
        known.add(redemptionId)
        refunded.add(redemptionId)
        outbox.enqueue(rewardId, redemptionId, RedemptionStatus.CANCELED)
    }

    /** Every fulfil goes through here: never for a redemption refunded in this run. */
    private fun fulfil(rewardId: String, redemptionId: String) {
        known.add(redemptionId)
        if (redemptionId in refunded) return
        outbox.enqueue(rewardId, redemptionId, RedemptionStatus.FULFILLED)
    }

    private companion object {
        const val KNOWN_IDS = 1_000
    }
}
