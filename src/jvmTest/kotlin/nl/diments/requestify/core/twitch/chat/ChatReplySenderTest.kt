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
        repeat(2) { n -> // the waits between the three tries
            settle()
            advanceTimeBy(5_000)
            awaitUntil("attempt ${n + 2}") { twitch.sends.size == n + 2 }
        }
        awaitUntil("next reply") { twitch.sends.size == 4 }
        assertEquals(listOf("lost", "lost", "lost", "next"), twitch.sends.map { it.message })
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
