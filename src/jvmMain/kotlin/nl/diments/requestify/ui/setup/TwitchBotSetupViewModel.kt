package nl.diments.requestify.ui.setup

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.auth.DeviceLoginResult
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.core.twitch.bot.TwitchBot

/**
 * The bot section of the Twitch card (Connections and the welcome wizard): connect a bot account with the device
 * code, refuse the streamer's own account, show who replies, whether the bot is a moderator, and the last message
 * Twitch held back. Runs on the caller's [scope] (an app-lifetime scope on the UI thread).
 */
class TwitchBotSetupViewModel(
    private val bot: TwitchBot,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val openBrowser: (String) -> Unit,
    private val copyToClipboard: (String) -> Unit,
) {
    private val _login = MutableStateFlow<TwitchLoginState>(TwitchLoginState.Idle)
    private val _saveFailed = MutableStateFlow(false)
    private var loginJob: Job? = null

    val login: StateFlow<TwitchLoginState> = _login.asStateFlow()

    /** The connected bot account; null while there is none. */
    val account: StateFlow<TwitchAccount?> =
        settings.settings.map { it.twitch.bot }.stateIn(scope, SharingStarted.Eagerly, settings.settings.value.twitch.bot)
    val authStatus: StateFlow<TwitchAuthStatus> = bot.auth.status
    val moderator: StateFlow<Boolean?> = bot.moderator

    /** Why the bot's latest message was not sent, while it is not sending. */
    val lastDrop: StateFlow<String?> = bot.drops.dropping

    /** The last change could not be saved; nothing changed. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /** Asks Twitch for a code and waits until the bot account approves it; the streamer's own account is refused. */
    fun onConnect() {
        if (_login.value is TwitchLoginState.Requesting || _login.value is TwitchLoginState.ShowingCode) return
        _login.value = TwitchLoginState.Requesting
        loginJob = scope.launch {
            try {
                val code = bot.auth.startDeviceLogin()
                if (code == null) {
                    _login.value = TwitchLoginState.Unreachable
                    return@launch
                }
                _login.value = TwitchLoginState.ShowingCode(code)
                val streamer = settings.settings.value.twitch.account?.userId
                _login.value = when (val result = bot.auth.awaitDeviceLogin(code, accept = { it != streamer })) {
                    is DeviceLoginResult.Success ->
                        if (withContext(NonCancellable) { saveBot(result) }) TwitchLoginState.Idle else TwitchLoginState.Failed
                    is DeviceLoginResult.WrongAccount -> TwitchLoginState.OwnAccount
                    DeviceLoginResult.Expired -> TwitchLoginState.Expired
                    is DeviceLoginResult.Failed -> TwitchLoginState.Failed
                }
            } catch (e: CancellationException) {
                // Cancelled while the login was being completed: a bot login without its account is half of one.
                withContext(NonCancellable) { undoLoginWithoutAccount() }
                throw e
            } catch (_: Exception) {
                _login.value = TwitchLoginState.Failed
            }
        }
    }

    /** Stops waiting; the code is simply left to run out. */
    fun onCancel() {
        loginJob?.cancel()
        loginJob = null
        _login.value = TwitchLoginState.Idle
    }

    fun onOpenBrowser() {
        (_login.value as? TwitchLoginState.ShowingCode)?.let { openBrowser(it.code.verificationUri) }
    }

    fun onCopyCode() {
        (_login.value as? TwitchLoginState.ShowingCode)?.let { copyToClipboard(it.code.userCode) }
    }

    /** Copies the link (with the code in it), to paste into a private window where the bot logs in. */
    fun onCopyLink() {
        (_login.value as? TwitchLoginState.ShowingCode)?.let { copyToClipboard(it.code.verificationUri) }
    }

    /**
     * Forgets the bot: its account first (when that cannot be saved nothing changes and [saveFailed] says so), then its
     * login; when the login cannot be removed the account comes back. Replies come from the streamer again.
     */
    fun onDisconnect() {
        val account = settings.settings.value.twitch.bot ?: return
        if (!save { it.copy(twitch = it.twitch.copy(bot = null)) }) return
        try {
            bot.auth.logout()
        } catch (_: IOException) {
            settings.tryUpdate { it.copy(twitch = it.twitch.copy(bot = account)) }
            _saveFailed.value = true
            return
        } catch (_: SecurityException) {
            settings.tryUpdate { it.copy(twitch = it.twitch.copy(bot = account)) }
            _saveFailed.value = true
            return
        }
        bot.reset()
        _login.value = TwitchLoginState.Idle
    }

    /** Copies `/mod <bot>`, to paste into the streamer's own chat. */
    fun onCopyModCommand() {
        settings.settings.value.twitch.bot?.let { copyToClipboard("/mod ${it.login}") }
    }

    /** "Opnieuw kijken": asks Twitch again whether the bot is a moderator (after the streamer typed /mod). */
    fun onCheckModerator() {
        scope.launch { bot.checkModerator() }
    }

    /** Looks up the bot's display name, then saves the account. False (and the login undone) when it cannot be saved. */
    private suspend fun saveBot(login: DeviceLoginResult.Success): Boolean {
        val me = (bot.api.me() as? TwitchResult.Ok)?.value?.takeIf { it.id == login.userId }
        val account = TwitchAccount(userId = login.userId, login = login.login, displayName = me?.displayName ?: login.login)
        if (!save { it.copy(twitch = it.twitch.copy(bot = account)) }) {
            try {
                bot.auth.logout()
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
            return false
        }
        bot.reset()
        bot.checkModerator()
        return true
    }

    private fun undoLoginWithoutAccount() {
        if (bot.auth.status.value != TwitchAuthStatus.LoggedIn || settings.settings.value.twitch.bot != null) return
        try {
            bot.auth.logout()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private fun save(change: (nl.diments.requestify.core.settings.Settings) -> nl.diments.requestify.core.settings.Settings): Boolean {
        val saved = settings.tryUpdate(change)
        _saveFailed.value = !saved
        return saved
    }
}
