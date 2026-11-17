package nl.diments.requestify.core.twitch.eventsub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import nl.diments.requestify.core.twitch.Reward

class EventSubParserTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/twitch/eventsub/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }

    private fun parse(name: String): EventSubMessage = EventSubParser.parse(fixture(name))

    private fun notification(name: String): EventSubMessage.Notification = assertIs(parse(name))

    @Test
    fun welcomeCarriesTheSessionAndItsKeepalive() {
        val welcome = assertIs<EventSubMessage.Welcome>(parse("welcome.json"))
        assertEquals("AQoQILE98gtqShGmLD7AM6yJThAB", welcome.sessionId)
        assertEquals(30, welcome.keepaliveTimeoutSeconds)
    }

    @Test
    fun keepaliveIsRecognised() {
        assertEquals(EventSubMessage.Keepalive, parse("keepalive.json"))
    }

    @Test
    fun reconnectCarriesTheUrlUnmodified() {
        val reconnect = assertIs<EventSubMessage.Reconnect>(parse("reconnect.json"))
        assertEquals(
            "wss://eventsub.wss.twitch.tv/ws?reconnect=AQoQILE98gtqShGmLD7AM6yJThAB&challenge=7f3a9c",
            reconnect.reconnectUrl,
        )
    }

    @Test
    fun revocationCarriesItsStatus() {
        val revocation = assertIs<EventSubMessage.Revocation>(parse("revocation.json"))
        assertEquals("84c1e79a-2a4b-4c13-ba0b-4312293e9310", revocation.messageId)
        assertEquals("channel.chat.message", revocation.subscriptionType)
        assertEquals("authorization_revoked", revocation.status)
    }

    @Test
    fun chatMessageReadsLeadModeratorAndFounderBadges() {
        val note = notification("chat-message.json")
        assertEquals("befa7b53-d79d-478f-86b9-120f112b044e", note.messageId)
        assertEquals(
            EventSubEvent.ChatMessage(
                messageId = "cc106a89-1814-919d-454c-f4f2f970aae7",
                chatterId = "4145994",
                chatterLogin = "viewer_one",
                chatterName = "Viewer_One",
                text = "!sr Darude - Sandstorm",
                badges = setOf("lead_moderator", "founder"),
                sourceBroadcasterId = null,
            ),
            note.event,
        )
    }

    @Test
    fun sharedChatCarriesTheSourceAndKeepsTheBadgesOfThisChannel() {
        val chat = assertIs<EventSubEvent.ChatMessage>(notification("chat-message-shared.json").event)
        assertEquals("112233", chat.sourceBroadcasterId)
        assertEquals(setOf("vip"), chat.badges) // not the source channel's moderator badge
        assertEquals("!sr Never Gonna Give You Up", chat.text)
    }

    @Test
    fun anonymousCheerHasNoUserAndUsesTheNotificationId() {
        assertEquals(
            EventSubEvent.Cheer(
                messageId = "a7d3c1e2-6f4b-4b8e-9d1a-2c3b4a5f6e7d",
                isAnonymous = true,
                userId = null,
                userLogin = null,
                userName = null,
                message = "Cheer500 Darude - Sandstorm",
                bits = 500,
            ),
            notification("cheer-anonymous.json").event,
        )
    }

    @Test
    fun redemptionWithALowercaseStatusIsRead() {
        assertEquals(
            EventSubEvent.Redemption(
                id = "17fa2df1-ad76-4804-bfa5-a40ef63efe63",
                rewardId = "92af127c-7326-4483-a52b-b0da0be61c01",
                userId = "1234",
                userLogin = "cool_user",
                userName = "Cool_User",
                userInput = "Daft Punk - One More Time",
            ),
            notification("redemption-add.json").event,
        )
    }

    @Test
    fun rewardUpdateCarriesTheWholeReward() {
        assertEquals(
            EventSubEvent.RewardUpdated(
                Reward(
                    id = "92af127c-7326-4483-a52b-b0da0be61c01",
                    title = "Song request",
                    cost = 750,
                    prompt = "Artist - title, or a Spotify link",
                    isEnabled = true,
                    isPaused = true,
                    isUserInputRequired = true,
                    skipsRequestQueue = false,
                ),
            ),
            notification("reward-update.json").event,
        )
    }

    @Test
    fun rewardRemoveCarriesTheRewardId() {
        assertEquals(
            EventSubEvent.RewardRemoved("92af127c-7326-4483-a52b-b0da0be61c01"),
            notification("reward-remove.json").event,
        )
    }

    @Test
    fun streamOnlineAndOffline() {
        assertEquals(EventSubEvent.StreamOnline, notification("stream-online.json").event)
        assertEquals(EventSubEvent.StreamOffline, notification("stream-offline.json").event)
    }

    @Test
    fun unknownSubscriptionTypeIsIgnored() {
        val note = notification("unknown-type.json")
        assertEquals("60718293-a4b5-4c6d-f4a5-b6c7d8e9f0a1", note.messageId)
        assertNull(note.event)
    }

    @Test
    fun unreadableMessagesAreUnknown() {
        assertEquals(EventSubMessage.Unknown, EventSubParser.parse("not json"))
        assertEquals(EventSubMessage.Unknown, EventSubParser.parse("""{"metadata":{"message_type":"something_new"}}"""))
        assertEquals(EventSubMessage.Unknown, EventSubParser.parse("""{"metadata":{"message_type":"session_welcome"}}"""))
        assertEquals(EventSubMessage.Unknown, EventSubParser.parse("[]"))
    }

    @Test
    fun notificationWithAnUnreadableEventKeepsItsIdButHasNoEvent() {
        val broken = fixture("cheer-anonymous.json").replace("\"bits\": 500", "\"bits\": \"lots\"")
        val note = assertIs<EventSubMessage.Notification>(EventSubParser.parse(broken))
        assertEquals("a7d3c1e2-6f4b-4b8e-9d1a-2c3b4a5f6e7d", note.messageId)
        assertNull(note.event)
    }
}
