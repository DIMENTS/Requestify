package nl.diments.requestify.core.spotify.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.io.IOException
import java.net.BindException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.auth.TokenResult

data class AccessToken(val value: String, val expiresAtEpochMs: Long)

sealed interface SpotifyAuthStatus {
    data object LoggedOut : SpotifyAuthStatus
    data object LoggedIn : SpotifyAuthStatus
    data class LoginNeeded(val reason: String) : SpotifyAuthStatus
}

sealed interface LoginResult {
    data object Success : LoginResult
    data object TimedOut : LoginResult
    data class Failed(val reason: String) : LoginResult
}

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

private const val REFRESH_MARGIN_MS = 60_000L

/** The [LoginResult.Failed.reason] when another app holds the redirect port. */
const val LOGIN_FAILED_PORT_IN_USE = "port $SPOTIFY_REDIRECT_PORT in use"

class SpotifyAuth(
    private val http: HttpClient,
    private val secrets: SecretStore,
    private val clientId: () -> String?,
    private val clock: () -> Long,
    private val openBrowser: (String) -> Unit,
    private val receiverFactory: () -> LoopbackReceiver,
    private val accountsBaseUrl: String = "https://accounts.spotify.com",
    private val requestTimeoutMs: Long = 15_000,
    /** How long a login waits for the browser to come back before it is [LoginResult.TimedOut]. */
    private val loginTimeout: Duration = 5.minutes,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Bumped by logout() and by a successful login(); in-flight refreshes from an older generation are discarded. */
    private var generation = 0L
    private val stateLock = Any()

    @Volatile
    private var cached: AccessToken? = null

    private val _status = MutableStateFlow<SpotifyAuthStatus>(
        if (secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN) != null) SpotifyAuthStatus.LoggedIn else SpotifyAuthStatus.LoggedOut,
    )
    val status: StateFlow<SpotifyAuthStatus> = _status.asStateFlow()

    fun authorizeUrl(challenge: String, state: String): String {
        fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
        val query = listOf(
            "client_id" to clientId().orEmpty(),
            "response_type" to "code",
            "redirect_uri" to SPOTIFY_REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
            "scope" to SPOTIFY_SCOPES.joinToString(" "),
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "$accountsBaseUrl/authorize?$query"
    }

    suspend fun login(): LoginResult {
        val id = clientId()
        if (id.isNullOrBlank()) return LoginResult.Failed("no client id")
        val verifier = Pkce.verifier()
        val state = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val receiver = try {
            receiverFactory()
        } catch (_: BindException) {
            return LoginResult.Failed(LOGIN_FAILED_PORT_IN_USE)
        } catch (e: Exception) {
            return LoginResult.Failed(e.message ?: "redirect receiver failed")
        } catch (e: LinkageError) {
            // E.g. a runtime image without jdk.httpserver: a failed login, never a login that waits forever.
            return LoginResult.Failed(e.toString())
        }
        val code = try {
            openBrowser(authorizeUrl(Pkce.challenge(verifier), state))
            receiver.awaitCode(state, loginTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LoginResult.Failed(e.message ?: "browser launch failed")
        } finally {
            receiver.close()
        }.getOrElse { e ->
            return if (e is TimeoutCancellationException) LoginResult.TimedOut
            else LoginResult.Failed(e.message ?: "authorization failed")
        }
        return exchange(id, code, verifier)
    }

    private suspend fun exchange(id: String, code: String, verifier: String): LoginResult {
        val outcome = try {
            val (status, body) = withTimeout(requestTimeoutMs) {
                val response = http.submitForm(
                    "$accountsBaseUrl/api/token",
                    parameters {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("redirect_uri", SPOTIFY_REDIRECT_URI)
                        append("client_id", id)
                        append("code_verifier", verifier)
                    },
                )
                response.status to response.bodyAsText()
            }
            if (status != HttpStatusCode.OK) {
                return LoginResult.Failed("token endpoint returned ${status.value}")
            }
            json.decodeFromString<TokenResponse>(body)
        } catch (_: TimeoutCancellationException) {
            return LoginResult.Failed("timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LoginResult.Failed(e.message ?: "token exchange failed")
        }
        val refresh = outcome.refreshToken ?: return LoginResult.Failed("no refresh token in response")
        synchronized(stateLock) {
            generation++
            secrets.put(SecretKeys.SPOTIFY_REFRESH_TOKEN, refresh)
            cached = AccessToken(outcome.accessToken, clock() + outcome.expiresIn * 1000)
            _status.value = SpotifyAuthStatus.LoggedIn
        }
        return LoginResult.Success
    }

    /**
     * The access token for an API call, or why there is none. A network problem, a timeout, a rate limit or a
     * Spotify server error is [TokenResult.Unavailable]: the login is kept and the next call tries again. Only
     * an answer that the login itself is refused (any other 4xx, e.g. `invalid_grant` or `invalid_client` when
     * the Spotify app was deleted) forgets it and is [TokenResult.LoginNeeded]; it is not sent again until the
     * next login.
     */
    suspend fun token(forceRefresh: Boolean = false): TokenResult = when (val got = obtain(forceRefresh)) {
        is Obtained.Token -> TokenResult.Ok(got.token.value)
        is Obtained.Missing -> got.why
    }

    /** Like [token] with the expiry; null when there is no token ([status] and [token] tell why). */
    suspend fun accessToken(forceRefresh: Boolean = false): AccessToken? = (obtain(forceRefresh) as? Obtained.Token)?.token

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
        // Spotify already refused this login: asking again cannot help, only a new login can.
        if (_status.value is SpotifyAuthStatus.LoginNeeded) return Obtained.Missing(TokenResult.LoginNeeded)
        val refreshToken = secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN)
        if (refreshToken == null) {
            synchronized(stateLock) {
                if (generation == startGeneration) {
                    cached = null
                    if (_status.value == SpotifyAuthStatus.LoggedIn) _status.value = SpotifyAuthStatus.LoggedOut
                }
            }
            return Obtained.Missing(TokenResult.LoggedOut)
        }
        // Without a Client ID (the settings were lost) nothing can refresh: Spotify must be set up again.
        val id = clientId()
        if (id.isNullOrBlank()) return Obtained.Missing(TokenResult.LoggedOut)
        val response = try {
            withTimeout(requestTimeoutMs) {
                val r = http.submitForm(
                    "$accountsBaseUrl/api/token",
                    parameters {
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                        append("client_id", id)
                    },
                )
                r.status to r.bodyAsText()
            }
        } catch (_: TimeoutCancellationException) {
            return Obtained.Missing(TokenResult.Unavailable("timeout")) // hung connection: transient, keep the login
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network blip: keep the login.
            return Obtained.Missing(TokenResult.Unavailable(e.message ?: e::class.simpleName))
        }
        val (code, body) = response
        // Apply the result only if no logout()/login() happened while the request was in flight.
        return when {
            code == HttpStatusCode.OK -> {
                val token = try {
                    json.decodeFromString<TokenResponse>(body)
                } catch (_: Exception) {
                    return Obtained.Missing(TokenResult.Unavailable("unreadable token"))
                }
                synchronized(stateLock) {
                    if (generation != startGeneration) return@synchronized superseded()
                    // Persist a rotated refresh token BEFORE the new access token is used.
                    token.refreshToken?.let { secrets.put(SecretKeys.SPOTIFY_REFRESH_TOKEN, it) }
                    val access = AccessToken(token.accessToken, clock() + token.expiresIn * 1000)
                    cached = access
                    _status.value = SpotifyAuthStatus.LoggedIn
                    Obtained.Token(access)
                }
            }
            isLoginRefused(code) -> synchronized(stateLock) {
                if (generation != startGeneration) return@synchronized superseded()
                try {
                    secrets.remove(SecretKeys.SPOTIFY_REFRESH_TOKEN)
                } catch (_: IOException) {
                    // Left on disk for now; the status below stops it being used, and a new login replaces it.
                } catch (_: SecurityException) {
                }
                cached = null
                _status.value = SpotifyAuthStatus.LoginNeeded("Spotify refused the saved login (HTTP ${code.value})")
                Obtained.Missing(TokenResult.LoginNeeded)
            }
            // Rate limit or Spotify trouble: keep the login.
            else -> Obtained.Missing(TokenResult.Unavailable("HTTP ${code.value}"))
        }
    }

    /** A 4xx other than a timeout or rate limit: Spotify refuses this login (revoked, expired, app deleted). */
    private fun isLoginRefused(code: HttpStatusCode): Boolean =
        code.value in 400..499 && code != HttpStatusCode.RequestTimeout && code != HttpStatusCode.TooManyRequests

    /** A logout or login happened while a refresh was in flight: its answer is dropped. Caller holds [stateLock]. */
    private fun superseded(): Obtained.Missing = Obtained.Missing(
        when (_status.value) {
            SpotifyAuthStatus.LoggedOut -> TokenResult.LoggedOut
            is SpotifyAuthStatus.LoginNeeded -> TokenResult.LoginNeeded
            SpotifyAuthStatus.LoggedIn -> TokenResult.Unavailable() // a new login: the next call uses its token
        },
    )

    /**
     * Forgets the login. The saved refresh token is removed first: when that throws (the secrets file cannot be
     * written), nothing has changed and the login stays.
     */
    fun logout() = synchronized(stateLock) {
        secrets.remove(SecretKeys.SPOTIFY_REFRESH_TOKEN)
        generation++
        cached = null
        _status.value = SpotifyAuthStatus.LoggedOut
    }
}
