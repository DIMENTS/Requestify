package nl.diments.requestify.ui.setup

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import nl.diments.requestify.core.twitch.auth.TwitchAuth
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
) {
    val opened = mutableListOf<String>()
    val copied = mutableListOf<String>()
    val secrets = InMemorySecretStore().apply { if (loggedIn) put(SecretKeys.TWITCH_REFRESH_TOKEN, "r0") }
    val auth = TwitchAuth(twitch.http, secrets, clock)
    val settings = SettingsStore(JsonFileStore(dir.resolve("twitch-settings.json"), Settings.serializer()) { Settings() })
        .apply { update { it.copy(twitch = TwitchSettings(account = account, channelPoints = points)) } }
    private val api = TwitchApi(
        twitch.http,
        token = { force -> auth.token(force) },
        broadcasterId = { settings.settings.value.twitch.account?.userId },
    )
    /** Whether requests are open, and the events Twitch sends about the reward. */
    val requestsOpen = MutableStateFlow(true)
    val rewardEvents = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 16)
    val rewards = RewardManager(helix.api, settings, rewardEvents, {}, requestsOpen, { AppLanguage.English }, scope)
    val vm = TwitchSetupViewModel(auth, api, rewards, settings, scope, openBrowser = { opened += it }, copyToClipboard = { copied += it })
}
