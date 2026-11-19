package nl.diments.requestify.ui.setup

import nl.diments.requestify.core.settings.setForTest
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.settings.ChannelPointsSettings
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.FakeTwitch
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.auth.TWITCH_BOT_SCOPES
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.bot.TwitchBot
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent
import nl.diments.requestify.core.twitch.points.FakeRewardsHelix
import nl.diments.requestify.core.twitch.points.RewardManager

/** A [TwitchSetupViewModel] under test on a [FakeTwitch], with what it opened and copied. */
internal class TwitchSetupParts(
    dir: Path,
    scope: CoroutineScope,
    val twitch: FakeTwitch = FakeTwitch(),
    loggedIn: Boolean = false,
    account: TwitchAccount? = null,
    clock: () -> Long = { 0L },
    points: ChannelPointsSettings = ChannelPointsSettings(),
    val helix: FakeRewardsHelix = FakeRewardsHelix(),
    botAccount: TwitchAccount? = null,
    botLoggedIn: Boolean = botAccount != null,
) {
    val opened = mutableListOf<String>()
    val copied = mutableListOf<String>()
    val secrets = InMemorySecretStore().apply { if (loggedIn) put(SecretKeys.TWITCH_REFRESH_TOKEN, "r0") }
        .apply { if (botLoggedIn) put(SecretKeys.TWITCH_BOT_REFRESH_TOKEN, "b0") }
    val auth = TwitchAuth(twitch.http, secrets, clock)
    val settings = SettingsStore(JsonFileStore(dir.resolve("twitch-settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
        .apply { setForTest { it.copy(twitch = TwitchSettings(account = account, channelPoints = points, bot = botAccount)) } }
    private val api = TwitchApi(
        twitch.http,
        token = { force -> auth.token(force) },
        broadcasterId = { settings.settings.value.twitch.account?.userId },
    )
    /** Whether requests are open, and the events Twitch sends about the reward. */
    val requestsOpen = MutableStateFlow(true)
    val rewardEvents = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 16)
    val rewards = RewardManager(helix.api, settings, rewardEvents, {}, requestsOpen, { AppLanguage.English }, scope, retryFirstMs = 20, retryMaxMs = 80)
    val vm = TwitchSetupViewModel(auth, api, rewards, settings, scope, openBrowser = { opened += it }, copyToClipboard = { copied += it })

    val botAuth = TwitchAuth(twitch.http, secrets, clock, secretKey = SecretKeys.TWITCH_BOT_REFRESH_TOKEN, scopes = TWITCH_BOT_SCOPES)
    val bot = TwitchBot(
        botAuth,
        TwitchApi(twitch.http, token = { force -> botAuth.token(force) }, broadcasterId = { settings.settings.value.twitch.account?.userId }),
        settings,
        scope,
    )
    val botVm = TwitchBotSetupViewModel(bot, settings, scope, openBrowser = { opened += it }, copyToClipboard = { copied += it })
}
