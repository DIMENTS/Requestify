package nl.diments.requestify.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.health.DonationProblem
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.connection_all_connected
import nl.diments.requestify.resources.connection_donolink_disconnected
import nl.diments.requestify.resources.connection_donolink_key_expiring
import nl.diments.requestify.resources.connection_donolink_key_expiring_days
import nl.diments.requestify.resources.connection_donolink_key_expiring_one_day
import nl.diments.requestify.resources.connection_donolink_key_expiring_hours
import nl.diments.requestify.resources.connection_donolink_key_refused
import nl.diments.requestify.resources.connection_donolink_no_key
import nl.diments.requestify.resources.connection_streamelements_no_token
import nl.diments.requestify.resources.connection_reward_removed
import nl.diments.requestify.resources.connection_streamelements_disconnected
import nl.diments.requestify.resources.connection_streamelements_refused
import nl.diments.requestify.resources.connection_twitch_disconnected
import nl.diments.requestify.resources.connection_twitch_login_needed
import org.jetbrains.compose.resources.StringResource

/** How the chip shows this problem at [nowMs] (a key's days left count down); also the tray's status line. */
internal fun ConnectionProblem.statusLook(nowMs: Long = System.currentTimeMillis()): StatusLook = look(nowMs)

private fun ConnectionProblem.look(nowMs: Long = System.currentTimeMillis()): StatusLook = when (this) {
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
            DonationProblem.KeyExpiring -> {
                val days = daysLeft ?: keyExpiresAtEpochMs?.let { ((it - nowMs).coerceAtLeast(0) / DAY_MS).toInt() }
                when (days) {
                    null -> StatusLook(Res.string.connection_donolink_key_expiring, AppIcons.Warning, Tone.Problem)
                    0 -> StatusLook(Res.string.connection_donolink_key_expiring_hours, AppIcons.Warning, Tone.Problem)
                    1 -> StatusLook(Res.string.connection_donolink_key_expiring_one_day, AppIcons.Warning, Tone.Problem)
                    else -> StatusLook(Res.string.connection_donolink_key_expiring_days, AppIcons.Warning, Tone.Problem, listOf(days))
                }
            }
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

private const val DAY_MS = 24 * 3_600_000L
