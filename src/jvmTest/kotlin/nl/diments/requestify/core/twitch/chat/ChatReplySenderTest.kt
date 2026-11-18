package nl.diments.requestify.core.twitch.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatReplySenderTest {
    @Test
    fun sendsInOrder() = runTest {
        val twitch = RecordingTwitchApi()
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("one", "m1")
        sender.send("two", null)
        sender.send("three", "m3")

        awaitUntil("three sends") { twitch.sends.size == 3 }
        assertEquals(
            listOf(
                RecordingTwitchApi.Sent("one", "m1"),
                RecordingTwitchApi.Sent("two", null),
                RecordingTwitchApi.Sent("three", "m3"),
            ),
            twitch.sends.toList(),
        )
        awaitUntil("ids remembered") { sender.isOwnMessage("own-3") }
        assertTrue(sender.isOwnMessage("own-1"))
        assertFalse(sender.isOwnMessage("own-4"))
    }

    @Test
    fun rateLimitedWaitsAndRetries() = runTest {
        val twitch = RecordingTwitchApi()
        twitch.sendStatuses.add(429) // no Ratelimit-Reset header: the client's default wait of 5 s
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("hello", "m1")

        awaitUntil("first attempt") { twitch.sends.size == 1 }
        settle()
        advanceTimeBy(4_000)
        settle()
        assertEquals(1, twitch.sends.size)
        advanceTimeBy(1_500)
        awaitUntil("retry") { twitch.sends.size == 2 }
        assertEquals(listOf("hello", "hello"), twitch.sends.map { it.message })
        awaitUntil("remembered") { sender.isOwnMessage("own-1") }
    }

    @Test
    fun droppedAfterThreeTries() = runTest {
        val twitch = RecordingTwitchApi()
        repeat(5) { twitch.sendStatuses.add(429) }
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("lost", null)
        sender.send("next", null)

        awaitUntil("first attempt") { twitch.sends.size == 1 }
        // The waits between the three tries start when Twitch answered, on the mock engine's own thread: virtual time
        // moves on in steps until all is sent, so a late answer cannot leave a wait that never ends.
        awaitUntil("three tries and the next reply") {
            advanceTimeBy(1_000)
            twitch.sends.size >= 4
        }
        assertEquals(listOf("lost", "lost", "lost", "next"), twitch.sends.take(4).map { it.message })
    }

    @Test
    fun stopDropsTheRepliesNotSentYet() = runTest {
        val twitch = RecordingTwitchApi()
        twitch.sendStatuses.add(429) // the first reply waits 5 s for its next try
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.start()
        sender.send("first", "m1")
        sender.send("second", "m2")
        awaitUntil("first attempt") { twitch.sends.size == 1 }
        settle()

        sender.stop() // the session ended (a logout, or the login died)
        sender.start() // logged in again later
        settle() // a reply left over would go out now
        assertEquals(listOf("first"), twitch.sends.map { it.message }, "nothing of the old session is posted")

        sender.send("fresh", "m3")
        awaitUntil("a new reply") { twitch.sends.size == 2 }
        assertEquals("fresh", twitch.sends.last().message)
    }

    @Test
    fun stoppedSenderSendsNothingUntilStarted() = runTest {
        val twitch = RecordingTwitchApi()
        val sender = ChatReplySender(twitch.api, backgroundScope)
        sender.send("early", null)
        settle()
        assertEquals(0, twitch.sends.size)
        sender.start()
        awaitUntil("early reply") { twitch.sends.size == 1 }
    }
}
