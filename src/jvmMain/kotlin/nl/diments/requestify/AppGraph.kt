package nl.diments.requestify

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyApi
import nl.diments.requestify.core.spotify.auth.LoopbackReceiver
import nl.diments.requestify.core.spotify.auth.SpotifyAuth
import nl.diments.requestify.core.storage.AppJson
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.KtorEventSubConnector
import nl.diments.requestify.ui.home.HomeViewModel
import nl.diments.requestify.ui.setup.SpotifySetupViewModel

/**
 * Builds the app's objects once, for the app's whole life, on [scope] (an app-lifetime scope on the UI thread).
 * Building starts nothing: [start] begins polling Spotify, [stop] ends it.
 */
class AppGraph(
    paths: AppPaths,
    secrets: SecretStore,
    httpEngine: HttpClientEngine = CIO.create(),
    scope: CoroutineScope,
) {
    private val http = HttpClient(httpEngine) {
        install(ContentNegotiation) { json(AppJson.instance) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        // EventSub. The request timeout does not apply to WebSocket sessions; Ktor answers Twitch's pings itself.
        install(WebSockets)
    }

    val settings = SettingsStore(JsonFileStore(paths.settingsFile, Settings.serializer()) { Settings() })

    val auth = SpotifyAuth(
        http = http,
        secrets = secrets,
        clientId = { settings.settings.value.spotifyClientId },
        clock = System::currentTimeMillis,
        openBrowser = ::openLoginPage,
        receiverFactory = { LoopbackReceiver() },
    )

    private val spotify = SpotifyApi(http, token = { force -> auth.token(force) })

    private val controller = PlaybackController(
        player = spotify,
        store = QueueStore(JsonFileStore(paths.queueFile, PersistedPlayback.serializer()) { PersistedPlayback() }),
        requestsOpen = MutableStateFlow(true),
        paidPriority = { settings.settings.value.paidPriority },
        scope = scope,
        clock = System::currentTimeMillis,
        ioDispatcher = Dispatchers.IO,
    )

    val home = HomeViewModel(controller, spotify, settings, scope, clock = System::currentTimeMillis)

    val spotifySetup = SpotifySetupViewModel(settings, auth, spotify, controller, scope)

    private val twitchAuth = TwitchAuth(http, secrets, clock = System::currentTimeMillis)

    private val twitchApi = TwitchApi(
        http = http,
        token = { force -> twitchAuth.token(force) },
        broadcasterId = { settings.settings.value.twitch.account?.userId },
    )

    /** Built, not started: the Twitch session starts it while logged in. */
    internal val eventSub = EventSubClient(
        connector = KtorEventSubConnector(http),
        api = twitchApi,
        broadcasterId = { settings.settings.value.twitch.account?.userId },
        rewardId = { settings.settings.value.twitch.channelPoints.rewardId },
        onAuthorizationRevoked = { twitchAuth.markLoginNeeded("EventSub authorization revoked") },
        scope = scope,
        clock = System::currentTimeMillis,
    )

    /** Starts polling Spotify. */
    fun start() = controller.start()

    /**
     * Stops polling Spotify. A queue save still in flight when the app exits right after can be cut off; the
     * atomic write then keeps the previous file, so at most the last change is lost.
     */
    fun stop() = controller.stop()

    /** Opens the Spotify login page in the default browser, or shows its address when that is not possible. */
    private fun openLoginPage(url: String) {
        val opened = try {
            val canBrowse = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            if (canBrowse) Desktop.getDesktop().browse(URI(url))
            canBrowse
        } catch (_: Exception) {
            false
        }
        if (!opened) spotifySetup.showLoginUrl(url)
    }
}
