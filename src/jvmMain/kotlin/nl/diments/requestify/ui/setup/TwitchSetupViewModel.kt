package nl.diments.requestify.ui.setup

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import nl.diments.requestify.core.twitch.Reward
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.auth.DeviceCode
import nl.diments.requestify.core.twitch.auth.DeviceLoginResult
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.core.twitch.points.RewardActionResult
import nl.diments.requestify.core.twitch.points.RewardManager
import nl.diments.requestify.core.twitch.points.ScannedReward

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

enum class CommandError { Blank, Duplicate, Whitespace }

/** A command name as it is stored: without the `!`, trimmed and lower-case. */
fun normalizeCommandName(raw: String): String = raw.trim().trimStart('!').trim().lowercase()

/**
 * What is wrong with [names] (as typed): blank names, names with a space inside (a command is the first word of a
 * message, so those could never match), and every name another command uses too. Empty when fine.
 */
fun commandNameErrors(names: ChatCommandNames): Map<CommandField, CommandError> {
    val normalized = CommandField.entries.associateWith { normalizeCommandName(it.get(names)) }
    val counts = normalized.values.groupingBy { it }.eachCount()
    return normalized.mapNotNull { (field, name) ->
        when {
            name.isEmpty() -> field to CommandError.Blank
            name.any { it.isWhitespace() } -> field to CommandError.Whitespace
            counts.getValue(name) > 1 -> field to CommandError.Duplicate
            else -> null
        }
    }.toMap()
}

/** The most a logout waits for Twitch to pause Requestify's own reward. */
const val LOGOUT_PAUSE_TIMEOUT_MS = 3_000L

const val BITS_MINIMUM_MIN = 1
const val BITS_MINIMUM_MAX = 10_000

const val REWARD_COST_MIN = 1
const val REWARD_COST_MAX = 1_000_000

/** Twitch's longest reward name. */
const val REWARD_TITLE_MAX = 45

/** What is wrong with the last Channel Points action; the card says it in its own words. */
enum class RewardProblem { DuplicateTitle, NotAffiliate, Failed }

/** The channel's rewards as the "Choose reward" sheet shows them, in its two groups. */
sealed interface RewardScan {
    data object Scanning : RewardScan

    /** Twitch could not list the rewards. */
    data object Failed : RewardScan

    data class Done(val own: List<ScannedReward>, val other: List<ScannedReward>) : RewardScan
}

/**
 * The open "Choose reward" sheet. "Create new reward" does not wait for the [scan]; [problem] is why the last create
 * did not work, [creating] while Twitch is being asked.
 */
data class RewardSheet(val scan: RewardScan, val creating: Boolean = false, val problem: RewardProblem? = null)

/** "Copy as Requestify reward": the reward being copied and the name the copy gets (editable). */
data class CopyDraft(val from: Reward, val title: String, val problem: RewardProblem? = null, val busy: Boolean = false)

/**
 * The Twitch card: the device-code login, the logged-in account and the chat and Bits settings. Runs on the caller's
 * [scope] (an app-lifetime scope on the UI thread). Every setting is saved at once; one that cannot be saved is not
 * applied and [saveFailed] says so until a later change saves.
 */
