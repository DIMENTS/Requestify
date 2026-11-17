package nl.diments.requestify.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.connection_all_connected
import nl.diments.requestify.resources.connection_reward_removed
import nl.diments.requestify.resources.connection_twitch_disconnected
import nl.diments.requestify.resources.connection_twitch_login_needed

/** Something that needs the streamer's attention on Connections. */
sealed interface ConnectionProblem {
    data class Spotify(val health: SpotifyHealth) : ConnectionProblem
    data object TwitchLoginNeeded : ConnectionProblem
    data object TwitchDisconnected : ConnectionProblem
    data object RewardRemoved : ConnectionProblem
}

/** What Twitch's session reports; all false (nothing wrong) until the session is wired. */
data class TwitchHealth(
    val loginNeeded: Boolean = false,
    val disconnected: Boolean = false,
    val rewardRemoved: Boolean = false,
)

/**
 * Everything that is wrong right now, most important first: the logins first (Spotify, then Twitch), then a
 * missing Spotify device, then connections that are down, then Twitch's removed reward. [SpotifyHealth.Ok] and
 * [SpotifyHealth.NothingPlaying] are not problems.
 */
fun connectionProblems(spotify: SpotifyHealth, twitch: TwitchHealth): List<ConnectionProblem> = buildList {
    when (spotify) {
        SpotifyHealth.LoggedOut, SpotifyHealth.LoginNeeded, SpotifyHealth.PremiumRequired ->
            add(ConnectionProblem.Spotify(spotify))
        else -> Unit
    }
    if (twitch.loginNeeded) add(ConnectionProblem.TwitchLoginNeeded)
    if (spotify == SpotifyHealth.NoActiveDevice) add(ConnectionProblem.Spotify(spotify))
    if (spotify is SpotifyHealth.Unreachable || spotify is SpotifyHealth.RateLimited) add(ConnectionProblem.Spotify(spotify))
    if (twitch.disconnected) add(ConnectionProblem.TwitchDisconnected)
    if (twitch.rewardRemoved) add(ConnectionProblem.RewardRemoved)
}

private fun ConnectionProblem.look(): StatusLook = when (this) {
    is ConnectionProblem.Spotify -> health.look()
    ConnectionProblem.TwitchLoginNeeded -> StatusLook(Res.string.connection_twitch_login_needed, AppIcons.Warning, Tone.Problem)
    ConnectionProblem.TwitchDisconnected -> StatusLook(Res.string.connection_twitch_disconnected, AppIcons.CloudOff, Tone.Problem)
    ConnectionProblem.RewardRemoved -> StatusLook(Res.string.connection_reward_removed, AppIcons.Warning, Tone.Problem)
}

/**
 * The one connection status in the title bar: "All connected" while nothing is wrong, otherwise the most
 * important problem. A click goes to Connections ([onClick]).
 */
@Composable
fun ConnectionChip(problems: List<ConnectionProblem>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = problems.firstOrNull()?.look() ?: StatusLook(Res.string.connection_all_connected, AppIcons.CheckCircle, Tone.Good)
    StatusChip(look, modifier, onClick)
}
