package nl.diments.requestify.core.twitch.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore

/** The app's Twitch application: a Public client (no secret), so it is not a secret and lives in source. */
const val TWITCH_CLIENT_ID = "wu2hg6lvxw5lqb2qbs046ou19nupnp"

val TWITCH_SCOPES = listOf(
    "user:read:chat",
    "user:write:chat",
    "channel:manage:redemptions",
    "bits:read",
    "moderator:read:followers",
)

/** The bot account sends chat messages, and may read where it is a moderator (shown on Connections); nothing else. */
val TWITCH_BOT_SCOPES = listOf(
    "user:write:chat",
    "user:read:moderated_channels",
)

sealed interface TwitchAuthStatus {
    data object LoggedOut : TwitchAuthStatus
    data object LoggedIn : TwitchAuthStatus
    data class LoginNeeded(val reason: String) : TwitchAuthStatus
}

/** What the user needs to approve a device login; [verificationUri] already carries the code. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMs: Long,
    val intervalMs: Long,
)

sealed interface DeviceLoginResult {
    data class Success(val userId: String, val login: String) : DeviceLoginResult

    /** The code was approved by an account the caller refused (the bot's code, approved as the streamer); nothing saved. */
    data class WrongAccount(val userId: String, val login: String) : DeviceLoginResult
    data object Expired : DeviceLoginResult
    data class Failed(val reason: String) : DeviceLoginResult
}

private data class AccessToken(val value: String, val expiresAtEpochMs: Long)

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Long = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class ErrorResponse(val message: String = "")

@Serializable
private data class ValidateResponse(
    val login: String,
    @SerialName("user_id") val userId: String,
)

private const val REFRESH_MARGIN_MS = 60_000L
private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
private const val SLOW_DOWN_MS = 5_000L

/**
 * A Twitch login (the broadcaster's, or the bot's with [secretKey] and [scopes] of its own): device code grant of a
 * Public client (no secret), rotating refresh tokens and token validation. Mirrors `SpotifyAuth`: a network problem
 * never logs out, only Twitch refusing the login does.
 */
