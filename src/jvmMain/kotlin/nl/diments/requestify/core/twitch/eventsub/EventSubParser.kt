package nl.diments.requestify.core.twitch.eventsub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import nl.diments.requestify.core.storage.AppJson
import nl.diments.requestify.core.twitch.Reward

internal const val CHAT_MESSAGE = "channel.chat.message"
internal const val CHEER = "channel.cheer"
internal const val STREAM_ONLINE = "stream.online"
internal const val STREAM_OFFLINE = "stream.offline"
internal const val REDEMPTION_ADD = "channel.channel_points_custom_reward_redemption.add"
internal const val REWARD_UPDATE = "channel.channel_points_custom_reward.update"
internal const val REWARD_REMOVE = "channel.channel_points_custom_reward.remove"

/** One message on the EventSub WebSocket. */
internal sealed interface EventSubMessage {
    data class Welcome(val sessionId: String, val keepaliveTimeoutSeconds: Int?) : EventSubMessage

    data object Keepalive : EventSubMessage

    /** Move to [reconnectUrl], unmodified: the session and its subscriptions go along. */
    data class Reconnect(val reconnectUrl: String) : EventSubMessage

    /** [event] is null for a subscription type the app does not use, or an event it cannot read. */
    data class Notification(
        val messageId: String,
        val subscriptionType: String,
        val event: EventSubEvent?,
    ) : EventSubMessage

    /** Twitch ended a subscription; [status] is e.g. `authorization_revoked`, `user_removed` or `version_removed`. */
    data class Revocation(val messageId: String, val subscriptionType: String, val status: String) : EventSubMessage

    /** Unreadable, or a message type the app does not know. */
    data object Unknown : EventSubMessage
}

/**
 * Reads EventSub messages (API notes, sections 4, 6, 10 and 11). Never throws: what it cannot read is
 * [EventSubMessage.Unknown], or a notification without an event.
 *
 * - Chat: [EventSubEvent.ChatMessage.badges] are the `set_id`s of the chatter's badges in this channel (`badges`, not
 *   the shared-chat `source_badges`), e.g. `lead_moderator` and `founder`.
 * - Redemptions are passed on whatever their `status`, which EventSub writes in lowercase (`unfulfilled`), unlike
 *   Helix: one that is already `fulfilled` (a reward that skips the request queue) was still paid for.
 */
internal object EventSubParser {
    private val json = AppJson.instance

    fun parse(text: String): EventSubMessage = try {
        read(json.decodeFromString<Envelope>(text))
    } catch (_: Exception) {
        EventSubMessage.Unknown
    }

    private fun read(envelope: Envelope): EventSubMessage {
        val metadata = envelope.metadata
        return when (metadata.messageType) {
            "session_welcome" -> {
                val session = json.decodeFromJsonElement<SessionPayload>(envelope.payload).session
                EventSubMessage.Welcome(session.id, session.keepaliveTimeoutSeconds)
            }
            "session_keepalive" -> EventSubMessage.Keepalive
            "session_reconnect" -> json.decodeFromJsonElement<SessionPayload>(envelope.payload).session.reconnectUrl
                ?.let { EventSubMessage.Reconnect(it) }
                ?: EventSubMessage.Unknown
            "notification" -> {
                val id = metadata.messageId ?: return EventSubMessage.Unknown
                val payload = json.decodeFromJsonElement<SubscriptionPayload>(envelope.payload)
                val type = payload.subscription.type
                EventSubMessage.Notification(id, type, payload.event?.let { eventOf(type, id, it) })
            }
            "revocation" -> {
                val id = metadata.messageId ?: return EventSubMessage.Unknown
                val subscription = json.decodeFromJsonElement<SubscriptionPayload>(envelope.payload).subscription
                EventSubMessage.Revocation(id, subscription.type, subscription.status.orEmpty())
            }
            else -> EventSubMessage.Unknown
        }
    }

