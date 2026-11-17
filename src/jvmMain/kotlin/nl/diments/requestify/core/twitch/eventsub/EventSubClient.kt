package nl.diments.requestify.core.twitch.eventsub

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.diments.requestify.core.twitch.Reward
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult

/** What the app hears from Twitch over EventSub. */
sealed interface EventSubEvent {
    /**
     * A message in the broadcaster's chat. [badges] are the chatter's badge `set_id`s in this channel (a lead moderator
     * can show `lead_moderator` instead of `moderator`, a founder `founder` instead of `subscriber`).
     * [sourceBroadcasterId] is set when the message comes from another channel in a shared chat.
     */
    data class ChatMessage(
        val messageId: String,
        val chatterId: String,
        val chatterLogin: String,
        val chatterName: String,
        val text: String,
        val badges: Set<String>,
        val sourceBroadcasterId: String?,
    ) : EventSubEvent

    /**
     * A Channel Points redemption of a reward the client subscribed to; after a reward change that can still be the
     * earlier reward (see [EventSubClient.rewardChanged]).
     */
    data class Redemption(
        val id: String,
        val rewardId: String,
        val userId: String,
        val userLogin: String,
        val userName: String,
        val userInput: String,
    ) : EventSubEvent

    /**
     * [messageId] is the notification's id (a cheer has none of its own). [message] includes the cheermote text, e.g.
     * "Cheer100 song name". The user fields are null when [isAnonymous].
     */
    data class Cheer(
        val messageId: String,
        val isAnonymous: Boolean,
        val userId: String?,
        val userLogin: String?,
        val userName: String?,
        val message: String,
        val bits: Int,
    ) : EventSubEvent

    data object StreamOnline : EventSubEvent

    data object StreamOffline : EventSubEvent

    /** The reward was edited, paused or disabled (also in Twitch's dashboard). */
    data class RewardUpdated(val reward: Reward) : EventSubEvent

    /** The reward was deleted. */
    data class RewardRemoved(val rewardId: String) : EventSubEvent
}

sealed interface EventSubState {
    data object Stopped : EventSubState

    /** The first connection since [EventSubClient.start]. */
    data object Connecting : EventSubState

    /** Subscribed: events arrive. */
    data object Connected : EventSubState

    /** Without a working connection since [sinceEpochMs]; reconnecting. */
    data class Disconnected(val sinceEpochMs: Long) : EventSubState

    /** Twitch refused the login; nothing happens until [EventSubClient.start] is called again. */
    data object LoginNeeded : EventSubState
}

/**
 * The one EventSub WebSocket of the app, on the broadcaster's user token (API notes, section 4).
 *
 * - **Fresh session** (a new connection's welcome): subscribes chat, cheers, stream online/offline and, when [rewardId]
 *   gives one, the reward's redemptions, updates and removal. Then it is [EventSubState.Connected] and [freshSessions]
 *   fires: events from before are lost (Twitch does not replay them), so listeners re-sync over Helix. A subscription
 *   that fails for a passing reason (network, 5xx, 429) is retried first, 1 s doubling to 30 s.
 * - **`session_reconnect`**: connects to the given URL and keeps reading the old socket until the new one's welcome,
 *   then closes the old one. The subscriptions carry over: no resubscribe, no fresh session.
 * - **Keepalive**: no message for the welcome's `keepalive_timeout_seconds` + 5 s means a dead connection: a fresh one.
 * - **Backoff**: a dropped session reconnects at once; a connect that fails (no welcome within 15 s included) waits
 *   1 s, doubling to 30 s. A welcome resets it.
 * - **Dead login**: a subscribe answered with [TwitchResult.LoginNeeded] ends in [EventSubState.LoginNeeded], and the
 *   client stops until [start]. A revocation with `authorization_revoked` calls [onAuthorizationRevoked], once per
 *   session.
 * - **De-duplication**: Twitch delivers at least once; a notification whose `message_id` is among the last 500 is
 *   dropped.
 *
 * Without a broadcaster (logged out) it does not connect, and ends in [EventSubState.Stopped].
 */
