package nl.diments.requestify.ui.window

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import nl.diments.requestify.core.stream.GateStatus
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.requests_closed
import nl.diments.requestify.resources.requests_live_closed
import nl.diments.requestify.resources.requests_live_open
import nl.diments.requestify.resources.requests_offline_closed
import nl.diments.requestify.resources.requests_offline_open
import nl.diments.requestify.resources.requests_open
import nl.diments.requestify.resources.requests_tip_clear_waits
import nl.diments.requestify.resources.requests_tip_clears_at
import nl.diments.requestify.resources.requests_tip_follows_closed
import nl.diments.requestify.resources.requests_tip_follows_open
import nl.diments.requestify.resources.requests_tip_manual
import nl.diments.requestify.resources.requests_tip_plain_closed
import nl.diments.requestify.resources.requests_tip_plain_open
import nl.diments.requestify.ui.common.AppIcons
import org.jetbrains.compose.resources.stringResource

/** The corner of the open (checked) state: a rounded square next to the closed state's pill. */
private val OpenShape = RoundedCornerShape(10.dp)

/**
 * Opens and closes requests, in the title bar: an Expressive toggle button. Open is filled `primary` with a
 * rounded-square shape, closed is outlined with a pill shape, and the shape morphs between them with the motion
 * scheme's spatial spec. With Twitch the label says whether the stream is live as well as whether requests are
 * open; a tooltip explains why it stands as it does, and when the free requests will be cleared.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RequestsToggle(status: GateStatus, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(status.labelResource())
    val tip = tooltipText(status)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tip) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        ToggleButton(
            checked = status.open,
            onCheckedChange = { onToggle() },
            modifier = Modifier.height(ButtonDefaults.ExtraSmallContainerHeight),
            shapes = ToggleButtonDefaults.shapes(shape = CircleShape, pressedShape = OpenShape, checkedShape = OpenShape),
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = scheme.onSurfaceVariant,
                checkedContainerColor = scheme.primary,
                checkedContentColor = scheme.onPrimary,
            ),
            border = if (status.open) null else BorderStroke(1.dp, scheme.outline),
            contentPadding = ButtonDefaults.ExtraSmallContentPadding,
        ) {
            Icon(
                if (status.open) AppIcons.CheckCircle else AppIcons.DoNotDisturb,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun GateStatus.labelResource() = when {
    twitch && live && open -> Res.string.requests_live_open
    twitch && live -> Res.string.requests_live_closed
    twitch && open -> Res.string.requests_offline_open
    twitch -> Res.string.requests_offline_closed
    open -> Res.string.requests_open
    else -> Res.string.requests_closed
}

private val ClearTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Why the switch stands as it does, plus when the free requests will be cleared while that is pending. */
@Composable
internal fun tooltipText(status: GateStatus, zone: ZoneId = ZoneId.systemDefault()): String {
    val why = stringResource(
        when {
            status.twitch && status.manual -> Res.string.requests_tip_manual
            status.twitch && status.open -> Res.string.requests_tip_follows_open
            status.twitch -> Res.string.requests_tip_follows_closed
            status.open -> Res.string.requests_tip_plain_open
            else -> Res.string.requests_tip_plain_closed
        },
    )
    val clears = status.clearAtEpochMs
    val clearing = when {
        clears != null -> stringResource(
            Res.string.requests_tip_clears_at,
            remember(clears, zone) { ClearTime.format(Instant.ofEpochMilli(clears).atZone(zone)) },
        )
        status.clearPostponed -> stringResource(Res.string.requests_tip_clear_waits)
        else -> null
    }
    return if (clearing == null) why else "$why\n$clearing"
}
