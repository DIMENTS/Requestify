package nl.diments.requestify.ui.setup

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.spotify.auth.LOGIN_FAILED_PORT_IN_USE
import nl.diments.requestify.core.spotify.auth.LoginResult
import nl.diments.requestify.core.spotify.auth.SpotifyAuth
import nl.diments.requestify.core.spotify.auth.SpotifyAuthStatus

private val ClientIdPattern = Regex("^[0-9a-fA-F]{32}$")

/** A Spotify Client ID is 32 hex characters; surrounding whitespace (from pasting) is ignored. */
fun isValidSpotifyClientId(s: String): Boolean = ClientIdPattern.matches(s.trim())

sealed interface PremiumCheck {
    data object Premium : PremiumCheck
    data object NotPremium : PremiumCheck

    /** Spotify did not say; playback reports it when the first track plays. */
    data object Unknown : PremiumCheck
}

/** Why the last login did not succeed. The screen shows its own message for each, never Spotify's text. */
enum class LoginError { TimedOut, PortInUse, Failed }

/** What could not be saved just now (e.g. another program held the file); nothing changed, so it can be retried. */
enum class SaveFailure { ClientId, Logout }

/**
 * Everything the Spotify setup shows. [premium] is null while it is being checked or when not logged in.
 * [pendingLoginUrl] is the login page while a login runs and the browser could not be opened for it.
 * [needsExtraPermission]: the login is from before Requestify asked to read the recently played songs; logging in
 * again (no need to log out first) grants it.
 */
data class SpotifySetupState(
    val clientId: String,
    val clientIdValid: Boolean,
    val auth: SpotifyAuthStatus,
    val loggingIn: Boolean,
    val loginError: LoginError?,
    val premium: PremiumCheck?,
    val health: SpotifyHealth,
    val pendingLoginUrl: String? = null,
    val saveFailure: SaveFailure? = null,
    val needsExtraPermission: Boolean = false,
)

/** Runs on the caller's [scope] (an app-lifetime scope on the UI thread). */
class SpotifySetupViewModel(
    private val settings: SettingsStore,
    private val auth: SpotifyAuth,
    private val catalog: SpotifyCatalog,
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
) {
    private data class Local(
        val clientId: String,
        val loggingIn: Boolean = false,
        val loginError: LoginError? = null,
        val pendingLoginUrl: String? = null,
        val saveFailure: SaveFailure? = null,
    )

    private val local = MutableStateFlow(Local(settings.settings.value.spotifyClientId.orEmpty()))
    private val premiumCheck = MutableStateFlow<PremiumCheck?>(null)
    private var loginJob: Job? = null

    val state: StateFlow<SpotifySetupState> =
        combine(local, auth.status, premiumCheck, controller.health, controller.needsExtraPermission, ::toState).stateIn(
            scope,
            SharingStarted.Eagerly,
            toState(
                local.value,
                auth.status.value,
                premiumCheck.value,
                controller.health.value,
                controller.needsExtraPermission.value,
            ),
        )

    init {
        // Premium is checked whenever Spotify is logged in: right after a login, and at start with a saved one.
        scope.launch {
            auth.status.collectLatest { status ->
                premiumCheck.value = null
                if (status == SpotifyAuthStatus.LoggedIn) premiumCheck.value = checkPremium()
            }
        }
    }

    /** Shows the typed text; saves it (trimmed) only when it is a valid Client ID. */
    fun onClientIdChange(s: String) {
        local.update { it.copy(clientId = s) }
        saveClientId()
    }

    /**
     * Saves the typed Client ID first when an earlier save of it failed, then logs in. Also while logged in: the new
     * login replaces the old one. After every successful login playback checks its permissions again.
     */
    fun onLogin() {
        if (local.value.loggingIn || !saveClientId()) return
        local.update { it.copy(loggingIn = true, loginError = null, pendingLoginUrl = null) }
        loginJob = scope.launch {
            var error: LoginError? = LoginError.Failed
            try {
                val result = auth.login()
                if (result == LoginResult.Success) controller.onLoginChanged()
                error = errorOf(result)
            } catch (e: CancellationException) {
                error = null // cancelled by the streamer: not an error
                throw e
            } catch (_: Exception) {
                // error stays Failed
            } finally {
                // Whatever happened, the spinner ends.
                local.update { it.copy(loggingIn = false, loginError = error, pendingLoginUrl = null) }
            }
        }
    }

    /**
     * Stops a running login, e.g. when Spotify shows an error page and never comes back (a wrong Client ID or a
     * redirect URI that was not saved). The redirect port is freed at once, so a new login can start.
     */
    fun onCancelLogin() {
        loginJob?.cancel()
    }

    /** When the saved login cannot be removed, Spotify stays logged in and the screen says the change failed. */
    fun onLogout() {
        val loggedOut = try {
            auth.logout()
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        local.update { it.copy(loginError = null, saveFailure = if (loggedOut) null else SaveFailure.Logout) }
    }

    /** Saves the typed Client ID when it is valid and new. False when that save failed; nothing changed then. */
    private fun saveClientId(): Boolean {
        val id = local.value.clientId.trim()
        val saved = !isValidSpotifyClientId(id) ||
            settings.settings.value.spotifyClientId == id ||
            settings.tryUpdate { it.copy(spotifyClientId = id) }
        local.update {
            when {
                !saved -> it.copy(saveFailure = SaveFailure.ClientId)
                it.saveFailure == SaveFailure.ClientId -> it.copy(saveFailure = null)
                else -> it
            }
        }
        return saved
    }

    /** The browser could not be opened for [url]: show it so the streamer can open it by hand. */
    fun showLoginUrl(url: String) {
        local.update { if (it.loggingIn) it.copy(pendingLoginUrl = url) else it }
    }

    private fun errorOf(result: LoginResult): LoginError? = when (result) {
        LoginResult.Success -> null
        LoginResult.TimedOut -> LoginError.TimedOut
        is LoginResult.Failed -> if (result.reason == LOGIN_FAILED_PORT_IN_USE) LoginError.PortInUse else LoginError.Failed
    }

    private suspend fun checkPremium(): PremiumCheck = when (val result = catalog.isPremium()) {
        is SpotifyResult.Ok -> when (result.value) {
            true -> PremiumCheck.Premium
            false -> PremiumCheck.NotPremium
            null -> PremiumCheck.Unknown
        }
        SpotifyResult.PremiumRequired -> PremiumCheck.NotPremium
        else -> PremiumCheck.Unknown
    }

    private fun toState(
        local: Local,
        auth: SpotifyAuthStatus,
        premium: PremiumCheck?,
        health: SpotifyHealth,
        needsExtraPermission: Boolean,
    ) = SpotifySetupState(
        clientId = local.clientId,
        clientIdValid = isValidSpotifyClientId(local.clientId),
        auth = auth,
        loggingIn = local.loggingIn,
        loginError = local.loginError,
        premium = premium,
        health = health,
        pendingLoginUrl = local.pendingLoginUrl,
        saveFailure = local.saveFailure,
        needsExtraPermission = needsExtraPermission,
    )
}
