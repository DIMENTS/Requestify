package nl.diments.requestify.ui.setup

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.diments.requestify.core.settings.ChatCommandNames
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.auth.DeviceCode
import nl.diments.requestify.core.twitch.auth.DeviceLoginResult
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus

/** Where a Twitch login (device code) stands. The card shows its own message for each, never Twitch's text. */
sealed interface TwitchLoginState {
    data object Idle : TwitchLoginState

    /** Asking Twitch for a code. */
    data object Requesting : TwitchLoginState

    /** The user has to enter [code] at Twitch; the app waits until they do. */
    data class ShowingCode(val code: DeviceCode) : TwitchLoginState

    /** The code ran out before the user confirmed. */
    data object Expired : TwitchLoginState

    data object Failed : TwitchLoginState

    /** Twitch could not be reached for a code. */
    data object Unreachable : TwitchLoginState
}

/** The seven chat commands, in the order the card shows them. */
enum class CommandField(val get: (ChatCommandNames) -> String, val set: (ChatCommandNames, String) -> ChatCommandNames) {
    Request({ it.request }, { n, v -> n.copy(request = v) }),
    Song({ it.song }, { n, v -> n.copy(song = v) }),
    Queue({ it.queue }, { n, v -> n.copy(queue = v) }),
    WrongSong({ it.wrongSong }, { n, v -> n.copy(wrongSong = v) }),
    Skip({ it.skip }, { n, v -> n.copy(skip = v) }),
    Remove({ it.remove }, { n, v -> n.copy(remove = v) }),
    Blacklist({ it.blacklist }, { n, v -> n.copy(blacklist = v) }),
}

enum class CommandError { Blank, Duplicate }

/** A command name as it is stored: without the `!`, trimmed and lower-case. */
fun normalizeCommandName(raw: String): String = raw.trim().trimStart('!').trim().lowercase()

/** What is wrong with [names] (as typed): blank names, and every name another command uses too. Empty when fine. */
fun commandNameErrors(names: ChatCommandNames): Map<CommandField, CommandError> {
    val normalized = CommandField.entries.associateWith { normalizeCommandName(it.get(names)) }
    val counts = normalized.values.groupingBy { it }.eachCount()
    return normalized.mapNotNull { (field, name) ->
        when {
            name.isEmpty() -> field to CommandError.Blank
            counts.getValue(name) > 1 -> field to CommandError.Duplicate
            else -> null
        }
    }.toMap()
}

const val BITS_MINIMUM_MIN = 1
const val BITS_MINIMUM_MAX = 10_000

/**
 * The Twitch card: the device-code login, the logged-in account and the chat and Bits settings. Runs on the caller's
 * [scope] (an app-lifetime scope on the UI thread). Every setting is saved at once; one that cannot be saved is not
 * applied and [saveFailed] says so until a later change saves.
 */