class EventSubClient(
    private val connector: EventSubConnector,
    private val api: TwitchApi,
    private val broadcasterId: () -> String?,
    private val rewardId: () -> String?,
    private val onAuthorizationRevoked: () -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val url: String = DEFAULT_URL,
) {
    private val _events = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<EventSubEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<EventSubState>(EventSubState.Stopped)
    val state: StateFlow<EventSubState> = _state.asStateFlow()

    private val _freshSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires after a fresh session's subscriptions are in place: re-sync over REST, events in between were lost. */
    val freshSessions: SharedFlow<Unit> = _freshSessions.asSharedFlow()

    private val seen = RecentIds(SEEN_IDS)

    private val lock = Any()

    /** Bumped by [start] and [stop]; a run of an older generation no longer sets [state]. Guarded by [lock]. */
    private var generation = 0L

    /** Guarded by [lock]. */
    private var job: Job? = null

    /** The running client's reward-change signal. Guarded by [lock]. */
    private var rewardChanges: Channel<Unit>? = null

    /** Connects and keeps the connection; does nothing while already running. */
    fun start() {
        val launched = synchronized(lock) {
            if (job?.isCompleted == false) return
            val gen = ++generation
            val changes = Channel<Unit>(Channel.CONFLATED)
            rewardChanges = changes
            _state.value = EventSubState.Connecting
            scope.launch(start = CoroutineStart.LAZY) { keepConnected(gen, changes) }.also { job = it }
        }
        launched.start()
    }

    /** Disconnects; the sockets are closed in the background. */
    fun stop() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
            rewardChanges = null
            _state.value = EventSubState.Stopped
        }
    }

    /**
     * Subscribes the reward types for [rewardId]'s current id on the live session. The subscriptions of an earlier
     * reward stay until the next fresh session, so listeners compare reward ids. Without a live session the next fresh
     * session subscribes it.
     */
    fun rewardChanged() {
        synchronized(lock) { rewardChanges }?.trySend(Unit)
    }

    private fun setState(gen: Long, state: EventSubState) = synchronized(lock) {
        if (gen == generation) _state.value = state
    }

    private suspend fun keepConnected(gen: Long, rewardChanges: ReceiveChannel<Unit>) {
        var backoffMs = FIRST_BACKOFF_MS
        var downSince: Long? = null
        while (true) {
            if (account() == null) {
                setState(gen, EventSubState.Stopped)
                return
            }
            val opened = open(url)
            if (opened == null) {
                val since = downSince ?: clock()
                downSince = since
                setState(gen, EventSubState.Disconnected(since))
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            backoffMs = FIRST_BACKOFF_MS
            when (val end = Session(gen, opened, rewardChanges).run()) {
                is SessionEnd.Dropped -> {
                    val since = if (end.wasConnected) clock() else downSince ?: clock()
                    downSince = since
                    setState(gen, EventSubState.Disconnected(since))
                }
                SessionEnd.LoginNeeded -> {
                    setState(gen, EventSubState.LoginNeeded)
                    return
                }
                SessionEnd.NoAccount -> {
                    setState(gen, EventSubState.Stopped)
                    return
                }
            }
        }
    }

    /** Connects to [url] and waits for the welcome; null (with the socket closed) when that does not work out. */
    private suspend fun open(url: String): Opened? {
        var socket: EventSubSocket? = null
        var handedOver = false
        try {
            val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                val connected = connector.connect(url)
                socket = connected
                awaitWelcome(connected)
            }
            handedOver = opened != null
            return opened
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive() // only this client's own cancellation ends the run
            return null
        } catch (_: Exception) {
            return null
        } finally {
            if (!handedOver) socket?.let { closeQuietly(it) }
        }
    }

    private suspend fun awaitWelcome(socket: EventSubSocket): Opened? {
        while (true) {
            val text = socket.incoming.receiveCatching().getOrNull() ?: return null
            val welcome = EventSubParser.parse(text) as? EventSubMessage.Welcome ?: continue
            val keepaliveS = (welcome.keepaliveTimeoutSeconds ?: DEFAULT_KEEPALIVE_S)
                .coerceIn(MIN_KEEPALIVE_S, MAX_KEEPALIVE_S)
            return Opened(socket, welcome.sessionId, keepaliveS * 1_000L + KEEPALIVE_MARGIN_MS)
        }
    }

    private fun account(): String? = broadcasterId()?.takeIf { it.isNotBlank() }

    /** One session, from a fresh welcome until it ends, across `session_reconnect` moves. */
    private inner class Session(
        private val gen: Long,
        first: Opened,
        private val rewardChanges: ReceiveChannel<Unit>,
    ) {
        private var current = first
        private var currentOpen = true
        private var move: Move? = null
        private var revocationReported = false

        /** The id to subscribe on; read by the subscriber while the session loop moves sockets. */
        @Volatile
        private var sessionId = first.sessionId
        private val connected = AtomicBoolean(false)
        private val ended = CompletableDeferred<SessionEnd>()

        suspend fun run(): SessionEnd = coroutineScope {
            val subscriber = launch { keepSubscribed() }
            try {
                readUntilEnd(this)
            } finally {
                withContext(NonCancellable) {
                    subscriber.cancelAndJoin()
                    move?.discard()
                    closeQuietly(current.socket)
                }
            }
        }

        /** Reads the current socket; the keepalive watch restarts with every message. */
        private suspend fun readUntilEnd(scope: CoroutineScope): SessionEnd {
            while (true) {
                when (val step = withTimeoutOrNull(current.watchMs) { next() }) {
                    null -> return SessionEnd.Dropped(connected.get()) // silent for too long
                    is Step.End -> return step.end
                    is Step.Message -> handle(step.text)?.let { reconnectUrl ->
                        if (move == null) move = scope.startMove(reconnectUrl)
                    }
                    Step.Closed -> if (move != null) {
                        currentOpen = false // Twitch let go of the old socket; the move can still work out
                    } else {
                        return SessionEnd.Dropped(connected.get())
                    }
                    is Step.Moved -> {
                        val next = step.opened ?: return SessionEnd.Dropped(connected.get())
                        val old = current.socket
                        if (currentOpen) drain(old)
                        current = next
                        currentOpen = true
                        move = null
                        sessionId = next.sessionId
                        closeQuietly(old)
                    }
                }
            }
        }

        private suspend fun next(): Step = select {
            ended.onAwait { Step.End(it) }
            if (currentOpen) {
                current.socket.incoming.onReceiveCatching { result ->
                    result.getOrNull()?.let { Step.Message(it) } ?: Step.Closed
                }
            }
            move?.let { m -> m.result.onAwait { Step.Moved(it) } }
        }

        /** Handles one message; returns the URL to move to when Twitch asks for a reconnect. */
        private suspend fun handle(text: String): String? {
            when (val message = EventSubParser.parse(text)) {
                is EventSubMessage.Notification -> if (seen.firstTime(message.messageId)) {
                    message.event?.let { _events.emit(it) }
                }
                is EventSubMessage.Revocation -> if (seen.firstTime(message.messageId) &&
                    message.status == AUTHORIZATION_REVOKED && !revocationReported
                ) {
                    revocationReported = true
                    try {
                        onAuthorizationRevoked()
                    } catch (_: Exception) {
                        // The listener's trouble; the session goes on.
                    }
                }
                is EventSubMessage.Reconnect -> return message.reconnectUrl
                is EventSubMessage.Welcome, EventSubMessage.Keepalive, EventSubMessage.Unknown -> Unit
            }
            return null
        }

        /** What the old socket received before the new welcome still counts. */
        private suspend fun drain(socket: EventSubSocket) {
            while (true) {
                val text = socket.incoming.tryReceive().getOrNull() ?: return
                handle(text)
            }
        }

        private fun CoroutineScope.startMove(url: String): Move {
            val result = CompletableDeferred<Opened?>()
            return Move(launch { result.complete(open(url)) }, result)
        }

        private suspend fun keepSubscribed() {
            do { // stale reward changes: the fresh subscriptions read the current reward id
                val stale = rewardChanges.tryReceive()
            } while (stale.isSuccess)
            val broadcaster = account() ?: return end(SessionEnd.NoAccount)
            when (subscribeAll(freshSubscriptions(broadcaster, reward()))) {
                Pass.Done -> Unit
                Pass.LoginNeeded -> return end(SessionEnd.LoginNeeded)
                Pass.NoAccount -> return end(SessionEnd.NoAccount)
            }
            connected.set(true)
            setState(gen, EventSubState.Connected)
            _freshSessions.emit(Unit)
            while (true) {
                rewardChanges.receive()
                val owner = account() ?: continue
                val reward = reward() ?: continue
                when (subscribeAll(rewardSubscriptions(owner, reward))) {
                    Pass.Done -> Unit
                    Pass.LoginNeeded -> return end(SessionEnd.LoginNeeded)
                    Pass.NoAccount -> return end(SessionEnd.NoAccount)
                }
            }
        }

        private fun end(why: SessionEnd) {
            ended.complete(why)
        }

        private suspend fun subscribeAll(subscriptions: List<Subscription>): Pass {
            var pending = subscriptions
            var retryMs = FIRST_BACKOFF_MS
            while (true) {
                val again = mutableListOf<Subscription>()
                var waitMs = retryMs
                for (subscription in pending) {
                    when (val result = subscribe(subscription)) {
                        is TwitchResult.Ok -> Unit
                        TwitchResult.LoginNeeded -> return Pass.LoginNeeded
                        TwitchResult.LoggedOut -> return Pass.NoAccount
                        is TwitchResult.RateLimited -> {
                            again += subscription
                            waitMs = maxOf(waitMs, result.retryAfterMs)
                        }
                        is TwitchResult.Failed -> again += subscription
                        // A bad request or a missing scope stays that way: retrying cannot help.
                        is TwitchResult.BadRequest, is TwitchResult.Forbidden, TwitchResult.NotFound -> Unit
                    }
                }
                if (again.isEmpty()) return Pass.Done
                delay(waitMs)
                retryMs = (retryMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                pending = again
            }
        }

        private suspend fun subscribe(subscription: Subscription): TwitchResult<Unit> = try {
            api.subscribe(subscription.type, VERSION, subscription.condition, sessionId)
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive() // only this client's own cancellation ends the session
            TwitchResult.Failed("cancelled")
        }
    }

    private fun reward(): String? = rewardId()?.takeIf { it.isNotBlank() }

    private fun freshSubscriptions(broadcaster: String, reward: String?): List<Subscription> = buildList {
        add(Subscription(CHAT_MESSAGE, mapOf(BROADCASTER_USER_ID to broadcaster, USER_ID to broadcaster)))
        add(Subscription(CHEER, mapOf(BROADCASTER_USER_ID to broadcaster)))
        add(Subscription(STREAM_ONLINE, mapOf(BROADCASTER_USER_ID to broadcaster)))
        add(Subscription(STREAM_OFFLINE, mapOf(BROADCASTER_USER_ID to broadcaster)))
        if (reward != null) addAll(rewardSubscriptions(broadcaster, reward))
    }

    private fun rewardSubscriptions(broadcaster: String, reward: String): List<Subscription> =
        listOf(REDEMPTION_ADD, REWARD_UPDATE, REWARD_REMOVE).map {
            Subscription(it, mapOf(BROADCASTER_USER_ID to broadcaster, REWARD_ID to reward))
        }

    private class Opened(val socket: EventSubSocket, val sessionId: String, val watchMs: Long)

    /** A move to a reconnect URL: [result] is the new socket after its welcome, or null when that failed. */
    private class Move(private val job: Job, val result: CompletableDeferred<Opened?>) {
        /** Gives up the move; a socket it already opened is closed. */
        suspend fun discard() {
            job.cancelAndJoin()
            if (!result.complete(null)) result.await()?.let { closeQuietly(it.socket) }
        }
    }

    private class Subscription(val type: String, val condition: Map<String, String>)

    private sealed interface SessionEnd {
        data class Dropped(val wasConnected: Boolean) : SessionEnd

        data object LoginNeeded : SessionEnd

        data object NoAccount : SessionEnd
    }

    private enum class Pass { Done, LoginNeeded, NoAccount }

    private sealed interface Step {
        data class Message(val text: String) : Step

        data object Closed : Step

        data class Moved(val opened: Opened?) : Step

        data class End(val end: SessionEnd) : Step
    }

    /** The last [capacity] notification ids. */
    private class RecentIds(private val capacity: Int) {
        private val ids = LinkedHashSet<String>()

        /** True when [id] is not among the last [capacity] ids; it is then remembered. */
        @Synchronized
        fun firstTime(id: String): Boolean {
            if (!ids.add(id)) return false
            if (ids.size > capacity) ids.remove(ids.first())
            return true
        }
    }

    companion object {
        /** Twitch's default keepalive is 10 s; 30 s gives more time to subscribe after the welcome. */
        const val DEFAULT_URL = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30"

        private const val VERSION = "1"
        private const val BROADCASTER_USER_ID = "broadcaster_user_id"
        private const val USER_ID = "user_id"
        private const val REWARD_ID = "reward_id"
        private const val AUTHORIZATION_REVOKED = "authorization_revoked"
        private const val FIRST_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val KEEPALIVE_MARGIN_MS = 5_000L
        private const val DEFAULT_KEEPALIVE_S = 10
        private const val MIN_KEEPALIVE_S = 10
        private const val MAX_KEEPALIVE_S = 600
        private const val SEEN_IDS = 500
        private const val EVENT_BUFFER = 64

        private suspend fun closeQuietly(socket: EventSubSocket) {
            withContext(NonCancellable) {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Already gone.
                }
            }
        }
    }
}
