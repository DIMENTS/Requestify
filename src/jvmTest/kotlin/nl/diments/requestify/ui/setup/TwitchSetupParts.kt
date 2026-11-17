package nl.diments.requestify.ui.setup

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.settings.TwitchSettings
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.FakeTwitch
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.auth.TwitchAuth

/** A [TwitchSetupViewModel] under test on a [FakeTwitch], with what it opened and copied. */
internal class TwitchSetupParts(
    dir: Path,
    scope: CoroutineScope,
    val twitch: FakeTwitch = FakeTwitch(),
    loggedIn: Boolean = false,
    account: TwitchAccount? = null,
    clock: () -> Long = { 0L },
) {
    val opened = mutableListOf<String>()
    val copied = mutableListOf<String>()
    val secrets = InMemorySecretStore().apply { if (loggedIn) put(SecretKeys.TWITCH_REFRESH_TOKEN, "r0") }
    val auth = TwitchAuth(twitch.http, secrets, clock)
    val settings = SettingsStore(JsonFileStore(dir.resolve("twitch-settings.json"), Settings.serializer()) { Settings() })
        .apply { update { it.copy(twitch = TwitchSettings(account = account)) } }
    private val api = TwitchApi(
        twitch.http,
        token = { force -> auth.token(force) },
        broadcasterId = { settings.settings.value.twitch.account?.userId },
    )
    val vm = TwitchSetupViewModel(auth, api, settings, scope, openBrowser = { opened += it }, copyToClipboard = { copied += it })
}