class TwitchSetupViewModel(
    private val auth: TwitchAuth,
    private val api: TwitchApi,
    private val rewards: RewardManager,
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
    private var logoutJob: Job? = null
    private val _sheet = MutableStateFlow<RewardSheet?>(null)
    private val _copyDraft = MutableStateFlow<CopyDraft?>(null)
    private val _linkWarning = MutableStateFlow<ScannedReward?>(null)
    private val _rewardProblem = MutableStateFlow<RewardProblem?>(null)
    private var scanJob: Job? = null

    val login: StateFlow<TwitchLoginState> = _login.asStateFlow()
    val settings: StateFlow<Settings> = store.settings
    val authStatus: StateFlow<TwitchAuthStatus> = auth.status

    /** The command names as typed (the fields show these), which can be ahead of what is saved while one is refused. */
    val commands: StateFlow<ChatCommandNames> = _commands.asStateFlow()

    /** Why the typed command names are not saved, per field; empty when they are. */
    val commandErrors: StateFlow<Map<CommandField, CommandError>> = _commandErrors.asStateFlow()

    /** The last change could not be saved (e.g. another program holds the file); nothing changed. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /** Whether requests are open; Requestify's own reward is paused while they are not. */
    val requestsOpen: StateFlow<Boolean> get() = rewards.requestsOpen

    /** Twitch still has the reward switched or paused differently from what the app wants; it keeps retrying. */
    val rewardOutOfSync: StateFlow<Boolean> get() = rewards.outOfSync

    /** The "Choose reward" sheet; null while it is closed. */
    val rewardSheet: StateFlow<RewardSheet?> = _sheet.asStateFlow()
    val copyDraft: StateFlow<CopyDraft?> = _copyDraft.asStateFlow()

    /** The foreign reward the user wants to link directly; the card shows the limits first. */
    val linkWarning: StateFlow<ScannedReward?> = _linkWarning.asStateFlow()

    /** Why the last edit or "Create again" did not work. */
    val rewardProblem: StateFlow<RewardProblem?> = _rewardProblem.asStateFlow()

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
                    // Past this point the user has confirmed: the account step is not cut short by a cancel.
                    is DeviceLoginResult.Success ->
                        if (withContext(NonCancellable) { saveAccount(result) }) TwitchLoginState.Idle else TwitchLoginState.Failed
                    DeviceLoginResult.Expired -> TwitchLoginState.Expired
                    is DeviceLoginResult.Failed -> TwitchLoginState.Failed
                }
            } catch (e: CancellationException) {
                // Cancelled while the login was being completed inside TwitchAuth: it may be logged in already,
                // and without an account that login is half of one. Undo it.
                withContext(NonCancellable) { undoLoginWithoutAccount() }
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

    /**
     * Pauses Requestify's own reward while the login still works (at most [LOGOUT_PAUSE_TIMEOUT_MS]; nothing would
     * answer its redemptions after the logout), then forgets the login and the account. When the saved login cannot
     * be removed, nothing changes and [saveFailed] is set.
     */
    fun onLogout() {
        if (logoutJob?.isActive == true) return
        logoutJob = scope.launch {
            try {
                withTimeoutOrNull(LOGOUT_PAUSE_TIMEOUT_MS) { rewards.pauseForExit() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: the logout goes on.
            }
            logOut()
        }
    }

    private fun logOut() {
        val account = store.settings.value.twitch.account
        // The account goes first: when that cannot be saved nothing has changed, and the login is not half undone.
        if (!save { it.copy(twitch = it.twitch.copy(account = null)) }) return
        try {
            auth.logout()
        } catch (_: IOException) {
            restoreAfterFailedLogout(account)
            return
        } catch (_: SecurityException) {
            restoreAfterFailedLogout(account)
            return
        }
        _login.value = TwitchLoginState.Idle
    }

    /** The saved login could not be removed: it stays, so the account that goes with it comes back. */
    private fun restoreAfterFailedLogout(account: TwitchAccount?) {
        if (account != null) store.tryUpdate { it.copy(twitch = it.twitch.copy(account = account)) }
        _saveFailed.value = true
    }

    private fun undoLoginWithoutAccount() {
        if (auth.status.value != TwitchAuthStatus.LoggedIn || store.settings.value.twitch.account != null) return
        try {
            auth.logout()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    fun onChatEnabled(on: Boolean) = save { it.copy(twitch = it.twitch.copy(chatEnabled = on)) }

    fun onChatPermission(p: ChatPermission) = save { it.copy(twitch = it.twitch.copy(chatPermission = p)) }

    fun onBitsEnabled(on: Boolean) = save { it.copy(twitch = it.twitch.copy(bitsEnabled = on)) }

    fun onBitsMinimum(value: Int) =
        save { it.copy(twitch = it.twitch.copy(bitsMinimum = value.coerceIn(BITS_MINIMUM_MIN, BITS_MINIMUM_MAX))) }

    /** The Channel Points switch. Switching on without a reward opens the "Choose reward" sheet. */
    fun onPointsEnabled(on: Boolean) {
        scope.launch {
            rewards.setEnabled(on)
            if (!on) {
                closeSheet()
                return@launch
            }
            val points = store.settings.value.twitch.channelPoints
            if (points.rewardId == null) onChooseReward()
        }
    }

    /** Opens the "Choose reward" sheet and scans the channel's rewards for it. */
    fun onChooseReward() {
        scanJob?.cancel()
        _sheet.update { (it ?: RewardSheet(RewardScan.Scanning)).copy(scan = RewardScan.Scanning) }
        scanJob = scope.launch {
            val scanned = rewards.scan()
            val scan = if (scanned == null) {
                RewardScan.Failed
            } else {
                RewardScan.Done(own = scanned.filter { it.manageable }, other = scanned.filterNot { it.manageable })
            }
            _sheet.update { it?.copy(scan = scan) }
        }
    }

    fun onCloseSheet() = closeSheet()

    private fun closeSheet() {
        scanJob?.cancel()
        scanJob = null
        _sheet.value = null
    }

    /** "Create new reward" in the sheet. */
    fun onCreateReward() {
        val sheet = _sheet.value ?: return
        if (sheet.creating) return
        _sheet.value = sheet.copy(creating = true, problem = null)
        scope.launch {
            when (val result = rewards.createNew()) {
                RewardActionResult.Ok -> closeSheet()
                else -> _sheet.update { it?.copy(creating = false, problem = result.problem()) }
            }
        }
    }

    /** Uses one of Requestify's own rewards that Twitch lists (e.g. from before a reinstall): no duplicate is made. */
    fun onUseOwnReward(reward: ScannedReward) {
        rewards.link(reward)
        closeSheet()
    }

    /** "Link directly": the warning with the limits comes first; [onConfirmLink] does it. */
    /** Only a reward that asks viewers for text can be linked: without it a redemption carries no song. */
    fun onLinkDirectly(reward: ScannedReward) {
        if (!reward.reward.isUserInputRequired) return
        _linkWarning.value = reward
    }

    fun onConfirmLink() {
        val reward = _linkWarning.value ?: return
        _linkWarning.value = null
        rewards.link(reward)
        closeSheet()
    }

    fun onDismissLinkWarning() {
        _linkWarning.value = null
    }

    /** "Copy as Requestify reward" of one of the channel's rewards: asks for the name of the copy first. */
    fun onCopyReward(reward: Reward) {
        _copyDraft.value = CopyDraft(reward, reward.title)
    }

    /** The same for the reward that is linked directly now. */
    fun onCopyLinkedReward() {
        val p = store.settings.value.twitch.channelPoints
        val id = p.rewardId ?: return
        onCopyReward(
            Reward(
                id = id,
                title = p.title.orEmpty(),
                cost = p.cost,
                prompt = p.prompt.orEmpty(),
                isEnabled = true,
                isPaused = false,
                isUserInputRequired = true,
                skipsRequestQueue = false,
            ),
        )
    }

    fun onCopyTitle(title: String) {
        _copyDraft.update { it?.copy(title = title.take(REWARD_TITLE_MAX), problem = null) }
    }

    fun onConfirmCopy() {
        val draft = _copyDraft.value ?: return
        if (draft.busy || draft.title.isBlank()) return
        _copyDraft.value = draft.copy(busy = true, problem = null)
        scope.launch {
            when (val result = rewards.copy(draft.from, draft.title)) {
                RewardActionResult.Ok -> {
                    _copyDraft.value = null
                    closeSheet()
                }
                else -> _copyDraft.update { it?.copy(busy = false, problem = result.problem()) }
            }
        }
    }

    fun onDismissCopy() {
        _copyDraft.value = null
    }

    /** "Create again" after the reward was removed on Twitch. */
    fun onCreateAgain() {
        _rewardProblem.value = null
        scope.launch { _rewardProblem.value = rewards.createNew().problem() }
    }

    /** Writes a changed name, cost or text of Requestify's own reward to Twitch at once. */
    fun onRewardEdit(title: String? = null, cost: Int? = null, prompt: String? = null) {
        _rewardProblem.value = null
        scope.launch {
            _rewardProblem.value = rewards.edit(
                title = title?.takeIf { it.isNotBlank() },
                cost = cost?.coerceIn(REWARD_COST_MIN, REWARD_COST_MAX),
                prompt = prompt,
            ).problem()
        }
    }

    private fun RewardActionResult.problem(): RewardProblem? = when (this) {
        RewardActionResult.Ok -> null
        RewardActionResult.DuplicateTitle -> RewardProblem.DuplicateTitle
        RewardActionResult.NotAffiliate -> RewardProblem.NotAffiliate
        RewardActionResult.Failed -> RewardProblem.Failed
    }

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
