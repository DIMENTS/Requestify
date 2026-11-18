package nl.diments.requestify.core.donations.streamelements

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import nl.diments.requestify.core.storage.AppJson

/** What StreamElements answered. */
sealed interface SeResult<out T> {
    data class Ok<T>(val value: T) : SeResult<T>

    /** The token was refused (or there is none): a new one is needed. */
    data object Refused : SeResult<Nothing>

    data class Failed(val message: String) : SeResult<Nothing>
}

/** The channel behind a token: its id (the room tips arrive in) and the name to show. */
data class SeChannel(val id: String, val name: String)

/**
 * The little of StreamElements' REST API Requestify uses: the channel behind the streamer's JWT
 * (`GET /kappa/v2/channels/me`, see docs/superpowers/specs/2026-11-18-streamelements-api-notes.md).
 */
class StreamElementsApi(
    private val http: HttpClient,
    private val jwt: () -> String?,
    private val baseUrl: String = "https://api.streamelements.com/kappa/v2",
) {
    suspend fun channel(): SeResult<SeChannel> {
        val token = jwt()?.takeIf { it.isNotBlank() } ?: return SeResult.Refused
        val response = try {
            http.get("$baseUrl/channels/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SeResult.Failed(e.message ?: e::class.simpleName ?: "network")
        }
        val status = response.status.value
        if (status == 401 || status == 403) return SeResult.Refused
        if (!response.status.isSuccess()) return SeResult.Failed("HTTP $status")
        return try {
            val body = AppJson.instance.parseToJsonElement(response.bodyAsText()).jsonObject
            val id = body.text("_id") ?: return SeResult.Failed("unexpected answer")
            val name = body.text("displayName")?.takeIf { it.isNotBlank() } ?: body.text("username") ?: id
            SeResult.Ok(SeChannel(id, name))
        } catch (e: CancellationException) {
            throw e
        } catch (_: SerializationException) {
            SeResult.Failed("unexpected answer")
        } catch (_: IllegalArgumentException) {
            SeResult.Failed("unexpected answer")
        } catch (e: IOException) {
            SeResult.Failed(e.message ?: "network")
        }
    }

    /**
     * The currency the channel takes tips in, from its tipping settings (`GET /kappa/v2/tipping/<channel>`, field
     * `currency`); Ok(null) when StreamElements does not say. Unverified against SE's docs (see the API notes): the
     * minimum then stays compared in each tip's own currency.
     */
    suspend fun channelCurrency(channelId: String): SeResult<String?> {
        val token = jwt()?.takeIf { it.isNotBlank() } ?: return SeResult.Refused
        val response = try {
            http.get("$baseUrl/tipping/$channelId") { header(HttpHeaders.Authorization, "Bearer $token") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SeResult.Failed(e.message ?: e::class.simpleName ?: "network")
        }
        val status = response.status.value
        if (status == 401 || status == 403) return SeResult.Refused
        if (!response.status.isSuccess()) return SeResult.Failed("HTTP $status")
        return try {
            val body = AppJson.instance.parseToJsonElement(response.bodyAsText()).jsonObject
            SeResult.Ok(body.text("currency")?.takeIf { it.length == 3 }?.uppercase())
        } catch (e: CancellationException) {
            throw e
        } catch (_: SerializationException) {
            SeResult.Ok(null)
        } catch (_: IllegalArgumentException) {
            SeResult.Ok(null)
        } catch (e: IOException) {
            SeResult.Failed(e.message ?: "network")
        }
    }
}

/** The `channel` claim of a StreamElements JWT (its payload is not checked, only read); null when there is none. */
fun channelIdFromJwt(jwt: String): String? = try {
    val payload = jwt.split('.').getOrNull(1) ?: return null
    val json = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
    (AppJson.instance.parseToJsonElement(json) as? JsonObject)?.text("channel")?.takeIf { it.isNotBlank() }
} catch (_: IllegalArgumentException) {
    null
} catch (_: SerializationException) {
    null
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