    private fun eventOf(type: String, notificationId: String, event: JsonObject): EventSubEvent? = try {
        when (type) {
            CHAT_MESSAGE -> json.decodeFromJsonElement<ChatMessageDto>(event).toEvent()
            CHEER -> json.decodeFromJsonElement<CheerDto>(event).toEvent(notificationId)
            REDEMPTION_ADD -> json.decodeFromJsonElement<RedemptionDto>(event).toEvent()
            REWARD_UPDATE -> EventSubEvent.RewardUpdated(json.decodeFromJsonElement<RewardDto>(event).toReward())
            REWARD_REMOVE -> EventSubEvent.RewardRemoved(json.decodeFromJsonElement<RewardDto>(event).id)
            STREAM_ONLINE -> EventSubEvent.StreamOnline
            STREAM_OFFLINE -> EventSubEvent.StreamOffline
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    @Serializable
    private class Envelope(val metadata: Metadata = Metadata(), val payload: JsonObject = JsonObject(emptyMap()))

    @Serializable
    private class Metadata(
        @SerialName("message_id") val messageId: String? = null,
        @SerialName("message_type") val messageType: String? = null,
    )

    @Serializable
    private class SessionPayload(val session: SessionDto)

    @Serializable
    private class SessionDto(
        val id: String,
        @SerialName("keepalive_timeout_seconds") val keepaliveTimeoutSeconds: Int? = null,
        @SerialName("reconnect_url") val reconnectUrl: String? = null,
    )

    @Serializable
    private class SubscriptionPayload(val subscription: SubscriptionDto, val event: JsonObject? = null)

    @Serializable
    private class SubscriptionDto(val type: String, val status: String? = null)

    @Serializable
    private class ChatMessageDto(
        @SerialName("message_id") val messageId: String,
        @SerialName("chatter_user_id") val chatterUserId: String,
        @SerialName("chatter_user_login") val chatterUserLogin: String,
        @SerialName("chatter_user_name") val chatterUserName: String,
        val message: ChatTextDto,
        val badges: List<BadgeDto>? = null,
        @SerialName("source_broadcaster_user_id") val sourceBroadcasterUserId: String? = null,
    ) {
        fun toEvent() = EventSubEvent.ChatMessage(
            messageId = messageId,
            chatterId = chatterUserId,
            chatterLogin = chatterUserLogin,
            chatterName = chatterUserName,
            text = message.text.orEmpty(),
            badges = badges.orEmpty().map { it.setId }.toSet(),
            sourceBroadcasterId = sourceBroadcasterUserId,
        )
    }

    @Serializable
    private class ChatTextDto(val text: String? = null)

    @Serializable
    private class BadgeDto(@SerialName("set_id") val setId: String)

    @Serializable
    private class CheerDto(
        @SerialName("is_anonymous") val isAnonymous: Boolean = false,
        @SerialName("user_id") val userId: String? = null,
        @SerialName("user_login") val userLogin: String? = null,
        @SerialName("user_name") val userName: String? = null,
        val message: String? = null,
        val bits: Int,
    ) {
        fun toEvent(notificationId: String) = EventSubEvent.Cheer(
            messageId = notificationId,
            isAnonymous = isAnonymous,
            userId = userId,
            userLogin = userLogin,
            userName = userName,
            message = message.orEmpty(),
            bits = bits,
        )
    }

    @Serializable
    private class RedemptionDto(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("user_login") val userLogin: String,
        @SerialName("user_name") val userName: String,
        @SerialName("user_input") val userInput: String? = null,
        val reward: RewardRefDto,
    ) {
        fun toEvent() = EventSubEvent.Redemption(
            id = id,
            rewardId = reward.id,
            userId = userId,
            userLogin = userLogin,
            userName = userName,
            userInput = userInput.orEmpty(),
        )
    }

    @Serializable
    private class RewardRefDto(val id: String)

    @Serializable
    private class RewardDto(
        val id: String,
        val title: String = "",
        val cost: Int = 0,
        val prompt: String? = null,
        @SerialName("is_enabled") val isEnabled: Boolean = true,
        @SerialName("is_paused") val isPaused: Boolean = false,
        @SerialName("is_user_input_required") val isUserInputRequired: Boolean = false,
        @SerialName("should_redemptions_skip_request_queue") val skipsRequestQueue: Boolean = false,
    ) {
        fun toReward() =
            Reward(id, title, cost, prompt.orEmpty(), isEnabled, isPaused, isUserInputRequired, skipsRequestQueue)
    }
}
