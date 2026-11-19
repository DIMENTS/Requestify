package nl.diments.requestify.core.twitch.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.diments.requestify.core.twitch.DropReason

/**
 * Whether the channel's moderation holds the message back ([reason]): AutoMod holding it for review, or Twitch
 * checking it against the channel's moderation settings (`msg_rejected*`). Such a message is never sent a second time
 * as the streamer: AutoMod may still let it through (a duplicate), and the broadcaster would bypass the moderation.
 */
fun isHeldByModeration(reason: DropReason): Boolean =
    reason.code.contains("automod", ignoreCase = true) ||
        reason.code.startsWith("msg_rejected") ||
        reason.message.contains("automod", ignoreCase = true)

/**
 * One sender's chat messages that Twitch did not send. [dropping] holds the latest reason from the first drop until a
 * message is sent again (one Log row for the whole stretch); [inARow] counts the drops since the last sent message
 * that the channel's moderation did not hold back (those neither count nor reset it), and [countedReason] is the
 * reason of the latest of those (what the channel holds the sender back for, never a moderation hold).
 */
class DropTracker {
    private val lock = Any()
    private val _dropping = MutableStateFlow<String?>(null)
    private val _inARow = MutableStateFlow(0)
    private val _countedReason = MutableStateFlow<String?>(null)

    val dropping: StateFlow<String?> = _dropping.asStateFlow()
    val inARow: StateFlow<Int> = _inARow.asStateFlow()
    val countedReason: StateFlow<String?> = _countedReason.asStateFlow()

    fun sent() = synchronized(lock) {
        _dropping.value = null
        _inARow.value = 0
        _countedReason.value = null
    }

    fun dropped(reason: DropReason) = synchronized(lock) {
        val text = reason.message.ifBlank { reason.code }
        if (!isHeldByModeration(reason)) {
            _inARow.value += 1
            _countedReason.value = text
        }
        _dropping.value = text
    }

    /** Forgets every drop (a bot connected or disconnected). */
    fun reset() = sent()
}
