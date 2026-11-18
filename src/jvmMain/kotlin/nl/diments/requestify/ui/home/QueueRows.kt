package nl.diments.requestify.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.home_drag_to_reorder
import nl.diments.requestify.resources.home_paid_request
import nl.diments.requestify.resources.block_artist
import nl.diments.requestify.resources.block_song
import nl.diments.requestify.resources.block_viewer
import nl.diments.requestify.resources.home_row_actions
import nl.diments.requestify.resources.home_row_remove
import nl.diments.requestify.resources.home_up_next_in_spotify
import nl.diments.requestify.resources.queue_your_music
import nl.diments.requestify.ui.common.ActionMenu
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.MenuAction
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.common.requesterLabel
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.theme.SourceChip
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The test tag of each row of the queue list, requests and Spotify's own tracks alike. */
internal const val QUEUE_ROW_TAG = "queue-row"

/** Connected list rows: [Spacing.xs] apart, 20 dp corners on the outside of the group, 6 dp between rows. */
internal val RowGap = Spacing.xs
internal val OuterCorner = 20.dp
internal val InnerCorner = 6.dp

/**
 * A queue row's drag handle icon, the room the handle takes ([Spacing.s] around the icon) and the room its remove
 * button takes (a touch target).
 */
internal val DragHandleSize = 20.dp
internal val DragHandleSlot = Spacing.s + DragHandleSize + Spacing.s
internal val RemoveButtonSize = 48.dp

/** Corner radii of row [index] of [count] in the connected list; a dragged row lifts out fully rounded. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun connectedShape(index: Int, count: Int, dragging: Boolean): RoundedCornerShape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(if (dragging || index == 0) OuterCorner else InnerCorner, spec)
    val bottom by animateDpAsState(if (dragging || index == count - 1) OuterCorner else InnerCorner, spec)
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun QueueRow(
    request: Request,
    shape: RoundedCornerShape,
    dragging: Boolean,
    onRemove: (String) -> Unit,
    onBlock: (BlockTarget) -> Unit,
    dragHandle: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val color by animateColorAsState(
        if (dragging) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
        motion.fastEffectsSpec(),
    )
    val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, motion.fastSpatialSpec())
    QueueRowLayout(
        shape = shape,
        color = color,
        shadowElevation = elevation,
        handle = {
            Icon(
                AppIcons.DragIndicator,
                contentDescription = stringResource(Res.string.home_drag_to_reorder),
                tint = scheme.outline,
                modifier = dragHandle.padding(Spacing.s).size(DragHandleSize),
            )
        },
        remove = {
            RowMenu(request, onRemove, onBlock)
        },
    ) {
        RequestDetails(request, Modifier.weight(1f))
    }
}

/**
 * A track Spotify itself plays after the requests: a request row with "Your music" as its source, and without the
 * drag handle and the remove button (Spotify does not let its own queue be edited).
 */
@Composable
internal fun SpotifyRow(track: Track, shape: Shape, modifier: Modifier = Modifier) {
    QueueRowLayout(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        TrackDetails(track, Modifier.weight(1f)) {
            // The neutral colours of the streamer's own requests: this is their music too.
            SourceChip(RequestSource.MANUAL, stringResource(Res.string.queue_your_music))
        }
    }
}

/**
 * The columns every queue row shares, so titles, chips and durations line up down the list: the drag handle, the
 * details and the remove button. A row without a [handle] or a [remove] button keeps their room (and so the
 * remove button's height too).
 */
@Composable
internal fun QueueRowLayout(
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    handle: (@Composable () -> Unit)? = null,
    remove: (@Composable () -> Unit)? = null,
    details: @Composable RowScope.() -> Unit,
) {
    Surface(modifier.testTag(QUEUE_ROW_TAG), shape = shape, color = color, shadowElevation = shadowElevation) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (handle != null) handle() else Spacer(Modifier.size(DragHandleSlot))
            details()
            if (remove != null) remove() else Spacer(Modifier.size(RemoveButtonSize))
        }
    }
}

/** The request Spotify already has: distinct, and neither draggable nor removable. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HandedOverRow(request: Request, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(OuterCorner),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(Res.string.home_up_next_in_spotify),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            // Lined up with the queue rows' details: past the drag handle, short of the remove button.
            RequestDetails(
                request,
                Modifier.padding(start = Spacing.xs + DragHandleSlot, end = Spacing.xs + RemoveButtonSize),
                secondaryColor = LocalContentColor.current,
            )
        }
    }
}

/** A request's track details, with the source chip over the requester and a bolt when it is paid. */
@Composable
internal fun RequestDetails(
    request: Request,
    modifier: Modifier = Modifier,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TrackDetails(request.track, modifier, secondaryColor, paid = request.isPaid) {
        SourceChip(request.source, sourceLabel(request))
        Text(requesterLabel(request), style = MaterialTheme.typography.labelMedium, color = secondaryColor, maxLines = 1)
    }
}

/**
 * Art, title and artist, then [source] (aligned to the end: a chip, maybe over the requester), a bolt when [paid]
 * and the duration.
 */
@Composable
internal fun TrackDetails(
    track: Track,
    modifier: Modifier = Modifier,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    paid: Boolean = false,
    source: @Composable ColumnScope.() -> Unit,
) {
    val typography = MaterialTheme.typography
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(track.albumArtUrl, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists.joinToString(", "),
                style = typography.bodyMedium,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = source,
        )
        Box(Modifier.size(18.dp)) {
            if (paid) {
                Icon(
                    AppIcons.Bolt,
                    contentDescription = stringResource(Res.string.home_paid_request),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Text(
            formatDuration(track.durationMs),
            style = typography.labelLarge,
            color = secondaryColor,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 36.dp),
            maxLines = 1,
        )
    }
}

/**
 * A queue row's last column: the menu button with Remove, Block song, Block artist and (for a request from a Twitch
 * viewer) Block viewer. Blocking sweeps the queue and takes this row's request out too (refunded when free).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowMenu(request: Request, onRemove: (String) -> Unit, onBlock: (BlockTarget) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val actions = buildList {
        add(MenuAction(stringResource(Res.string.home_row_remove), AppIcons.Close) { onRemove(request.id) })
        add(MenuAction(stringResource(Res.string.block_song), AppIcons.Block) { onBlock(BlockTarget.Song(request.track)) })
        add(MenuAction(stringResource(Res.string.block_artist), AppIcons.MusicOff) { onBlock(BlockTarget.Artist(request.track)) })
        if (request.requester.twitchUserId != null) {
            add(MenuAction(stringResource(Res.string.block_viewer), AppIcons.PersonOff) { onBlock(BlockTarget.Viewer(request.requester)) })
        }
    }
    Box {
        IconButton(onClick = { expanded = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(
                AppIcons.MoreVert,
                contentDescription = stringResource(Res.string.home_row_actions, request.track.name),
            )
        }
        ActionMenu(expanded, onDismiss = { expanded = false }, actions)
    }
}
