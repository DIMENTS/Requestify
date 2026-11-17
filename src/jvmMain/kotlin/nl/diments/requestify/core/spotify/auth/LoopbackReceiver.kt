package nl.diments.requestify.core.spotify.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import nl.diments.requestify.resources.oauth_failed_body
import nl.diments.requestify.resources.oauth_success_body
import nl.diments.requestify.resources.oauth_success_title
import org.jetbrains.compose.resources.getString

const val SPOTIFY_REDIRECT_PORT = 43821
const val SPOTIFY_REDIRECT_URI = "http://127.0.0.1:$SPOTIFY_REDIRECT_PORT/callback"
val SPOTIFY_SCOPES = listOf(
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-read-currently-playing",
    "user-read-private",
    "user-read-recently-played",
)

/**
 * One-shot loopback HTTP server for the OAuth redirect. Binds on construction
 * (throws [java.net.BindException] if the port is taken) and stops after the first callback,
 * on timeout, or on [close].
 */
class LoopbackReceiver(port: Int = SPOTIFY_REDIRECT_PORT) : AutoCloseable {
    private val result = CompletableDeferred<Result<String>>()
    private val expectedState = CompletableDeferred<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)

    val boundPort: Int get() = server.address.port

    init {
        server.createContext("/callback") { handle(it) }
        server.start()
    }

    suspend fun awaitCode(expectedState: String, timeout: Duration = 5.minutes): Result<String> {
        this.expectedState.complete(expectedState)
        return try {
            withTimeout(timeout) { result.await() }
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } finally {
            close()
        }
    }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val params = parseQuery(it.requestURI.rawQuery)
            val outcome = when {
                params["state"] != runBlocking { expectedState.await() } ->
                    Result.failure(IllegalStateException("state mismatch"))
                params["error"] != null -> Result.failure(IllegalStateException(params["error"]))
                params["code"].isNullOrEmpty() -> Result.failure(IllegalStateException("no code"))
                else -> Result.success(params.getValue("code"))
            }
            val body = runBlocking { page(outcome.isSuccess) }.toByteArray(Charsets.UTF_8)
            it.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            it.sendResponseHeaders(200, body.size.toLong())
            it.responseBody.use { out -> out.write(body) }
            result.complete(outcome)
        }
    }

    private suspend fun page(success: Boolean): String {
        val title = getString(if (success) Res.string.oauth_success_title else Res.string.app_name)
        val body = getString(if (success) Res.string.oauth_success_body else Res.string.oauth_failed_body)
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>$title</title></head>" +
            "<body style=\"font-family:sans-serif;text-align:center;margin-top:20vh\">" +
            "<h1>$title</h1><p>$body</p></body></html>"
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
}
