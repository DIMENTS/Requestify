package nl.diments.requestify.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.connection_all_connected
import nl.diments.requestify.resources.connection_donolink_disconnected
import nl.diments.requestify.resources.connection_donolink_key_expiring
import nl.diments.requestify.resources.connection_donolink_key_refused
import nl.diments.requestify.resources.connection_donolink_no_key
import nl.diments.requestify.resources.connection_streamelements_no_token
import nl.diments.requestify.resources.connection_reward_removed
import nl.diments.requestify.resources.connection_streamelements_disconnected
import nl.diments.requestify.resources.connection_streamelements_refused
import nl.diments.requestify.resources.connection_twitch_disconnected
import nl.diments.requestify.resources.connection_twitch_login_needed

/** What is wrong with the donation source. */
enum class DonationProblem { KeyRefused, NoKey, Disconnected, KeyExpiring }

/** Something that needs the streamer's attention on Connections. */
sealed interface ConnectionProblem {
    data class Spotify(val health: SpotifyHealth) : ConnectionProblem
    data object TwitchLoginNeeded : ConnectionProblem
    data object TwitchDisconnected : ConnectionProblem
    data object RewardRemoved : ConnectionProblem
    data class Donation(val kind: DonationSourceKind, val problem: DonationProblem) : ConnectionProblem
}

/** What Twitch's session reports; all false (nothing wrong) until the session is wired. */
data class TwitchHealth(
    val loginNeeded: Boolean = false,
    val disconnected: Boolean = false,
    val rewardRemoved: Boolean = false,
)

/** A DonoLink key is warned about from this long before it expires. */
private const val KEY_WARNING_MS = 7 * 24 * 3_600_000L

/**
 * Everything that is wrong right now, most important first: the logins and keys first (Spotify, Twitch, the donation
 * source), then a missing Spotify device, then connections that are down, then Twitch's removed reward, then a
 * DonoLink key that expires within a week ([nowMs]). [SpotifyHealth.Ok] and [SpotifyHealth.NothingPlaying] are not
 * problems.
 */
fun connectionProblems(
    spotify: SpotifyHealth,
    twitch: TwitchHealth,
    donations: DonationHealth = DonationHealth(),
    nowMs: Long = System.currentTimeMillis(),
): List<ConnectionProblem> = buildList {
    when (spotify) {
        SpotifyHealth.LoggedOut, SpotifyHealth.LoginNeeded, SpotifyHealth.PremiumRequired ->
            add(ConnectionProblem.Spotify(spotify))
        else -> Unit
    }
    if (twitch.loginNeeded) add(ConnectionProblem.TwitchLoginNeeded)
    val kind = donations.kind
    if (kind != DonationSourceKind.None && donations.status == DonationStatus.KeyRefused) {
        add(ConnectionProblem.Donation(kind, DonationProblem.KeyRefused))
    }
    if (kind != DonationSourceKind.None && donations.status == DonationStatus.NoKey) {
        add(ConnectionProblem.Donation(kind, DonationProblem.NoKey))
    }
    if (spotify == SpotifyHealth.NoActiveDevice) add(ConnectionProblem.Spotify(spotify))
    if (spotify is SpotifyHealth.Unreachable || spotify is SpotifyHealth.RateLimited) add(ConnectionProblem.Spotify(spotify))
    if (twitch.disconnected) add(ConnectionProblem.TwitchDisconnected)
    if (kind != DonationSourceKind.None && donations.status == DonationStatus.Retrying) {
        add(ConnectionProblem.Donation(kind, DonationProblem.Disconnected))
    }
    if (twitch.rewardRemoved) add(ConnectionProblem.RewardRemoved)
    val expires = donations.keyExpiresAtEpochMs
    if (kind == DonationSourceKind.DonoLink && donations.status != DonationStatus.Off && donations.status != DonationStatus.KeyRefused &&
        expires != null && expires - nowMs <= KEY_WARNING_MS
    ) {
        add(ConnectionProblem.Donation(kind, DonationProblem.KeyExpiring))
    }
}

private fun ConnectionProblem.look(): StatusLook = when (this) {
    is ConnectionProblem.Spotify -> health.look()
    ConnectionProblem.TwitchLoginNeeded -> StatusLook(Res.string.connection_twitch_login_needed, AppIcons.Warning, Tone.Problem)
    ConnectionProblem.TwitchDisconnected -> StatusLook(Res.string.connection_twitch_disconnected, AppIcons.CloudOff, Tone.Problem)
    ConnectionProblem.RewardRemoved -> StatusLook(Res.string.connection_reward_removed, AppIcons.Warning, Tone.Problem)
    is ConnectionProblem.Donation -> {
        val streamElements = kind == DonationSourceKind.StreamElements
        when (problem) {
            DonationProblem.KeyRefused -> StatusLook(
                if (streamElements) Res.string.connection_streamelements_refused else Res.string.connection_donolink_key_refused,
                AppIcons.Warning,
                Tone.Problem,
            )
            DonationProblem.NoKey -> StatusLook(
                if (streamElements) Res.string.connection_streamelements_no_token else Res.string.connection_donolink_no_key,
                AppIcons.Link,
                Tone.Problem,
            )
            DonationProblem.Disconnected -> StatusLook(
                if (streamElements) Res.string.connection_streamelements_disconnected else Res.string.connection_donolink_disconnected,
                AppIcons.CloudOff,
                Tone.Problem,
            )
            DonationProblem.KeyExpiring -> StatusLook(Res.string.connection_donolink_key_expiring, AppIcons.Warning, Tone.Problem)
        }
    }
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
