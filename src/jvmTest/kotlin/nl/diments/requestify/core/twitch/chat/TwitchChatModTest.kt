package nl.diments.requestify.core.twitch.chat

import nl.diments.requestify.core.settings.setForTest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class TwitchChatModTest {
    private lateinit var dir: Path
    private lateinit var savedLocale: Locale

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("requestify-chat-mod")
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(savedLocale)
        dir.deleteRecursively()
    }

    private val idA = "A".repeat(22)
    private val idB = "B".repeat(22)
    private val idC = "C".repeat(22)
    private fun link(id: String) = "https://open.spotify.com/track/$id"

    private class Harness(val rig: TwitchTestRig) {
        fun message(id: String, text: String, viewer: String = "u1", badges: Set<String> = emptySet()) {
            rig.events.tryEmit(
                EventSubEvent.ChatMessage(id, viewer, "login_$viewer", "Name $viewer", text, badges, null),
            )
        }

        val sends get() = rig.twitch.sends
    }

    private fun TestScope.harness(playing: Boolean = false): Harness {
        val rig = TwitchTestRig(this, dir)
        rig.catalog.tracks[idA] = rig.track(idA, "Alpha")
        rig.catalog.tracks[idB] = rig.track(idB, "Bravo")
        rig.catalog.tracks[idC] = rig.track(idC, "Charlie")
        if (playing) {
            rig.player.plays(rig.track(idA, "Alpha"), progressMs = 10_000)
            rig.controller.start()
        }
        val chat = TwitchChat(
            events = rig.events,
            intake = rig.intake,
            controller = rig.controller,
            api = rig.twitch.api,
            settings = rig.settings,
            replies = rig.replies,
            sender = rig.sender,
            broadcasterId = { RecordingTwitchApi.BROADCASTER_ID },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        rig.sender.start()
        chat.start()
        runCurrent()
        if (playing) awaitUntil("now playing") { rig.controller.snapshot().nowPlaying != null }
        return Harness(rig)
    }

    @Test
    fun viewerCannotSkip() = runTest {
        val h = harness(playing = true)
        h.message("m1", "!skip", viewer = "u1")
        h.message("m2", "!skip", viewer = "u2", badges = setOf("subscriber", "vip"))
        h.message("probe", "!song", viewer = "u3")

        awaitUntil("probe reply") { h.sends.isNotEmpty() }
        settle()
        assertEquals(listOf("probe"), h.sends.map { it.replyTo })
        assertTrue(h.rig.player.callsTo("skipNext").isEmpty())
    }

    @Test
    fun modSkipSkips() = runTest {
        val h = harness(playing = true)
        h.message("m1", "!skip", viewer = "mod1", badges = setOf("moderator"))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertEquals(1, h.rig.player.callsTo("skipNext").size)
        assertEquals("m1", h.sends.single().replyTo)
        assertTrue("Skipped" in h.sends.single().message && "Alpha" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun broadcasterCanSkipAndNothingPlayingSaysSo() = runTest {
        val h = harness(playing = false)
        h.message("m1", "!skip", viewer = "streamer", badges = setOf("broadcaster"))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertTrue("nothing" in h.sends.single().message.lowercase(), h.sends.single().message)
        assertTrue(h.rig.player.callsTo("skipNext").isEmpty())
    }

    @Test
    fun leadModeratorCanRemovePositionTwo() = runTest {
        val h = harness()
        h.message("a", "!sr ${link(idA)}", viewer = "u1")
        h.message("b", "!sr ${link(idB)}", viewer = "u2")
        h.message("c", "!sr ${link(idC)}", viewer = "u3")
        awaitUntil("three replies") { h.sends.size == 3 }

        h.message("r1", "!remove 2", viewer = "mod1", badges = setOf("lead_moderator"))
        awaitUntil("remove reply") { h.sends.size == 4 }
        assertEquals(listOf("Alpha", "Charlie"), h.rig.queued.map { it.track.name })
        assertEquals("r1", h.sends.last().replyTo)
        assertTrue("Bravo" in h.sends.last().message, h.sends.last().message)
    }

    @Test
    fun viewerCannotRemove() = runTest {
        val h = harness()
        h.message("a", "!sr ${link(idA)}", viewer = "u1")
        awaitUntil("reply") { h.sends.size == 1 }

        h.message("r1", "!remove 1", viewer = "u1", badges = setOf("vip"))
        h.message("probe", "!song", viewer = "u2")
        awaitUntil("probe reply") { h.sends.size == 2 }
        settle()
        assertEquals(listOf("a", "probe"), h.sends.map { it.replyTo })
        assertEquals(1, h.rig.queued.size)
    }

    @Test
    fun removeInvalidPositionReplies() = runTest {
        val h = harness()
        h.message("a", "!sr ${link(idA)}", viewer = "u1")
        awaitUntil("reply") { h.sends.size == 1 }

        h.message("r1", "!remove 5", viewer = "mod1", badges = setOf("moderator"))
        h.message("r2", "!remove", viewer = "mod1", badges = setOf("moderator"))
        h.message("r3", "!remove 0", viewer = "mod1", badges = setOf("moderator"))
        awaitUntil("three replies") { h.sends.size == 4 }
        assertEquals(listOf("r1", "r2", "r3"), h.sends.drop(1).map { it.replyTo })
        assertTrue("No position 5" in h.sends[1].message, h.sends[1].message)
        assertEquals("Usage: !remove <position>", h.sends[2].message, "without a number: how to use it, not a literal n")
        assertTrue("No position 0" in h.sends[3].message, h.sends[3].message)
        assertEquals(listOf("Alpha"), h.rig.queued.map { it.track.name })
    }

    @Test
    fun removeUsageNamesTheConfiguredCommandInTheAppLanguage() = runTest {
        Locale.setDefault(Locale.forLanguageTag("nl-NL"))
        val h = harness()
        h.rig.settings.setForTest { s -> s.copy(twitch = s.twitch.copy(commands = s.twitch.commands.copy(remove = "weg"))) }

        h.message("r1", "!weg", viewer = "mod1", badges = setOf("moderator"))
        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertEquals("Gebruik: !weg <positie>", h.sends.single().message)
        assertEquals("r1", h.sends.single().replyTo)
    }

    @Test
    fun blacklistBlocksCurrentTrackByModAndSkips() = runTest {
        val h = harness(playing = true)
        h.message("m1", "!blacklist", viewer = "mod1", badges = setOf("moderator"))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        val blocked = h.rig.blacklist.blacklist.value.tracks.single()
        assertEquals(idA, blocked.track.id)
        assertEquals("login_mod1", blocked.by)
        assertEquals(1, h.rig.player.callsTo("skipNext").size)
        assertEquals("m1", h.sends.single().replyTo)
        assertTrue("Alpha" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun aBlacklistWhoseFileCannotBeWrittenStillBlocksAndSkips() = runTest {
        val h = harness(playing = true)
        java.nio.file.Files.createDirectory(dir.resolve("blacklist.json.tmp")) // the blacklist cannot be written yet
        h.message("m1", "!blacklist", viewer = "mod1", badges = setOf("moderator"))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertEquals(idA, h.rig.blacklist.blacklist.value.tracks.single().track.id, "applied at once, saving retried")
        assertEquals(1, h.rig.player.callsTo("skipNext").size)
        assertTrue("Alpha" in h.sends.single().message, h.sends.single().message)
    }

    @Test
    fun blacklistWithNothingPlayingSaysSo() = runTest {
        val h = harness(playing = false)
        h.message("m1", "!blacklist", viewer = "mod1", badges = setOf("moderator"))

        awaitUntil("reply") { h.sends.isNotEmpty() }
        assertTrue("nothing" in h.sends.single().message.lowercase(), h.sends.single().message)
        assertTrue(h.rig.blacklist.blacklist.value.tracks.isEmpty())
        assertTrue(h.rig.player.callsTo("skipNext").isEmpty())
    }
}
