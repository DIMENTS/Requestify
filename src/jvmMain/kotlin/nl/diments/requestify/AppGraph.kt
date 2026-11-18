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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
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
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationCursors
import nl.diments.requestify.core.donations.DonationHandler
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationSources
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.donations.donolink.DonoLinkApi
import nl.diments.requestify.core.donations.donolink.DonoLinkSource
import nl.diments.requestify.core.donations.streamelements.AstroClient
import nl.diments.requestify.core.donations.streamelements.KtorAstroConnector
import nl.diments.requestify.core.donations.streamelements.astroHttpClient
import nl.diments.requestify.core.donations.streamelements.SeResult
import nl.diments.requestify.core.donations.streamelements.StreamElementsApi
import nl.diments.requestify.core.donations.streamelements.StreamElementsSource
import nl.diments.requestify.core.donations.streamelements.channelIdFromJwt
import nl.diments.requestify.core.log.BitsGapWatcher
import nl.diments.requestify.core.log.ConnectionSnapshot
import nl.diments.requestify.core.log.LogInputs
import nl.diments.requestify.core.log.LogService
import nl.diments.requestify.core.log.LogStore
import nl.diments.requestify.core.log.Logbook
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.ui.common.TwitchHealth
import nl.diments.requestify.ui.log.LogViewModel
import nl.diments.requestify.ui.setup.DonationsSetupViewModel
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.KtorShortLinkResolver
import nl.diments.requestify.core.spotify.SpotifyApi
import nl.diments.requestify.core.spotify.auth.LoopbackReceiver
import nl.diments.requestify.core.spotify.auth.SpotifyAuth
import nl.diments.requestify.core.storage.AppJson
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.stream.RequestsGate
import nl.diments.requestify.core.stream.StreamState
import nl.diments.requestify.core.twitch.StreamWatcher
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchSession
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.bits.BitsSource
import nl.diments.requestify.core.twitch.chat.ChatReplies
import nl.diments.requestify.core.twitch.chat.ChatReplySender
import nl.diments.requestify.core.twitch.chat.TwitchChat
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubConnector
import nl.diments.requestify.core.twitch.eventsub.KtorEventSubConnector
import nl.diments.requestify.core.twitch.points.ChannelPointsSource
import nl.diments.requestify.core.twitch.points.OutboxEntry
import nl.diments.requestify.core.twitch.points.RedemptionOutbox
import nl.diments.requestify.core.twitch.points.RewardManager
import nl.diments.requestify.ui.blacklist.BlacklistViewModel
import nl.diments.requestify.ui.home.HomeViewModel
import nl.diments.requestify.ui.setup.SpotifySetupViewModel
import nl.diments.requestify.ui.setup.TwitchSetupViewModel

/** The most the exit waits for Twitch to pause the reward. */
internal const val EXIT_PAUSE_TIMEOUT_MS = 3_000L

/**
 * Builds the app's objects once, for the app's whole life, on [scope] (an app-lifetime scope on the UI thread).
 * Building starts nothing: [start] begins polling Spotify and following Twitch, [stop] ends it.
 *
 * The Twitch sources (chat, Bits, Channel Points redemptions, the stream's live status) with their reply sender and
 * redemption outbox run only while the Twitch session is connected (logged in with an account). Settling Channel
 * Points redemptions runs for the app's whole life.
 */