class TwitchAuth(
    private val http: HttpClient,
    private val secrets: SecretStore,
    private val clock: () -> Long,
    private val idBaseUrl: String = "https://id.twitch.tv",
    private val requestTimeoutMs: Long = 15_000,
    private val secretKey: String = SecretKeys.TWITCH_REFRESH_TOKEN,
    private val scopes: List<String> = TWITCH_SCOPES,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Bumped by logout(), markLoginNeeded() and a successful login; in-flight refreshes of an older one are dropped. */
    private var generation = 0L
    private val stateLock = Any()

    @Volatile
    private var cached: AccessToken? = null

    /** A rotated refresh token the secret store would not take; guarded by [stateLock]. */
    private var pendingRefresh: String? = null

    private val _status = MutableStateFlow<TwitchAuthStatus>(
        if (secrets.get(secretKey) != null) TwitchAuthStatus.LoggedIn else TwitchAuthStatus.LoggedOut,
    )
    val status: StateFlow<TwitchAuthStatus> = _status.asStateFlow()

    /** Asks Twitch for a device code; null when Twitch cannot be reached or refuses. */
    suspend fun startDeviceLogin(): DeviceCode? {
        val (code, body) = post("/oauth2/device") {
            append("client_id", TWITCH_CLIENT_ID)
            append("scopes", scopes.joinToString(" "))
        } ?: return null
        if (code != HttpStatusCode.OK) return null
        val parsed = try {
            json.decodeFromString<DeviceCodeResponse>(body)
        } catch (_: Exception) {
            return null
        }
        return DeviceCode(
            deviceCode = parsed.deviceCode,
            userCode = parsed.userCode,
            verificationUri = parsed.verificationUri,
            expiresAtEpochMs = clock() + parsed.expiresIn * 1000,
            intervalMs = parsed.interval * 1000,
        )
    }

    /**
     * Polls until the user approved [code], the code ran out or Twitch refused it. Only `authorization_pending` (and
     * a network problem) keeps polling. On success the account is looked up and, when [accept] takes its user id, the
     * login is saved; a refused account ([DeviceLoginResult.WrongAccount]) saves nothing and leaves any earlier login.
     */
    suspend fun awaitDeviceLogin(code: DeviceCode, accept: (userId: String) -> Boolean = { true }): DeviceLoginResult {
        val startGeneration = synchronized(stateLock) { generation }
        var interval = code.intervalMs
        while (true) {
            if (clock() >= code.expiresAtEpochMs) return DeviceLoginResult.Expired
            delay(interval)
            if (clock() >= code.expiresAtEpochMs) return DeviceLoginResult.Expired
            val (status, body) = post("/oauth2/token") {
                append("client_id", TWITCH_CLIENT_ID)
                append("scopes", scopes.joinToString(" "))
                append("device_code", code.deviceCode)
                append("grant_type", DEVICE_GRANT_TYPE)
            } ?: continue // offline for a moment: the code is still good, ask again
            if (status == HttpStatusCode.OK) {
                val token = try {
                    json.decodeFromString<TokenResponse>(body)
                } catch (_: Exception) {
                    return DeviceLoginResult.Failed("unreadable token response")
                }
                val refresh = token.refreshToken ?: return DeviceLoginResult.Failed("no refresh token in response")
                return finishLogin(startGeneration, token.accessToken, token.expiresIn, refresh, accept)
            }
            when (messageOf(body)) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += SLOW_DOWN_MS
                else -> return DeviceLoginResult.Failed("token endpoint returned ${status.value}")
            }
        }
    }

    /**
     * The account is looked up first: without it the login is no use, and an account [accept] refuses must not
     * replace anything. Only then is the login saved; when that fails nothing has changed and the user can try again.
     */
    private suspend fun finishLogin(
        startGeneration: Long,
        access: String,
        expiresIn: Long,
        refresh: String,
        accept: (userId: String) -> Boolean,
    ): DeviceLoginResult {
        val who = whoAmI(access) as? Identity.Known ?: return DeviceLoginResult.Failed("could not read the Twitch account")
        if (!accept(who.userId)) return DeviceLoginResult.WrongAccount(who.userId, who.login)
        synchronized(stateLock) {
            if (generation != startGeneration) return DeviceLoginResult.Failed("cancelled")
            try {
                secrets.put(secretKey, refresh)
            } catch (_: IOException) {
                return DeviceLoginResult.Failed("could not save the login")
            } catch (_: SecurityException) {
                return DeviceLoginResult.Failed("could not save the login")
            }
            generation++
            pendingRefresh = null
            cached = AccessToken(access, clock() + expiresIn * 1000)
            _status.value = TwitchAuthStatus.LoggedIn
        }
        return DeviceLoginResult.Success(who.userId, who.login)
    }

    /**
     * The user access token for an API call, or why there is none. A network problem, a timeout, a rate limit or a
     * Twitch server error is [TokenResult.Unavailable]: the login is kept and the next call tries again. Only an
     * invalid refresh token forgets the login ([TokenResult.LoginNeeded]); it is not sent again until a new login.
     */
    suspend fun token(forceRefresh: Boolean = false): TokenResult = when (val got = obtain(forceRefresh)) {
        is Obtained.Token -> TokenResult.Ok(got.token.value)
        is Obtained.Missing -> got.why
    }

    /**
     * Asks Twitch whether the token is still good (required at start and then every hour). A 401 gets one refresh
     * and one retry; still refused means the login is dead. [TokenResult.Ok] carries the validated token.
     */
    suspend fun validate(): TokenResult {
        val first = when (val got = obtain(forceRefresh = false)) {
            is Obtained.Missing -> return got.why
            is Obtained.Token -> got.token
        }
        when (whoAmI(first.value)) {
            is Identity.Known -> return TokenResult.Ok(first.value)
            Identity.Unavailable -> return TokenResult.Unavailable
            Identity.Refused -> Unit
        }
        val second = when (val got = obtain(forceRefresh = true)) {
            is Obtained.Missing -> return got.why
            is Obtained.Token -> got.token
        }
        return when (whoAmI(second.value)) {
            is Identity.Known -> TokenResult.Ok(second.value)
            Identity.Unavailable -> TokenResult.Unavailable
            Identity.Refused -> {
                markLoginNeeded("Twitch refused the token after a refresh")
                TokenResult.LoginNeeded
            }
        }
    }

    private sealed interface Identity {
        class Known(val userId: String, val login: String) : Identity
        data object Refused : Identity
        data object Unavailable : Identity
    }

    private suspend fun whoAmI(accessToken: String): Identity {
        val (code, body) = try {
            withTimeout(requestTimeoutMs) {
                val r = http.get("$idBaseUrl/oauth2/validate") { header(HttpHeaders.Authorization, "OAuth $accessToken") }
                r.status to r.bodyAsText()
            }
        } catch (_: TimeoutCancellationException) {
            return Identity.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Identity.Unavailable
        }
        return when {
            code == HttpStatusCode.OK -> try {
                json.decodeFromString<ValidateResponse>(body).let { Identity.Known(it.userId, it.login) }
            } catch (_: Exception) {
                Identity.Unavailable
            }
            code == HttpStatusCode.Unauthorized -> Identity.Refused
            else -> Identity.Unavailable
        }
    }

    /** A usable token, or why there is none. */
    private sealed interface Obtained {
        class Token(val token: AccessToken) : Obtained
        class Missing(val why: TokenResult) : Obtained
    }

    private suspend fun obtain(forceRefresh: Boolean): Obtained {
        val seen = cached
        if (!forceRefresh) seen?.takeIf { isFresh(it) }?.let { return Obtained.Token(it) }
        return mutex.withLock {
            val current = cached
            if (current != null) {
                // A concurrent caller already refreshed while we waited for the lock.
                if (forceRefresh) { if (current !== seen) return@withLock Obtained.Token(current) }
                else if (isFresh(current)) return@withLock Obtained.Token(current)
            }
            refresh()
        }
    }

    private fun isFresh(t: AccessToken) = t.expiresAtEpochMs - clock() > REFRESH_MARGIN_MS

    private suspend fun refresh(): Obtained {
        val startGeneration = synchronized(stateLock) { generation }
        // Twitch already refused this login: asking again cannot help, only a new login can.
        if (_status.value is TwitchAuthStatus.LoginNeeded) return Obtained.Missing(TokenResult.LoginNeeded)
        // A rotated token that could not be saved yet is the live one; the saved one is already used up.
        val refreshToken = synchronized(stateLock) {
            pendingRefresh?.also { if (trySave(it)) pendingRefresh = null }
        } ?: secrets.get(secretKey)
        if (refreshToken == null) {
            // Logged in, yet the saved login cannot be read (the secrets file held by another program, or not
            // decryptable for now): a read failure, not a logout. The status stays; only logout(), forget() and the
            // roll-back of a login change it. The next call reads it again.
            return Obtained.Missing(
                synchronized(stateLock) {
                    if (_status.value == TwitchAuthStatus.LoggedIn) TokenResult.Unavailable else TokenResult.LoggedOut
                },
            )
        }
        val (code, body) = post("/oauth2/token") {
            append("client_id", TWITCH_CLIENT_ID)
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken) // submitForm URL-encodes it; Twitch needs that
        } ?: return Obtained.Missing(TokenResult.Unavailable) // network blip or hung connection: keep the login
        // Apply the result only if no logout()/login happened while the request was in flight.
        return when {
            code == HttpStatusCode.OK -> {
                val token = try {
                    json.decodeFromString<TokenResponse>(body)
                } catch (_: Exception) {
                    return Obtained.Missing(TokenResult.Unavailable)
                }
                synchronized(stateLock) {
                    if (generation != startGeneration) return@synchronized superseded()
                    // Persist the rotated refresh token BEFORE the new access token is used.
                    // When it cannot be saved the session goes on with it in memory (Twitch has already used up the
                    // old one) and every later refresh tries to save it again.
                    token.refreshToken?.let { pendingRefresh = if (trySave(it)) null else it }
                    val access = AccessToken(token.accessToken, clock() + token.expiresIn * 1000)
                    cached = access
                    _status.value = TwitchAuthStatus.LoggedIn
                    Obtained.Token(access)
                }
            }
            isLoginRefused(code, body) -> synchronized(stateLock) {
                if (generation != startGeneration) return@synchronized superseded()
                forget("Twitch refused the saved login (HTTP ${code.value})")
                Obtained.Missing(TokenResult.LoginNeeded)
            }
            else -> Obtained.Missing(TokenResult.Unavailable) // rate limit or Twitch trouble: keep the login
        }
    }

    /** 401, or 400 "Invalid refresh token": the refresh token is dead (revoked, expired, already used). */
    private fun isLoginRefused(code: HttpStatusCode, body: String): Boolean =
        code == HttpStatusCode.Unauthorized ||
            (code == HttpStatusCode.BadRequest && messageOf(body).contains("invalid refresh token", ignoreCase = true))

    private fun messageOf(body: String): String = try {
        json.decodeFromString<ErrorResponse>(body).message
    } catch (_: Exception) {
        ""
    }

    /** A logout or login happened while a refresh was in flight: its answer is dropped. Caller holds [stateLock]. */
    private fun superseded(): Obtained.Missing = Obtained.Missing(
        when (_status.value) {
            TwitchAuthStatus.LoggedOut -> TokenResult.LoggedOut
            is TwitchAuthStatus.LoginNeeded -> TokenResult.LoginNeeded
            TwitchAuthStatus.LoggedIn -> TokenResult.Unavailable // a new login: the next call uses its token
        },
    )

    private fun trySave(refresh: String): Boolean = try {
        secrets.put(secretKey, refresh)
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Drops the saved login and enters [TwitchAuthStatus.LoginNeeded]. Caller holds [stateLock]. */
    private fun forget(reason: String) {
        pendingRefresh = null
        try {
            secrets.remove(secretKey)
        } catch (_: IOException) {
            // Left on disk for now; the status below stops it being used, and a new login replaces it.
        } catch (_: SecurityException) {
        }
        generation++
        cached = null
        _status.value = TwitchAuthStatus.LoginNeeded(reason)
    }

    /** Twitch says the login is gone (EventSub `authorization_revoked`, or a 401 that a refresh did not cure). */
    fun markLoginNeeded(reason: String) = synchronized(stateLock) { forget(reason) }

    /**
     * Forgets the login. The saved refresh token is removed first: when that throws (the secrets file cannot be
     * written), nothing has changed and the login stays.
     */
    fun logout() = synchronized(stateLock) {
        secrets.remove(secretKey)
        generation++
        cached = null
        pendingRefresh = null
        _status.value = TwitchAuthStatus.LoggedOut
    }

    /** POSTs a form to the id server; null on a network error or timeout. */
    private suspend fun post(path: String, form: io.ktor.http.ParametersBuilder.() -> Unit): Pair<HttpStatusCode, String>? = try {
        withTimeout(requestTimeoutMs) {
            val r = http.submitForm("$idBaseUrl$path", parameters(form))
            r.status to r.bodyAsText()
        }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
