package nl.diments.requestify.core.donations.streamelements

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nl.diments.requestify.core.storage.AppJson

/** One Astro WebSocket connection: text messages in ([incoming] closes when it ends), text messages out. */
interface AstroSocket {
    val incoming: ReceiveChannel<String>
    suspend fun send(text: String)
    suspend fun close()
}

/** Opens a connection to an Astro URL; throws when that is not possible. */
fun interface AstroConnector {
    suspend fun connect(url: String): AstroSocket
}

/** Connects with Ktor's WebSocket client (the `WebSockets` plugin answers Astro's pings). */
class KtorAstroConnector(private val http: HttpClient) : AstroConnector {
    override suspend fun connect(url: String): AstroSocket = KtorAstroSocket(http.webSocketSession(url))
}

private class KtorAstroSocket(private val session: DefaultClientWebSocketSession) : AstroSocket {
    private val texts = Channel<String>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<String> get() = texts

    init {
        session.launch {
            try {
                for (frame in session.incoming) if (frame is Frame.Text) texts.send(frame.readText())
            } catch (_: Exception) {
                // The connection broke; the closed channel tells the reader.
            } finally {
                texts.close()
            }
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        try {
            withTimeoutOrNull(2_000) { session.close() }
        } catch (_: Exception) {
            // Already gone.
        } finally {
            session.cancel()
            texts.close()
        }
    }
}

/** What [StreamElementsSource] needs from the gateway. */
interface AstroFeed {
    val tips: SharedFlow<JsonObject>
    val state: StateFlow<AstroState>
    fun start()
    fun stop()
}

sealed interface AstroState {
    data object Stopped : AstroState
    data object Connecting : AstroState
    data object Connected : AstroState
    data class Disconnected(val sinceEpochMs: Long) : AstroState

    /** The token was refused: nothing more until the next start. */
    data object Refused : AstroState
}

/**
 * StreamElements' Astro gateway, subscribed to `channel.tips` in [room] with the streamer's [jwt]. Every tip's `data`
 * comes out on [tips]. A broken connection is tried again after 1 s, doubling to 60 s (back to 1 s once a session lived
 * a minute); a `reconnect` message is followed at once with its token, which keeps the subscription. [freshSessions]
 * fires after every new subscription (not after a resume): tips sent in between are gone. Facts in
 * docs/superpowers/specs/2026-11-18-streamelements-api-notes.md.
 */
class AstroClient(
    private val connector: AstroConnector,
    private val jwt: () -> String?,
    private val room: suspend () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val url: String = DEFAULT_URL,
) : AstroFeed {
    private val _tips = MutableSharedFlow<JsonObject>(extraBufferCapacity = Int.MAX_VALUE)
    override val tips: SharedFlow<JsonObject> = _tips.asSharedFlow()

    private val _state = MutableStateFlow<AstroState>(AstroState.Stopped)
    override val state: StateFlow<AstroState> = _state.asStateFlow()

    private val _freshSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val freshSessions: SharedFlow<Unit> = _freshSessions.asSharedFlow()

    private val lock = Any()
    private var job: Job? = null

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            _state.value = AstroState.Connecting
            job = scope.launch { run() }
        }
    }

    override fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            _state.value = AstroState.Stopped
        }
    }

    /** How a session ended. */
    private sealed interface Ended {
        data object Refused : Ended
        data class Resume(val token: String) : Ended
        data class Broke(val livedMs: Long) : Ended
    }

    private suspend fun run() {
        var resumeToken: String? = null
        var failures = 0
        while (true) {
            val socket = try {
                connector.connect(if (resumeToken != null) "$url?reconnect_token=$resumeToken" else url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Anything unexpected in a session (a send on a socket that just closed) is a broken connection: tried again.
            val ended = if (socket == null) {
                Ended.Broke(0)
            } else {
                try {
                    session(socket, resumed = resumeToken != null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Ended.Broke(0)
                }
            }
            resumeToken = null
            when (ended) {
                Ended.Refused -> {
                    _state.value = AstroState.Refused
                    return
                }
                is Ended.Resume -> resumeToken = ended.token // at once
                is Ended.Broke -> {
                    if (_state.value !is AstroState.Disconnected) _state.value = AstroState.Disconnected(clock())
                    if (ended.livedMs >= HEALTHY_SESSION_MS) failures = 0
                    delay(BACKOFF_MS.getOrElse(failures) { BACKOFF_MS.last() })
                    failures++
                }
            }
        }
    }

    private suspend fun session(socket: AstroSocket, resumed: Boolean): Ended {
        var connectedAt: Long? = null
        try {
            while (true) {
                // Until subscribed, Astro must answer in time; after that its pings keep the connection honest.
                val text = if (connectedAt == null) {
                    withTimeoutOrNull(HANDSHAKE_MS) { socket.incoming.receiveCatching().getOrNull() }
                } else {
                    socket.incoming.receiveCatching().getOrNull()
                } ?: return Ended.Broke(connectedAt?.let { clock() - it } ?: 0)
                val message = parse(text) ?: continue
                when (message.text("type")) {
                    "welcome" -> if (resumed) {
                        connectedAt = clock()
                        _state.value = AstroState.Connected
                    } else {
                        socket.send(subscribe(room() ?: return Ended.Broke(0), jwt() ?: return Ended.Refused))
                    }
                    "response" -> {
                        val error = message["error"]?.toString()
                        if (error != null) {
                            return if (error.contains("unauthorized", ignoreCase = true)) Ended.Refused else Ended.Broke(0)
                        }
                        connectedAt = clock()
                        _state.value = AstroState.Connected
                        _freshSessions.tryEmit(Unit)
                    }
                    "message" -> if (message.text("topic") == TIPS_TOPIC) (message["data"] as? JsonObject)?.let { _tips.tryEmit(it) }
                    "reconnect" -> {
                        val token = (message["data"] as? JsonObject)?.text("reconnect_token") ?: message.text("reconnect_token")
                        if (token != null) return Ended.Resume(token)
                    }
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun subscribe(room: String, token: String): String = buildJsonObject {
        put("type", "subscribe")
        put("nonce", UUID.randomUUID().toString())
        put(
            "data",
            buildJsonObject {
                put("topic", TIPS_TOPIC)
                put("room", room)
                put("token", token)
                put("token_type", "jwt")
            },
        )
    }.toString()

    private fun parse(text: String): JsonObject? = try {
        AppJson.instance.parseToJsonElement(text).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val DEFAULT_URL = "wss://astro.streamelements.com/"
        const val TIPS_TOPIC = "channel.tips"
        const val HANDSHAKE_MS = 15_000L
        const val HEALTHY_SESSION_MS = 60_000L
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L)
    }
}
