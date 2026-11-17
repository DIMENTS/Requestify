package nl.diments.requestify.core.twitch.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

/**
 * Turns the broadcaster's chat into requests and answers: `!sr`, `!song`, `!queue` and `!wrongsong`, each answered as
 * a reply to the viewer's message through [sender]. The moderator commands are parsed but not handled yet.
 *
 * Ignored: everything while chat is switched off, messages from another channel in a shared chat (their
 * `sourceBroadcasterId` is not the broadcaster's), and the app's own replies.
 *
 * `!sr` needs the role of the permission level (a higher role always may). The Followers level also lets every
 * subscriber, VIP, moderator and the broadcaster in; a plain viewer is looked up once with [TwitchApi.follows], and a
 * follower stays a follower for the session. A failed lookup counts as not following and is asked again next time.
 * A refused viewer gets a short reply and no entry in the Rejected list.
 */
class TwitchChat(
    private val events: Flow<EventSubEvent>,
    private val intake: RequestIntake,
    private val controller: PlaybackController,
    private val api: TwitchApi,
    private val settings: SettingsStore,
    private val replies: ChatReplies,
    private val sender: ChatReplySender,
    private val broadcasterId: () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    constructor(
        eventSub: EventSubClient,
        intake: RequestIntake,
        controller: PlaybackController,
        api: TwitchApi,
        settings: SettingsStore,
        replies: ChatReplies,
        sender: ChatReplySender,
        broadcasterId: () -> String?,
        scope: CoroutineScope,
        clock: () -> Long,
    ) : this(eventSub.events, intake, controller, api, settings, replies, sender, broadcasterId, scope, clock)

    private val lock = Any()
    private var job: Job? = null

    /** What the follower lookup answered per viewer id, for this session. */
    private val followers = ConcurrentHashMap<String, Boolean>()
    private val followerCheck = Mutex()

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            followers.clear()
            job = scope.launch {
                // The collector only hands messages over, so the event source is never held up; one worker handles
                // them one at a time in the order they came in (first come, first served, also for the replies).
                val inbox = Channel<EventSubEvent.ChatMessage>(Channel.UNLIMITED)
                coroutineScope {
                    launch { for (message in inbox) handleSafely(message) }
                    try {
                        events.collect { event -> if (event is EventSubEvent.ChatMessage) inbox.trySend(event) }
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

    private suspend fun handleSafely(message: EventSubEvent.ChatMessage) {
        try {
            handle(message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // one broken message never ends the chat
        }
    }

    private suspend fun handle(message: EventSubEvent.ChatMessage) {
        val twitch = settings.settings.value.twitch
        if (!twitch.chatEnabled) return
        if (message.sourceBroadcasterId != null && message.sourceBroadcasterId != broadcasterId()) return
        if (sender.isOwnMessage(message.messageId)) return
        val command = parseChatCommand(message.text, twitch.commands) ?: return
        when (command) {
            is ChatCommand.Request -> request(message, command, twitch.chatPermission, twitch.commands.request)
            ChatCommand.Song -> reply(message, replies.song(controller.snapshot(), null))
            ChatCommand.Queue -> reply(message, replies.queue(controller.snapshot(), message.chatterId, null))
            ChatCommand.WrongSong -> wrongSong(message)
            // Moderator commands are handled elsewhere (later); nothing is said for them here.
            ChatCommand.Skip, is ChatCommand.Remove, ChatCommand.Blacklist -> Unit
        }
    }

    private suspend fun request(
        message: EventSubEvent.ChatMessage,
        command: ChatCommand.Request,
        permission: ChatPermission,
        commandName: String,
    ) {
        if (!mayRequest(message, permission)) {
            replies.denied(permission, null)?.let { reply(message, it) }
            return
        }
        if (command.input.isBlank()) {
            reply(message, replies.usage(commandName, null))
            return
        }
        val outcome = intake.submit(
            IncomingRequest(
                id = message.messageId,
                input = command.input,
                linkOnly = false,
                requester = Requester(message.chatterName, message.chatterLogin, message.chatterId),
                source = RequestSource.CHAT,
                payment = null,
                receivedAtEpochMs = clock(),
                replyTo = message.messageId,
            ),
        )
        replies.outcome(outcome, mention = null, refundedPoints = false)?.let { reply(message, it) }
    }

    private suspend fun mayRequest(message: EventSubEvent.ChatMessage, permission: ChatPermission): Boolean {
        val role = roleOf(message.badges)
        return when (permission) {
            ChatPermission.Everyone -> true
            ChatPermission.Followers -> role != ChatRole.Viewer || follows(message.chatterId)
            ChatPermission.Subscribers -> role >= ChatRole.Subscriber
            ChatPermission.Vips -> role >= ChatRole.Vip
            ChatPermission.Moderators -> role >= ChatRole.Moderator
        }
    }

    private suspend fun follows(userId: String): Boolean {
        followers[userId]?.let { return it }
        return followerCheck.withLock {
            followers[userId]?.let { return@withLock it }
            when (val answer = api.follows(userId)) {
                is TwitchResult.Ok -> answer.value.also { followers[userId] = it }
                else -> false // a failed lookup counts as not following, and is asked again next time
            }
        }
    }

    /** Removes the asker's newest queued request (never one handed over: the controller offers only queued ones). */
    private suspend fun wrongSong(message: EventSubEvent.ChatMessage) {
        val removed = controller.removeMatching(RemoveReason.WrongSong) { queue ->
            val newest = queue.withIndex()
                .filter { it.value.requester.twitchUserId == message.chatterId }
                .maxWithOrNull(compareBy({ it.value.createdAtEpochMs }, { it.index }))
            listOfNotNull(newest?.value)
        }
        reply(message, replies.wrongSong(removed.firstOrNull(), null))
    }

    private fun reply(message: EventSubEvent.ChatMessage, text: String) = sender.send(text, message.messageId)
}
