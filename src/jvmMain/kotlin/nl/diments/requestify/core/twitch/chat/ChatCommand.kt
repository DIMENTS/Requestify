package nl.diments.requestify.core.twitch.chat

import nl.diments.requestify.core.settings.ChatCommandNames

sealed interface ChatCommand {
    /** `!sr <input>`; [input] is empty when the viewer gave none. */
    data class Request(val input: String) : ChatCommand

    data object Song : ChatCommand

    data object Queue : ChatCommand

    data object WrongSong : ChatCommand

    data object Skip : ChatCommand

    /** `!remove <n>`; [position] is null without a number. */
    data class Remove(val position: Int?) : ChatCommand

    data object Blacklist : ChatCommand
}

/**
 * The command a chat message starts with: `!` and a configured name, case-insensitive, as a whole first word. Text
 * that only contains a command further on is not a command.
 */
fun parseChatCommand(text: String, names: ChatCommandNames): ChatCommand? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("!")) return null
    val word = trimmed.substring(1).takeWhile { !it.isWhitespace() }
    if (word.isEmpty()) return null
    val rest = trimmed.substring(1 + word.length).trim()
    fun named(name: String) = name.isNotBlank() && word.equals(name.trim().removePrefix("!"), ignoreCase = true)
    return when {
        named(names.request) -> ChatCommand.Request(rest)
        named(names.song) -> ChatCommand.Song
        named(names.queue) -> ChatCommand.Queue
        named(names.wrongSong) -> ChatCommand.WrongSong
        named(names.skip) -> ChatCommand.Skip
        named(names.remove) ->
            ChatCommand.Remove(rest.takeWhile { !it.isWhitespace() }.removePrefix("#").toIntOrNull())
        named(names.blacklist) -> ChatCommand.Blacklist
        else -> null
    }
}

/** What a chatter is in the broadcaster's channel, from their badges; a higher role may do what a lower one may. */
enum class ChatRole { Viewer, Subscriber, Vip, Moderator, Broadcaster }

fun roleOf(badges: Set<String>): ChatRole = when {
    "broadcaster" in badges -> ChatRole.Broadcaster
    "moderator" in badges || "lead_moderator" in badges -> ChatRole.Moderator
    "vip" in badges -> ChatRole.Vip
    "subscriber" in badges || "founder" in badges -> ChatRole.Subscriber
    else -> ChatRole.Viewer
}
