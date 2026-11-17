package nl.diments.requestify.core.twitch.eventsub

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One EventSub WebSocket connection. [incoming] carries the text messages and closes when the connection ends, for
 * whatever reason. The client never sends anything: Twitch closes a connection that does (close code 4001).
 */
interface EventSubSocket {
    val incoming: ReceiveChannel<String>

    suspend fun close()
}

/** Opens a connection to an EventSub URL; throws when that is not possible. */
fun interface EventSubConnector {
    suspend fun connect(url: String): EventSubSocket
}

/** Connects with Ktor's WebSocket client. [http] needs the `WebSockets` plugin and an engine that has it (CIO). */
class KtorEventSubConnector(private val http: HttpClient) : EventSubConnector {
    override suspend fun connect(url: String): EventSubSocket = KtorEventSubSocket(http.webSocketSession(url))
}

/**
 * Ktor answers Twitch's pings itself. Frames are moved into an unbounded channel at once, so a slow reader never
 * holds up Ktor's frame reader (and with it the pong Twitch expects; close code 4002).
 */
private class KtorEventSubSocket(private val session: DefaultClientWebSocketSession) : EventSubSocket {
    private val texts = Channel<String>(Channel.UNLIMITED)

    override val incoming: ReceiveChannel<String> get() = texts

    init {
        session.launch {
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) texts.send(frame.readText())
                }
            } catch (_: Exception) {
                // The connection broke; the closed channel tells the reader.
            } finally {
                texts.close()
            }
        }
    }

    override suspend fun close() {
        try {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { session.close() }
        } catch (_: Exception) {
            // Already gone.
        } finally {
            session.cancel()
            texts.close()
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}
