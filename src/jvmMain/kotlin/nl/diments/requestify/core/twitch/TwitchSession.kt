package nl.diments.requestify.core.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.diments.requestify.core.auth.TokenResult
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.auth.TwitchAuth
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubState
import nl.diments.requestify.ui.common.TwitchHealth

/** A dropped EventSub connection is only worth showing once it has lasted this long: reconnects are usually instant. */
private const val DISCONNECT_GRACE_MS = 30_000L

/**
 * Ties Twitch's login to its connection, and reports what is wrong for the title bar.
 *
 * - EventSub runs while Twitch is logged in with an account, and stops when the user logs out or the login is dead.
 * - The token is validated at [start] and every [validateEveryMs] while logged in (Twitch requires this); a refused
 *   login ends in [TwitchAuthStatus.LoginNeeded] inside [TwitchAuth]. A good validation also refreshes the saved
 *   account's display name and broadcaster type, which change at Twitch (e.g. when a streamer becomes Affiliate).
 * - EventSub's `authorization_revoked` goes through [onAuthorizationRevoked].
 *
 * Runs on [scope]; [clock] is the same clock EventSub stamps its disconnects with.
 */
class TwitchSession(
    private val auth: TwitchAuth,
    private val api: TwitchApi,
    private val eventSub: EventSubClient,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val validateEveryMs: Long = 3_600_000,
) {
    private fun hasAccount() = settings.settings.value.twitch.account != null

    /** Logged in with an account: the state EventSub follows. */
    val connected: StateFlow<Boolean> =
        combine(auth.status, settings.settings.map { it.twitch.account != null }) { status, hasAccount ->
            status == TwitchAuthStatus.LoggedIn && hasAccount
        }.stateIn(scope, SharingStarted.Eagerly, auth.status.value == TwitchAuthStatus.LoggedIn && hasAccount())

    private val disconnectedForLong = MutableStateFlow(false)

    /** What the connection chip shows; see [TwitchHealth]. */
    val health: StateFlow<TwitchHealth> = combine(auth.status, disconnectedForLong, settings.settings) { status, disconnected, s ->
        TwitchHealth(
            loginNeeded = status is TwitchAuthStatus.LoginNeeded,
            disconnected = disconnected,
            rewardRemoved = s.twitch.channelPoints.rewardRemoved,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        TwitchHealth(
            loginNeeded = auth.status.value is TwitchAuthStatus.LoginNeeded,
            rewardRemoved = settings.settings.value.twitch.channelPoints.rewardRemoved,
        ),
    )

    private var jobs: List<Job> = emptyList()

    /** Begins following the login. Does nothing while already started. */
    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch {
                connected.collect { loggedIn -> if (loggedIn) eventSub.start() else eventSub.stop() }
            },
            scope.launch {
                // EventSub that Twitch refused the login for is the same as a dead login: show it as one.
                eventSub.state.collect { state ->
                    if (state is EventSubState.LoginNeeded && auth.status.value == TwitchAuthStatus.LoggedIn) {
                        auth.markLoginNeeded("EventSub could not subscribe with the login")
                    }
                }
            },
            scope.launch {
                auth.status.map { it == TwitchAuthStatus.LoggedIn }.distinctUntilChanged().collectLatest { loggedIn ->
                    if (!loggedIn) return@collectLatest
                    while (true) {
                        validate()
                        delay(validateEveryMs)
                    }
                }
            },
            scope.launch {
                eventSub.state.collectLatest { state ->
                    if (state is EventSubState.Disconnected) {
                        val wait = state.sinceEpochMs + DISCONNECT_GRACE_MS - clock()
                        if (wait > 0) delay(wait)
                        disconnectedForLong.value = true
                    } else {
                        disconnectedForLong.value = false
                    }
                }
            },
        )
    }

    /** Stops following the login and disconnects. */
    @Synchronized
    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        disconnectedForLong.value = false
        eventSub.stop()
    }

    /** Twitch withdrew the app's access (EventSub `authorization_revoked`): only a new login helps. */
    fun onAuthorizationRevoked() = auth.markLoginNeeded("Twitch revoked the authorization")

    private suspend fun validate() {
        try {
            if (auth.validate() is TokenResult.Ok) refreshAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next round tries again.
        }
    }

    /** Takes the broadcaster type and display name from Twitch; only of the account that is saved. */
    private suspend fun refreshAccount() {
        val channel = (api.me() as? TwitchResult.Ok)?.value ?: return
        fun TwitchAccount.fresh() = copy(displayName = channel.displayName, broadcasterType = channel.broadcasterType)
        val saved = settings.settings.value.twitch.account
        if (saved == null || saved.userId != channel.id || saved == saved.fresh()) return
        settings.tryUpdate { s ->
            val current = s.twitch.account
            if (current?.userId == channel.id) s.copy(twitch = s.twitch.copy(account = current.fresh())) else s
        }
    }
}