class AppGraph(
    paths: AppPaths,
    secrets: SecretStore,
    httpEngine: HttpClientEngine = CIO.create(),
    private val scope: CoroutineScope,
    /** Null: Ktor's WebSocket. Tests drive EventSub with a fake one. */
    eventSubConnector: EventSubConnector? = null,
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
        shortLinks = KtorShortLinkResolver(http),
    )

    val home =HomeViewModel(controller, spotify, settings, intake, rejected, scope, clock = System::currentTimeMillis)

    val spotifySetup = SpotifySetupViewModel(settings, auth, spotify, controller, scope)

    private val twitchAuth = TwitchAuth(http, secrets, clock = System::currentTimeMillis)

    private val twitchApi = TwitchApi(
        http = http,
        token = { force -> twitchAuth.token(force) },
        broadcasterId = { settings.settings.value.twitch.account?.userId },
    )

    /** Built, not started: the Twitch session starts it while logged in. */
    internal val eventSub: EventSubClient = EventSubClient(
        connector = eventSubConnector ?: KtorEventSubConnector(http),
        api = twitchApi,
        broadcasterId = { settings.settings.value.twitch.account?.userId },
        rewardId = { settings.settings.value.twitch.channelPoints.rewardId },
        onAuthorizationRevoked = { twitchSession.onAuthorizationRevoked() },
        scope = scope,
        clock = System::currentTimeMillis,
    )

    /** Keeps EventSub and the login in step, and reports Twitch's health to the connection chip. */
    val twitchSession: TwitchSession = TwitchSession(twitchAuth, twitchApi, eventSub, settings, scope, clock = System::currentTimeMillis)

    // The Twitch sources. Built once; they run while the Twitch session is connected (see [startTwitchSources]).

    private val chatReplies = ChatReplies()

    /** Sends the replies of chat, Bits and Channel Points, one at a time. */
    private val chatSender = ChatReplySender(twitchApi, scope)

    private val chat = TwitchChat(
        eventSub = eventSub,
        intake = intake,
        controller = controller,
        api = twitchApi,
        settings = settings,
        replies = chatReplies,
        sender = chatSender,
        broadcasterId = { settings.settings.value.twitch.account?.userId },
        scope = scope,
        clock = System::currentTimeMillis,
    )

    private val bits = BitsSource(
        eventSub = eventSub,
        intake = intake,
        settings = settings,
        replies = chatReplies,
        sender = chatSender,
        scope = scope,
        clock = System::currentTimeMillis,
    )

    /** Tells Twitch what became of each redemption (fulfilled or refunded), also across restarts. */
    private val redemptionOutbox = RedemptionOutbox(
        file = JsonFileStore(paths.redemptionsFile, ListSerializer(OutboxEntry.serializer())) { emptyList() },
        api = twitchApi,
        scope = scope,
    )

    private val channelPoints = ChannelPointsSource(
        eventSub = eventSub,
        intake = intake,
        controller = controller,
        api = twitchApi,
        outbox = redemptionOutbox,
        settings = settings,
        replies = chatReplies,
        sender = chatSender,
        scope = scope,
        clock = System::currentTimeMillis,
    )

    /** Opens and closes requests with the stream. */
    private val streamWatcher = StreamWatcher(eventSub, twitchApi, gate, scope, clock = System::currentTimeMillis)

    private val twitchLock = Any()

    /** Between [start] and [stop]: the sources follow the Twitch session. Guarded by [twitchLock]. */
    private var followingTwitch = false

    /** Guarded by [twitchLock]. */
    private var twitchFollower: Job? = null

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

    val twitchSetup = TwitchSetupViewModel(
        auth = twitchAuth,
        api = twitchApi,
        rewards = rewardManager,
        settings = settings,
        scope = scope,
        openBrowser = { url -> browse(url) },
        copyToClipboard = ::copyToClipboard,
    )

    private val logStore = LogStore(paths.logDir, scope, clock = System::currentTimeMillis)

    /** Notices the times Bits could have been missed: EventSub down during the stream, or the app off. */
    private val bitsGaps = BitsGapWatcher(
        eventSubState = eventSub.state,
        gateEvents = gate.events,
        loadedLastSeenLiveMs = gate.loadedLastSeenLiveMs,
        appStartMs = System.currentTimeMillis(),
        scope = scope,
        clock = System::currentTimeMillis,
    )

    private val donationCursors =
        DonationCursorStore(JsonFileStore(paths.donationsFile, DonationCursors.serializer()) { DonationCursors() })
    private val donoLinkApi = DonoLinkApi(http, key = { secrets.get(SecretKeys.DONOLINK_KEY) })
    private val streamElementsApi = StreamElementsApi(http, jwt = { secrets.get(SecretKeys.STREAMELEMENTS_JWT) })

    /** Donations with a Spotify link become paid requests; replies only while Twitch is connected. */
    private val donationHandler = DonationHandler(
        intake = intake,
        settings = settings,
        replies = chatReplies,
        sender = chatSender,
        twitchConnected = twitchSession.connected,
        scope = scope,
        clock = System::currentTimeMillis,
    )

    private val streamElementsSource = StreamElementsSource(
        feed = AstroClient(
            connector = KtorAstroConnector(astroHttpClient(httpEngine)), // its own client: it pings, EventSub must not
            jwt = { secrets.get(SecretKeys.STREAMELEMENTS_JWT) },
            room = {
                when (val channel = streamElementsApi.channel()) {
                    is SeResult.Ok -> channel.value.id
                    else -> secrets.get(SecretKeys.STREAMELEMENTS_JWT)?.let(::channelIdFromJwt)
                }
            },
            scope = scope,
            clock = System::currentTimeMillis,
        ),
        handle = { donationHandler.handle(it) },
        cursors = donationCursors,
        scope = scope,
        clock = System::currentTimeMillis,
    )

    /** The one donation source the streamer chose, for the app's whole life (with or without Twitch). */
    val donationSources = DonationSources(
        settings = settings,
        donoLink = {
            DonoLinkSource(donoLinkApi, { donationHandler.handle(it) }, donationCursors, scope, clock = System::currentTimeMillis)
        },
        streamElements = { streamElementsSource },
        scope = scope,
    )

    val donationsSetup = DonationsSetupViewModel(
        settings = settings,
        secrets = secrets,
        cursors = donationCursors,
        donoLink = donoLinkApi,
        streamElements = streamElementsApi,
        health = donationSources.health,
        scope = scope,
        clock = System::currentTimeMillis,
        openBrowser = { url -> browse(url) },
        restartSource = { donationSources.restartCurrent() },
    )

    /** The Log: it listens to what the app already reports. */
    val logbook = Logbook(
        store = logStore,
        scope = scope,
        clock = System::currentTimeMillis,
        inputs = LogInputs(
            intake = intake.records,
            requests = controller.events,
            refunds = redemptionOutbox.results,
            gate = gate.events,
            connections = merge(
                controller.health.map { LogService.Spotify to it.toSnapshot() },
                twitchSession.health.map { LogService.Twitch to it.toSnapshot() },
                donationSources.health.mapNotNull { h -> h.service()?.let { it to h.toSnapshot(System.currentTimeMillis()) } },
            ),
            gaps = merge(bitsGaps.gaps, streamElementsSource.gaps),
        ),
    )

    val logScreen = LogViewModel(logbook, scope)

    /**
     * Starts settling Channel Points redemptions, resumes the offline grace (or ends a stream whose grace ran out while
     * the app was off), starts polling Spotify, resumes the paid requests that waited for it, and connects to Twitch
     * with its sources while logged in.
     */
    fun start() {
        // For the app's whole life, before anything can change the queue: the controller's request events do not
        // replay. That includes the gate, whose start can end a stream that ended while the app was closed, and clear
        // its free requests on the UI thread while this runs on another. A redemption is fulfilled or refunded also
        // without a Twitch login: the outbox keeps it until it can be sent. The Log listens first of all.
        logbook.start()
        bitsGaps.start()
        channelPoints.startLifecycle()
        gate.start()
        controller.start()
        intake.start()
        donationHandler.start()
        donationSources.start()
        // Before the session: when it is connected already, the sources listen before EventSub connects.
        followTwitchSession()
        twitchSession.start()
    }

    /**
     * Stops everything [start] started, in reverse. A queue save still in flight when the app exits right after can be
     * cut off; the atomic write then keeps the previous file, so at most the last change is lost.
     */
    fun stop() {
        twitchSession.stop()
        stopFollowingTwitchSession()
        donationSources.stop()
        donationHandler.stop()
        controller.stop()
        gate.stop() // remembers that the stream was live until now, for the next start
        channelPoints.stopLifecycle() // a change the controller still saves is settled too
        bitsGaps.stop()
        logbook.stop() // last: everything above is in the Log (its store writes what is left in stopGracefully)
    }

    /** Runs the Twitch sources while the Twitch session is connected, from now until [stop]. */
    private fun followTwitchSession() {
        synchronized(twitchLock) {
            if (followingTwitch) return
            followingTwitch = true
            // Undispatched: the current value is acted on before this returns.
            twitchFollower = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                twitchSession.connected.collect { connected -> onTwitchSession(connected) }
            }
        }
    }

    private fun onTwitchSession(connected: Boolean) {
        synchronized(twitchLock) {
            if (!followingTwitch) return // stopped meanwhile: nothing may start again
            if (connected) startTwitchSources() else stopTwitchSources()
        }
    }

    private fun stopFollowingTwitchSession() {
        synchronized(twitchLock) {
            followingTwitch = false
            twitchFollower?.cancel()
            twitchFollower = null
            stopTwitchSources()
        }
    }

    /**
     * The sender first (the sources reply through it), then the outbox, the sources, and the stream watcher last, so
     * the gate hears of the stream only with Twitch connected. Each start does nothing while it runs, so a repeated
     * value never starts a second instance. Caller holds [twitchLock].
     */
    private fun startTwitchSources() {
        chatSender.start()
        redemptionOutbox.start()
        chat.start()
        bits.start()
        channelPoints.startSession()
        rewardManager.start() // its pausing and its events need a login, so it follows the session
        streamWatcher.start()
    }

    /** The reverse of [startTwitchSources]; the Channel Points lifecycle keeps running. Caller holds [twitchLock]. */
    private fun stopTwitchSources() {
        streamWatcher.stop()
        rewardManager.stop()
        channelPoints.stopSession()
        bits.stop()
        chat.stop()
        redemptionOutbox.stop()
        chatSender.stop()
    }

    /**
     * The exit: pauses Requestify's own Channel Points reward (when it is switched on, so viewers cannot redeem it
     * while nobody is listening), then [stop]s everything. The pause gets [EXIT_PAUSE_TIMEOUT_MS] at most.
     */
    suspend fun stopGracefully() {
        withTimeoutOrNull(EXIT_PAUSE_TIMEOUT_MS) { withContext(Dispatchers.IO) { rewardManager.pauseForExit() } }
        stop()
        withTimeoutOrNull(EXIT_PAUSE_TIMEOUT_MS) { logStore.stop() } // writes what the Log still holds
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

/** Spotify as the Log sees it: a playback state (nothing playing, no device, not set up) is no connection news. */
private fun SpotifyHealth.toSnapshot(): ConnectionSnapshot = when (this) {
    SpotifyHealth.LoginNeeded -> ConnectionSnapshot.LoginExpired
    is SpotifyHealth.Unreachable -> ConnectionSnapshot.Down
    else -> ConnectionSnapshot.Up
}

private fun TwitchHealth.toSnapshot(): ConnectionSnapshot = when {
    loginNeeded -> ConnectionSnapshot.LoginExpired
    disconnected -> ConnectionSnapshot.Down
    else -> ConnectionSnapshot.Up
}

private fun DonationHealth.service(): LogService? = when (kind) {
    DonationSourceKind.DonoLink -> LogService.DonoLink
    DonationSourceKind.StreamElements -> LogService.StreamElements
    DonationSourceKind.None -> null
}

/** The donation source as the Log sees it: a key about to expire is news once, like a lost connection. */
private fun DonationHealth.toSnapshot(nowMs: Long): ConnectionSnapshot = when {
    status == DonationStatus.KeyRefused -> ConnectionSnapshot.KeyRefused
    status == DonationStatus.Retrying -> ConnectionSnapshot.Down
    keyExpiresAtEpochMs?.let { it - nowMs <= 7 * 24 * 3_600_000L } == true -> ConnectionSnapshot.KeyExpiring
    else -> ConnectionSnapshot.Up
}
