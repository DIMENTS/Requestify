package nl.diments.requestify

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import nl.diments.requestify.core.blacklist.Blacklist
import nl.diments.requestify.core.blacklist.BlacklistStore
import nl.diments.requestify.core.intake.IncomingRequest
import nl.diments.requestify.core.intake.PendingResolveStore
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.intake.RejectedStore
import nl.diments.requestify.core.intake.RequestIntake
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.isFreeViewerRequest
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.RemoveReason
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
import nl.diments.requestify.core.stream.RequestsGate
import nl.diments.requestify.core.stream.StreamState
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchSession
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.KtorEventSubConnector
import nl.diments.requestify.core.twitch.points.RewardManager
import nl.diments.requestify.ui.blacklist.BlacklistViewModel
import nl.diments.requestify.ui.home.HomeViewModel
import nl.diments.requestify.ui.setup.SpotifySetupViewModel
import nl.diments.requestify.ui.setup.TwitchSetupViewModel

/** The most the exit waits for Twitch to pause the reward. */
internal const val EXIT_PAUSE_TIMEOUT_MS = 3_000L

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

    /**
     * Whether a Twitch account is set up: true even while its login is dead, so a Twitch streamer's requests never
     * fall back to the plain default-open switch.
     */
    private val twitchConnected: StateFlow<Boolean> = settings.settings
        .map { it.twitch.account != null }
        .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.twitch.account != null)

    /** Whether requests are open; at the stream's real end it clears the free viewer requests. */
    val gate: RequestsGate = RequestsGate(
        file = JsonFileStore(paths.streamFile, StreamState.serializer()) { StreamState() },
        twitchConnected = twitchConnected,
        scope = scope,
        clock = System::currentTimeMillis,
        onStreamEnded = { controller.removeMatching(RemoveReason.StreamEnded) { it.filter(Request::isFreeViewerRequest) } },
    )

    private val controller = PlaybackController(
        player = spotify,
        store = QueueStore(JsonFileStore(paths.queueFile, PersistedPlayback.serializer()) { PersistedPlayback() }),
        requestsOpen = gate.open,
        paidPriority = { settings.settings.value.paidPriority },
        scope = scope,
        clock = System::currentTimeMillis,
        ioDispatcher = Dispatchers.IO,
    )

    val blacklist = BlacklistStore(JsonFileStore(paths.blacklistFile, Blacklist.serializer()) { Blacklist() })
    val rejected = RejectedStore(JsonFileStore(paths.rejectedFile, ListSerializer(RejectedEntry.serializer())) { emptyList() })
    private val pending = PendingResolveStore(
        JsonFileStore(paths.pendingFile, ListSerializer(IncomingRequest.serializer())) { emptyList() },
    )

    val intake = RequestIntake(
        controller = controller,
        catalog = spotify,
        settings = settings,
        blacklist = blacklist,
        rejected = rejected,
        pending = pending,
        requestsOpen = gate.open,
        scope = scope,
        clock = System::currentTimeMillis,
    )

    val home = HomeViewModel(controller, spotify, settings, intake, rejected, scope, clock = System::currentTimeMillis)

    val spotifySetup = SpotifySetupViewModel(settings, auth, spotify, controller, scope)

    private val twitchAuth = TwitchAuth(http, secrets, clock = System::currentTimeMillis)

    private val twitchApi = TwitchApi(
        http = http,
        token = { force -> twitchAuth.token(force) },
        broadcasterId = { settings.settings.value.twitch.account?.userId },
    )

    /** Built, not started: the Twitch session starts it while logged in. */
    internal val eventSub: EventSubClient = EventSubClient(
        connector = KtorEventSubConnector(http),
        api = twitchApi,
        broadcasterId = { settings.settings.value.twitch.account?.userId },
        rewardId = { settings.settings.value.twitch.channelPoints.rewardId },
        onAuthorizationRevoked = { twitchSession.onAuthorizationRevoked() },
        scope = scope,
        clock = System::currentTimeMillis,
    )

    /** Keeps EventSub and the login in step, and reports Twitch's health to the connection chip. */
    val twitchSession: TwitchSession = TwitchSession(twitchAuth, twitchApi, eventSub, settings, scope, clock = System::currentTimeMillis)

    val blacklistScreen = BlacklistViewModel(
        store = blacklist,
        intake = intake,
        catalog = spotify,
        lookupViewer = twitchApi::userByLogin,
        twitchConnected = twitchSession.connected,
        scope = scope,
    )

    /** Requestify's Channel Points reward: created or linked from the Twitch card, paused with the requests. */
    private val rewardManager = RewardManager(
        api = twitchApi,
        settings = settings,
        eventSub = eventSub,
        requestsOpen = gate.open,
        language = { settings.settings.value.language },
        scope = scope,
    )

    private val appScope = scope
    private var rewardJob: Job? = null

    val twitchSetup = TwitchSetupViewModel(
        auth = twitchAuth,
        api = twitchApi,
        rewards = rewardManager,
        settings = settings,
        scope = scope,
        openBrowser = { url -> browse(url) },
        copyToClipboard = ::copyToClipboard,
    )

    /**
     * Resumes the offline grace (or ends a stream whose grace ran out while the app was off), starts polling Spotify
     * and resumes the paid requests that waited for it.
     */
    fun start() {
        gate.start()
        controller.start()
        intake.start()
        twitchSession.start()
        // The reward follows Twitch's connection: its pausing and its events need a login.
        rewardJob = appScope.launch {
            twitchSession.connected.collect { connected -> if (connected) rewardManager.start() else rewardManager.stop() }
        }
    }

    /**
     * Stops polling Spotify and disconnects from Twitch. A queue save still in flight when the app exits right after
     * can be cut off; the atomic write then keeps the previous file, so at most the last change is lost.
     */
    fun stop() {
        rewardJob?.cancel()
        rewardJob = null
        rewardManager.stop()
        twitchSession.stop()
        controller.stop()
    }

    /**
     * The exit: pauses Requestify's own Channel Points reward (when it is switched on, so viewers cannot redeem it
     * while nobody is listening), then [stop]s everything. The pause gets [EXIT_PAUSE_TIMEOUT_MS] at most.
     */
    suspend fun stopGracefully() {
        withTimeoutOrNull(EXIT_PAUSE_TIMEOUT_MS) { withContext(Dispatchers.IO) { rewardManager.pauseForExit() } }
        stop()
    }

    /** Opens the Spotify login page in the default browser, or shows its address when that is not possible. */
    private fun openLoginPage(url: String) {
        if (!browse(url)) spotifySetup.showLoginUrl(url)
    }

    /** Opens [url] in the default browser; false when that is not possible. */
    private fun browse(url: String): Boolean = try {
        val canBrowse = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        if (canBrowse) Desktop.getDesktop().browse(URI(url))
        canBrowse
    } catch (_: Exception) {
        false
    }

    /** Puts [text] on the system clipboard; nothing happens when another program holds it. */
    private fun copyToClipboard(text: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (_: Exception) {
        }
    }
}
