package nl.diments.requestify.core.spotify.auth

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class LoopbackReceiverTest {
    private fun get(port: Int, query: String): Int =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/callback?$query")).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    private fun await(query: String, state: String = "s1"): Result<String> = runBlocking {
        val receiver = LoopbackReceiver(0)
        val result = async(Dispatchers.Default) { receiver.awaitCode(state) }
        assertEquals(200, get(receiver.boundPort, query))
        result.await()
    }

    @Test
    fun returnsCode() {
        assertEquals("abc", await("code=abc&state=s1").getOrThrow())
    }

    @Test
    fun wrongStateFails() {
        assertTrue(await("code=abc&state=s2").isFailure)
    }

    @Test
    fun accessDeniedFails() {
        assertTrue(await("error=access_denied&state=s1").isFailure)
    }

    @Test
    fun timesOut() = runBlocking {
        val receiver = LoopbackReceiver(0)
        val result = receiver.awaitCode("s1", 100.milliseconds)
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }
}