class TwitchSetupViewModel(
    private val auth: TwitchAuth,
    private val api: TwitchApi,
    settings: SettingsStore,
    private val scope: CoroutineScope,
    private val openBrowser: (String) -> Unit,
    private val copyToClipboard: (String) -> Unit,
) {
    private val store = settings
    private val _login = MutableStateFlow<TwitchLoginState>(TwitchLoginState.Idle)
    private val _commands = MutableStateFlow(settings.settings.value.twitch.commands)
    private val _commandErrors = MutableStateFlow<Map<CommandField, CommandError>>(emptyMap())
    private val _saveFailed = MutableStateFlow(false)
    private var loginJob: Job? = null

    val login: StateFlow<TwitchLoginState> = _login.asStateFlow()
    val settings: StateFlow<Settings> = store.settings
    val authStatus: StateFlow<TwitchAuthStatus> = auth.status

    /** The command names as typed (the fields show these), which can be ahead of what is saved while one is refused. */
    val commands: StateFlow<ChatCommandNames> = _commands.asStateFlow()

    /** Why the typed command names are not saved, per field; empty when they are. */
    val commandErrors: StateFlow<Map<CommandField, CommandError>> = _commandErrors.asStateFlow()

    /** The last change could not be saved (e.g. another program holds the file); nothing changed. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /** Asks Twitch for a code and waits for the user to confirm it, then saves the account. */
    fun onLogin() {
        if (_login.value is TwitchLoginState.Requesting || _login.value is TwitchLoginState.ShowingCode) return
        _login.value = TwitchLoginState.Requesting
        loginJob = scope.launch {
            try {
                val code = auth.startDeviceLogin()
                if (code == null) {
                    _login.value = TwitchLoginState.Unreachable
                    return@launch
                }
                _login.value = TwitchLoginState.ShowingCode(code)
                _login.value = when (val result = auth.awaitDeviceLogin(code)) {
                    is DeviceLoginResult.Success -> if (saveAccount(result)) TwitchLoginState.Idle else TwitchLoginState.Failed
                    DeviceLoginResult.Expired -> TwitchLoginState.Expired
                    is DeviceLoginResult.Failed -> TwitchLoginState.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _login.value = TwitchLoginState.Failed
            }
        }
    }

    /** Stops waiting for the user; the code is simply left to run out. */
    fun onCancelLogin() {
        loginJob?.cancel()
        loginJob = null
        _login.value = TwitchLoginState.Idle
    }

    /** Opens the page that asks for the code; its address already carries the code. */
    fun onOpenBrowser() {
        (_login.value as? TwitchLoginState.ShowingCode)?.let { openBrowser(it.code.verificationUri) }
    }

    fun onCopyCode() {
        (_login.value as? TwitchLoginState.ShowingCode)?.let { copyToClipboard(it.code.userCode) }
    }

    /** Forgets the login and the account. When the saved login cannot be removed, nothing changes and [saveFailed] is set. */
    fun onLogout() {
        try {
            auth.logout()
        } catch (_: IOException) {
            _saveFailed.value = true
            return
        } catch (_: SecurityException) {
            _saveFailed.value = true
            return
        }
        _login.value = TwitchLoginState.Idle
        save { it.copy(twitch = it.twitch.copy(account = null)) }
    }

    fun onChatEnabled(on: Boolean) = save { it.copy(twitch = it.twitch.copy(chatEnabled = on)) }

    fun onChatPermission(p: ChatPermission) = save { it.copy(twitch = it.twitch.copy(chatPermission = p)) }

    fun onBitsEnabled(on: Boolean) = save { it.copy(twitch = it.twitch.copy(bitsEnabled = on)) }

    fun onBitsMinimum(value: Int) =
        save { it.copy(twitch = it.twitch.copy(bitsMinimum = value.coerceIn(BITS_MINIMUM_MIN, BITS_MINIMUM_MAX))) }

    /**
     * Applies [edit] to the command names as typed. They are saved (normalised) only when none is blank and none is
     * used twice; otherwise [commandErrors] says which, and what is saved stays as it was.
     */
    fun onCommandName(edit: (ChatCommandNames) -> ChatCommandNames) {
        val typed = edit(_commands.value)
        _commands.value = typed
        val errors = commandNameErrors(typed)
        _commandErrors.value = errors
        if (errors.isNotEmpty()) return
        val normalized = CommandField.entries.fold(typed) { names, field -> field.set(names, normalizeCommandName(field.get(names))) }
        if (normalized != store.settings.value.twitch.commands) save { it.copy(twitch = it.twitch.copy(commands = normalized)) }
    }

    /** Looks the account up (its name and Affiliate/Partner status), then saves it. False when it cannot be saved. */
    private suspend fun saveAccount(login: DeviceLoginResult.Success): Boolean {
        // The id server's answer is enough to know who logged in; the profile only adds the display name and type.
        val channel = (api.me() as? TwitchResult.Ok)?.value?.takeIf { it.id == login.userId }
        val account = TwitchAccount(
            userId = login.userId,
            login = login.login,
            displayName = channel?.displayName ?: login.login,
            broadcasterType = channel?.broadcasterType.orEmpty(),
        )
        if (save { it.copy(twitch = it.twitch.copy(account = account)) }) return true
        // Without the account the login is no use; undo it so the user can simply try again.
        try {
            auth.logout()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
        return false
    }

    private fun save(change: (Settings) -> Settings): Boolean {
        val saved = store.tryUpdate(change)
        _saveFailed.update { !saved }
        return saved
    }
}
