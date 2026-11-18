package nl.diments.requestify.core.twitch.chat

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult

/**
 * Sends the app's chat replies one at a time. A rate-limited send waits as long as Twitch says and tries again; a reply
 * that is still not sent after [maxTries] tries, or that Twitch refuses, is dropped (chat is ephemeral, and the
 * request itself is unaffected). It remembers the ids of the last 100 messages it sent, so the chat reader can skip them.
 * Replies queued before the first [start] wait for it; [stop] drops what is still unsent.
 */
class ChatReplySender(
    private val api: TwitchApi,
    private val scope: CoroutineScope,
    private val maxTries: Int = 3,
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
            repeat(maxTries) { attempt ->
                val result = api.sendChat(reply.text, reply.replyTo)
                when (result) {
                    is TwitchResult.Ok -> {
                        remember(result.value.messageId)
                        return
                    }
                    is TwitchResult.RateLimited -> if (attempt < maxTries - 1) delay(result.retryAfterMs)
                    else -> return // refused or not reachable: trying again would not help
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // never let one reply end the sender
        }
    }

    private fun remember(id: String) = synchronized(lock) {
        sentIds.addLast(id)
        while (sentIds.size > OWN_IDS) sentIds.removeFirst()
    }

    private companion object {
        const val OWN_IDS = 100
    }
}
