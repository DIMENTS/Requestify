package nl.diments.requestify.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.spotify_status_limit_reached
import nl.diments.requestify.resources.spotify_status_login_needed
import nl.diments.requestify.resources.spotify_status_no_device
import nl.diments.requestify.resources.spotify_status_not_connected
import nl.diments.requestify.resources.spotify_status_nothing_playing
import nl.diments.requestify.resources.spotify_status_ok
import nl.diments.requestify.resources.spotify_status_premium_required
import nl.diments.requestify.resources.spotify_status_rate_limited
import nl.diments.requestify.resources.spotify_status_unreachable
import nl.diments.requestify.ui.theme.fadeThrough
import nl.diments.requestify.ui.theme.sizeTransform
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class Tone { Good, Neutral, Problem }

/** [args] fill [text]'s placeholders (a DonoLink key's days left). */
internal class StatusLook(val text: StringResource, val icon: ImageVector, val tone: Tone, val args: List<Any> = emptyList())

/** How the chip shows Spotify at [nowMs]: a wait of a minute or more says when it is over. */
internal fun SpotifyHealth.look(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): StatusLook = when (this) {
    SpotifyHealth.Ok -> StatusLook(Res.string.spotify_status_ok, AppIcons.CheckCircle, Tone.Good)
    SpotifyHealth.NothingPlaying -> StatusLook(Res.string.spotify_status_nothing_playing, AppIcons.MusicNote, Tone.Neutral)
    SpotifyHealth.NoActiveDevice -> StatusLook(Res.string.spotify_status_no_device, AppIcons.Warning, Tone.Problem)
    SpotifyHealth.LoginNeeded -> StatusLook(Res.string.spotify_status_login_needed, AppIcons.Warning, Tone.Problem)
    SpotifyHealth.LoggedOut -> StatusLook(Res.string.spotify_status_not_connected, AppIcons.Link, Tone.Neutral)
    SpotifyHealth.PremiumRequired -> StatusLook(Res.string.spotify_status_premium_required, AppIcons.Warning, Tone.Problem)
    is SpotifyHealth.RateLimited -> if (quota || untilEpochMs - nowMs >= 60_000) {
        StatusLook(Res.string.spotify_status_limit_reached, AppIcons.Sync, Tone.Neutral, listOf(clockTime(untilEpochMs, nowMs, zone)))
    } else {
        StatusLook(Res.string.spotify_status_rate_limited, AppIcons.Sync, Tone.Neutral)
    }
    is SpotifyHealth.Unreachable -> StatusLook(Res.string.spotify_status_unreachable, AppIcons.CloudOff, Tone.Problem)
}

/** [atMs] to the minute, rounded up so it is never too early; with the weekday when that is another day than [nowMs]. */
internal fun clockTime(atMs: Long, nowMs: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(atMs + 59_999).atZone(zone)
    val sameDay = at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return at.format(DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "EEE HH:mm", Locale.getDefault()))
}

/** Spotify's state at a glance; problems get the error container so they stand out. Changes cross-fade. */
@Composable
fun SpotifyStatusChip(health: SpotifyHealth, modifier: Modifier = Modifier) = StatusChip(health.look(), modifier)

/** A status pill; [onClick] makes it a button. Problems get the error container, and a new look cross-fades. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusChip(look: StatusLook, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        if (look.tone == Tone.Problem) scheme.errorContainer else Color.Transparent,
        motion.defaultEffectsSpec(),
    )
    val content by animateColorAsState(
        if (look.tone == Tone.Problem) scheme.onErrorContainer else scheme.onSurfaceVariant,
        motion.defaultEffectsSpec(),
    )
    val iconTint by animateColorAsState(
        when (look.tone) {
            Tone.Good -> scheme.primary
            Tone.Neutral -> scheme.onSurfaceVariant
            Tone.Problem -> scheme.onErrorContainer
        },
        motion.defaultEffectsSpec(),
    )
    val shape = MaterialTheme.shapes.small
    // The outline fades with the fill, instead of vanishing the moment a problem comes in.
    val outline by animateColorAsState(
        if (look.tone == Tone.Problem) Color.Transparent else scheme.outlineVariant,
        motion.defaultEffectsSpec(),
    )
    val border = BorderStroke(1.dp, outline)
    val body: @Composable () -> Unit = {
        AnimatedContent(
            targetState = look,
            transitionSpec = { motion.fadeThrough() using motion.sizeTransform(clip = true) },
            contentKey = { it.text.key },
        ) { shown ->
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(shown.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(shown.text, *shown.args.toTypedArray()),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (onClick == null) {
        Surface(modifier = modifier, shape = shape, color = container, contentColor = content, border = border, content = body)
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = container, contentColor = content, border = border, content = body)
    }
}
