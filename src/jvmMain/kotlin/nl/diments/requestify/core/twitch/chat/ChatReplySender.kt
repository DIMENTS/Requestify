package nl.diments.requestify.core.twitch.chat

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.DropReason
import nl.diments.requestify.core.twitch.SentChat
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.bot.TwitchBot

/**
 * Sends the app's chat replies one at a time: as the [bot] while one is connected, else as the streamer ([api]).
 *
 * - A rate-limited send waits as long as Twitch says and tries again; a reply that is still not sent after [maxTries]
 *   tries, or that cannot reach Twitch, is dropped (chat is ephemeral, and the request itself is unaffected).
 * - When the bot cannot send (its login refused or gone, the channel does not let it talk, or Twitch did not send the
 *   message for a reason other than the channel's moderation), the same reply goes out as the streamer at once:
 *   viewers always get an answer. A message the moderation holds back is not sent again (it may still appear).
 * - What Twitch did not send is counted per sender: the bot's in [TwitchBot.drops], the streamer's in [streamerDrops].
 *
 * It remembers the ids of the last 100 messages it sent, so the chat reader can skip them. Replies queued before the
 * first [start] wait for it; [stop] drops what is still unsent.
 */
class ChatReplySender(
    private val api: TwitchApi,
    private val scope: CoroutineScope,
    private val maxTries: Int = 3,
    private val bot: TwitchBot? = null,
    val streamerDrops: DropTracker = DropTracker(),
) {
    private class Reply(val text: String, val replyTo: String?)

    private val outbox = Channel<Reply>(Channel.UNLIMITED)
    private val sentIds = ArrayDeque<String>()
    private val lock = Any()
    private var job: Job? = null

    /** Queues [text]; it is sent after the replies queued before it. */
    fun send(text: String, replyTo: String?) {
        outbox.trySend(Reply(text, replyTo))
    }

    /** True for a message this sender sent among the last 100. */
    fun isOwnMessage(messageId: String): Boolean = synchronized(lock) { messageId in sentIds }

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch {
                for (reply in outbox) deliver(reply)
            }
        }
    }

    /** Stops sending and drops the replies not sent yet: after a new login they would answer an old session. */
    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            do {
                val dropped = outbox.tryReceive().isSuccess
            } while (dropped)
        }
    }

    private suspend fun deliver(reply: Reply) {
        try {
            val bot = bot
            val account = bot?.sender()
            if (bot != null && account != null && !asBot(bot, account, reply)) return
            asStreamer(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // never let one reply end the sender
        }
    }

    /** Sends [reply] as the bot; true when it must go out as the streamer instead. */
    private suspend fun asBot(bot: TwitchBot, account: TwitchAccount, reply: Reply): Boolean =
        when (val result = attempt(bot.api, reply, account.userId)) {
            is TwitchResult.Ok -> {
                val sent = result.value
                if (sent.isSent) {
                    remember(sent.messageId)
                    bot.onSent()
                    false
                } else {
                    val reason = sent.dropReason ?: UNKNOWN_DROP
                    bot.onDropped(reason)
                    !isHeldByModeration(reason)
                }
            }
            TwitchResult.LoginNeeded -> {
                bot.onLoginRefused()
                true
            }
            TwitchResult.LoggedOut -> true
            is TwitchResult.Forbidden -> {
                bot.onForbidden(result.message)
                true
            }
            // Still rate-limited, or Twitch cannot be reached: the streamer would fare no better. Dropped, as always.
            else -> false
        }

    private suspend fun asStreamer(reply: Reply) {
        val result = attempt(api, reply, senderId = null)
        if (result !is TwitchResult.Ok) return // refused or not reachable: trying again would not help
        val sent = result.value
        if (sent.isSent) {
            remember(sent.messageId)
            streamerDrops.sent()
        } else {
            streamerDrops.dropped(sent.dropReason ?: UNKNOWN_DROP)
        }
    }

    /** Sends [reply] as [senderId] (null: the broadcaster), waiting out rate limits up to [maxTries] tries. */
    private suspend fun attempt(api: TwitchApi, reply: Reply, senderId: String?): TwitchResult<SentChat> {
        var tries = 0
        while (true) {
            val result = api.sendChat(reply.text, reply.replyTo, senderId)
            tries++
            if (result !is TwitchResult.RateLimited || tries >= maxTries) return result
            delay(result.retryAfterMs)
        }
    }

    private fun remember(id: String) = synchronized(lock) {
        sentIds.addLast(id)
        while (sentIds.size > OWN_IDS) sentIds.removeFirst()
    }

    private companion object {
        const val OWN_IDS = 100

        /** Twitch said the message was not sent, without saying why. */
        val UNKNOWN_DROP = DropReason("unknown", "")
    }
}
