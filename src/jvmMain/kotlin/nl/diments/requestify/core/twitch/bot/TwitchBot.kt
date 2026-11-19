package nl.diments.requestify.core.twitch.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.DropReason
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.core.twitch.chat.DropTracker

/**
 * What is wrong with the bot, for the title-bar chip, the tray and the notifier: its login was refused
 * ([loginNeeded]), or the channel does not let it talk ([blockedReason], Twitch's words). Both only while a bot and the
 * streamer's account are set up: a bot that is merely not connected is no problem.
 */
data class TwitchBotHealth(val loginNeeded: Boolean = false, val blockedReason: String? = null)

/**
 * The optional bot account that sends Requestify's chat replies (plan 6): its own login ([auth], with its own secret
 * and scopes), a Helix client on its token ([api], whose broadcaster is the streamer), what Twitch did with its
 * messages ([drops]) and whether it is a moderator in the channel ([moderator]).
 *
 * The bot is used ([sender]) only while it is saved, logged in, and not the streamer's own account; the reply sender
 * falls back to the streamer otherwise. A `Forbidden`, or [blockedAfter] messages in a row that were not sent for a
 * reason other than moderation, mean the channel holds the bot back, until it sends a message again.
 */
class TwitchBot(
    val auth: TwitchAuth,
    val api: TwitchApi,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val validateEveryMs: Long = 3_600_000,
    private val blockedAfter: Int = 3,
) {
    val drops = DropTracker()
    private val forbidden = MutableStateFlow<String?>(null)
    private val _moderator = MutableStateFlow<Boolean?>(null)

    /** Whether the bot is a moderator in the streamer's channel; null while unknown. */
    val moderator: StateFlow<Boolean?> = _moderator.asStateFlow()

    val health: StateFlow<TwitchBotHealth> =
        combine(auth.status, settings.settings, forbidden, drops.dropping, drops.inARow) { status, s, refused, dropping, inARow ->
            healthOf(status, s, refused, dropping, inARow)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            healthOf(auth.status.value, settings.settings.value, null, drops.dropping.value, drops.inARow.value),
        )

    private fun healthOf(status: TwitchAuthStatus, s: Settings, refused: String?, dropping: String?, inARow: Int): TwitchBotHealth {
        if (s.twitch.bot == null || s.twitch.account == null) return TwitchBotHealth()
        return TwitchBotHealth(
            loginNeeded = status != TwitchAuthStatus.LoggedIn,
            blockedReason = refused ?: dropping?.takeIf { inARow >= blockedAfter },
        )
    }

    /** The bot account replies go out as: saved, logged in, and not the streamer's own account; null otherwise. */
    fun sender(): TwitchAccount? {
        val twitch = settings.settings.value.twitch
        val bot = twitch.bot ?: return null
        val account = twitch.account ?: return null
        if (bot.userId == account.userId) return null
        return bot.takeIf { auth.status.value == TwitchAuthStatus.LoggedIn }
    }

    /** The bot's message was sent: whatever held it back is over. */
    fun onSent() {
        forbidden.value = null
        drops.sent()
    }

    fun onDropped(reason: DropReason) = drops.dropped(reason)

    /** Twitch does not let the bot talk in the channel (banned, timed out, ...): held back at once. */
    fun onForbidden(message: String) {
        val reason = message.ifBlank { "forbidden" }
        drops.dropped(DropReason("forbidden", reason))
        forbidden.value = reason
    }

    /** Twitch refused the bot's token even after a refresh: only a new bot login helps. */
    fun onLoginRefused() = auth.markLoginNeeded("Twitch refused the bot's login")

    /** Asks Twitch whether the bot moderates the streamer's channel; anything but an answer leaves [moderator] as is. */
    suspend fun checkModerator() {
        val bot = settings.settings.value.twitch.bot ?: return
        val result = api.moderates(bot.userId)
        if (result is TwitchResult.Ok) _moderator.value = result.value
    }

    /** Forgets what was seen of a bot: after connecting one, or disconnecting it. */
    fun reset() {
        forbidden.value = null
        drops.reset()
        _moderator.value = null
    }

    private var job: Job? = null

    /**
     * Validates the bot's token now and every [validateEveryMs] while it is logged in and saved (Twitch requires this
     * of every token in use), and looks at its moderator status after each good validation. Does nothing while started.
     */
    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(auth.status, settings.settings) { status, s -> status == TwitchAuthStatus.LoggedIn && s.twitch.bot != null }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    while (true) {
                        validate()
                        delay(validateEveryMs)
                    }
                }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun validate() {
        try {
            if (auth.validate() is TokenResult.Ok) checkModerator()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next round tries again.
        }
    }
}
