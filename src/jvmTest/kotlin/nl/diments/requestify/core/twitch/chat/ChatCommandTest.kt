package nl.diments.requestify.core.twitch.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nl.diments.requestify.core.settings.ChatCommandNames

class ChatCommandTest {
    private val names = ChatCommandNames()

    @Test
    fun parsesConfiguredNamesCaseInsensitively() {
        assertEquals(ChatCommand.Request("never gonna"), parseChatCommand("!SR never gonna", names))
        assertEquals(ChatCommand.Song, parseChatCommand("!Song", names))
        assertEquals(ChatCommand.Queue, parseChatCommand("  !queue  ", names))
        assertEquals(ChatCommand.WrongSong, parseChatCommand("!wrongsong", names))
        assertEquals(ChatCommand.Skip, parseChatCommand("!skip", names))
        assertEquals(ChatCommand.Blacklist, parseChatCommand("!blacklist", names))
        assertEquals(ChatCommand.Request(""), parseChatCommand("!sr", names))
        assertEquals(ChatCommand.Request("x"), parseChatCommand("!play x", names.copy(request = "play")))
        assertNull(parseChatCommand("!sr x", names.copy(request = "play")))
    }

    @Test
    fun ignoresTextThatOnlyContainsTheCommandLater() {
        assertNull(parseChatCommand("hey !sr never gonna", names))
        assertNull(parseChatCommand("sr never gonna", names))
        assertNull(parseChatCommand("!srnever gonna", names))
        assertNull(parseChatCommand("!unknown", names))
        assertNull(parseChatCommand("!", names))
        assertNull(parseChatCommand("", names))
    }

    @Test
    fun removeTakesPosition() {
        assertEquals(ChatCommand.Remove(3), parseChatCommand("!remove 3", names))
        assertEquals(ChatCommand.Remove(2), parseChatCommand("!REMOVE #2 please", names))
        assertEquals(ChatCommand.Remove(null), parseChatCommand("!remove", names))
        assertEquals(ChatCommand.Remove(null), parseChatCommand("!remove abc", names))
    }

    @Test
    fun rolesFromBadges() {
        assertEquals(ChatRole.Viewer, roleOf(emptySet()))
        assertEquals(ChatRole.Viewer, roleOf(setOf("premium", "bits")))
        assertEquals(ChatRole.Subscriber, roleOf(setOf("subscriber")))
        assertEquals(ChatRole.Subscriber, roleOf(setOf("founder")))
        assertEquals(ChatRole.Vip, roleOf(setOf("vip", "subscriber")))
        assertEquals(ChatRole.Moderator, roleOf(setOf("moderator", "vip")))
        assertEquals(ChatRole.Moderator, roleOf(setOf("lead_moderator")))
        assertEquals(ChatRole.Broadcaster, roleOf(setOf("broadcaster", "moderator")))
        assertEquals(true, ChatRole.Moderator > ChatRole.Vip)
    }
}
