package nl.diments.requestify.core.settings

import kotlinx.serialization.Serializable

/** The Twitch account the app is logged in to; saved with the settings (the tokens are in the secret store). */
@Serializable
data class TwitchAccount(
    val userId: String,
    val login: String,
    val displayName: String,
    val broadcasterType: String = "",
)

/** Who may use a chat command. Saved by name: never rename a value. */
@Serializable
enum class ChatPermission { Everyone, Followers, Subscribers, Vips, Moderators }

@Serializable
data class ChatCommandNames(
    val request: String = "sr",
    val song: String = "song",
    val queue: String = "queue",
    val wrongSong: String = "wrongsong",
    val skip: String = "skip",
    val remove: String = "remove",
    val blacklist: String = "blacklist",
)

@Serializable
data class ChannelPointsSettings(
    val enabled: Boolean = false,
    val rewardId: String? = null,
    val managed: Boolean = true,
    val title: String? = null,
    val cost: Int = 500,
    val prompt: String? = null,
    val rewardRemoved: Boolean = false,
)

@Serializable
data class TwitchSettings(
    val account: TwitchAccount? = null,
    val chatEnabled: Boolean = true,
    val chatPermission: ChatPermission = ChatPermission.Everyone,
    val commands: ChatCommandNames = ChatCommandNames(),
    val channelPoints: ChannelPointsSettings = ChannelPointsSettings(),
    val bitsEnabled: Boolean = true,
    val bitsMinimum: Int = 100,
    /** The optional bot account that sends the chat replies (plan 6); its login is in the secret store. */
    val bot: TwitchAccount? = null,
)
