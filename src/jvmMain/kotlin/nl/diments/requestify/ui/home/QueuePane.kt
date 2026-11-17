package nl.diments.requestify.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.home_drag_to_reorder
import nl.diments.requestify.resources.home_load_failing
import nl.diments.requestify.resources.home_paid_request
import nl.diments.requestify.resources.home_played_empty
import nl.diments.requestify.resources.home_played_tab
import nl.diments.requestify.resources.home_queue_empty
import nl.diments.requestify.resources.home_queue_tab
import nl.diments.requestify.resources.home_queue_title
import nl.diments.requestify.resources.home_queue_total
import nl.diments.requestify.resources.home_remove_request
import nl.diments.requestify.resources.home_save_failing
import nl.diments.requestify.resources.home_up_next_in_spotify
import nl.diments.requestify.resources.queue_then_in_spotify
import nl.diments.requestify.resources.queue_your_music
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.common.requesterLabel
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.theme.SourceChip
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Connected list rows: [Spacing.xs] apart, 20 dp corners on the outside of the group, 6 dp between rows. */
private val RowGap = Spacing.xs
private val OuterCorner = 20.dp
private val InnerCorner = 6.dp

/**
 * A queue row's drag handle icon, the room the handle takes ([Spacing.s] around the icon) and the room its remove
 * button takes (a touch target).
 */
private val DragHandleSize = 20.dp
private val DragHandleSlot = Spacing.s + DragHandleSize + Spacing.s
private val RemoveButtonSize = 48.dp

private enum class QueueTab { Queue, Played }

/**
 * Left side of Home: the queue (drag to reorder, X to remove) with the request Spotify already has on top and
 * what Spotify itself plays after the requests below, the played history and the field to add a track. Spotify's
 * status is in the title bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueuePane(
    state: HomeUiState,
    onAddInput: (String) -> Unit,
    onAddSubmit: (String) -> Boolean,
    onAddTrack: (Track) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    addFieldFocus: FocusRequester = remember { FocusRequester() },
) {
    var tab by rememberSaveable { mutableStateOf(QueueTab.Queue) }
    val motion = MaterialTheme.motionScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(Res.string.home_queue_title), style = MaterialTheme.typography.headlineMediumEmphasized)
            Row {
                QueueTabs(tab, queueSize = state.queue.size, onSelect = { tab = it }, Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                if (state.queueDurationMs > 0) {
                    Text(
                        stringResource(Res.string.home_queue_total, formatDuration(state.queueDurationMs)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = state.loadFailing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            ProblemBanner(stringResource(Res.string.home_load_failing))
        }
        AnimatedVisibility(
            visible = state.saveFailing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            ProblemBanner(stringResource(Res.string.home_save_failing))
        }
        AddTrackField(
            results = state.results,
            resultsStale = state.lookingUp,
            message = state.addMessage,
            onInput = onAddInput,
            onSubmit = onAddSubmit,
            onPick = onAddTrack,
            focusRequester = addFieldFocus,
        )
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            modifier = Modifier.weight(1f),
        ) { shown ->
            when (shown) {
                QueueTab.Queue -> QueueList(
                    queue = state.queue,
                    handedOver = state.handedOver,
                    upcoming = state.upcoming,
                    onRemove = onRemove,
                    onMove = onMove,
                    modifier = Modifier.fillMaxSize(),
                )
                QueueTab.Played -> PlayedList(state.history, Modifier.fillMaxSize())
            }
        }
    }
}

/** The Expressive successor of segmented buttons: a connected button group; the checked one turns into a pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueTabs(selected: QueueTab, queueSize: Int, onSelect: (QueueTab) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(
            checked = selected == QueueTab.Queue,
            onCheckedChange = { onSelect(QueueTab.Queue) },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.home_queue_tab, queueSize))
        }
        ToggleButton(
            checked = selected == QueueTab.Played,
            onCheckedChange = { onSelect(QueueTab.Played) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.home_played_tab))
        }
    }
}

/**
 * Everything that plays next, in playing order: the [handedOver] request, the queue and then [upcoming], the
 * tracks Spotify itself has lined up. The queue and Spotify's tracks form one connected list with a caption where
 * the requests end. Only the requests can be dragged, and only among themselves. While a row is dragged the list
 * shows a local order; the move is committed when the row is dropped, and the local order is dropped again once
 * the queue reflects it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueList(
    queue: List<Request>,
    handedOver: Request?,
    upcoming: List<Track>,
    onRemove: (String) -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentQueue by rememberUpdatedState(queue)
    var dragOrder by remember { mutableStateOf<List<Request>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(queue) { if (draggingId == null) dragOrder = null }
    val shown = dragOrder ?: queue

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val order = dragOrder ?: currentQueue
        val fromIndex = order.indexOfFirst { it.id == from.key }
        val toIndex = order.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            dragOrder = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
    val drop = { id: String ->
        draggingId = null
        val order = dragOrder
        val from = currentQueue.indexOfFirst { it.id == id }
        val to = if (order == null || from < 0) from else dropIndex(order, currentQueue, id)
        // A real move changes the queue, which then drops the local order; otherwise drop it now.
        if (to == from) dragOrder = null else onMove(id, to)
    }

    if (queue.isEmpty() && handedOver == null && upcoming.isEmpty()) {
        EmptyState(stringResource(Res.string.home_queue_empty), modifier)
        return
    }
    val motion = MaterialTheme.motionScheme
    val connectedRows = shown.size + upcoming.size
    val upcomingKeys = remember(upcoming) { upcomingKeys(upcoming) }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RowGap),
        contentPadding = PaddingValues(bottom = Spacing.l),
    ) {
        if (handedOver != null) {
            item(key = HANDED_OVER_KEY) {
                HandedOverRow(
                    handedOver,
                    Modifier
                        .animateItem(motion.defaultEffectsSpec(), motion.defaultSpatialSpec(), motion.fastEffectsSpec())
                        .padding(bottom = Spacing.s),
                )
            }
        }
        itemsIndexed(shown, key = { _, request -> request.id }) { index, request ->
            ReorderableItem(
                state = reorder,
                key = request.id,
                animateItemModifier = Modifier.animateItem(
                    motion.defaultEffectsSpec(),
                    motion.defaultSpatialSpec(),
                    motion.fastEffectsSpec(),
                ),
            ) { dragging ->
                QueueRow(
                    request = request,
                    shape = connectedShape(index, connectedRows, dragging),
                    dragging = dragging,
                    onRemove = onRemove,
                    dragHandle = Modifier.draggableHandle(
                        onDragStarted = { draggingId = request.id },
                        onDragStopped = { drop(request.id) },
                    ),
                )
            }
        }
        if (upcoming.isNotEmpty()) {
            // Plain items, not reorderable ones: no request can be dragged among them.
            item(key = THEN_IN_SPOTIFY_KEY) {
                ThenInSpotify(
                    Modifier.animateItem(
                        motion.defaultEffectsSpec(),
                        motion.defaultSpatialSpec(),
                        motion.fastEffectsSpec(),
                    ),
                )
            }
            itemsIndexed(upcoming, key = { index, _ -> upcomingKeys[index] }) { index, track ->
                SpotifyRow(
                    track = track,
                    shape = connectedShape(shown.size + index, connectedRows, dragging = false),
                    modifier = Modifier.animateItem(
                        motion.defaultEffectsSpec(),
                        motion.defaultSpatialSpec(),
                        motion.fastEffectsSpec(),
                    ),
                )
            }
        }
    }
}

private const val HANDED_OVER_KEY = "handed-over"
private const val THEN_IN_SPOTIFY_KEY = "then-in-spotify"

/**
 * Keys for Spotify's rows. They follow the track, so the rows slide along when Spotify moves on to the next song;
 * a repeated track gets the next number. The prefix keeps them apart from the requests' ids, also for a track that
 * is requested too.
 */
private fun upcomingKeys(tracks: List<Track>): List<String> {
    val seen = HashMap<String, Int>()
    return tracks.map { track ->
        val repeat = (seen[track.id] ?: 0) + 1
        seen[track.id] = repeat
        "spotify:$repeat:${track.id}"
    }
}

/** Where [id] goes in [queue] so it follows the same request as in [shown], even if the queue changed meanwhile. */
private fun dropIndex(shown: List<Request>, queue: List<Request>, id: String): Int {
    val rest = queue.filter { it.id != id }
    val at = shown.indexOfFirst { it.id == id }
    for (i in at - 1 downTo 0) {
        val index = rest.indexOfFirst { it.id == shown[i].id }
        if (index >= 0) return index + 1
    }
    return 0
}

/** Corner radii of row [index] of [count] in the connected list; a dragged row lifts out fully rounded. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedShape(index: Int, count: Int, dragging: Boolean): RoundedCornerShape {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val top by animateDpAsState(if (dragging || index == 0) OuterCorner else InnerCorner, spec)
    val bottom by animateDpAsState(if (dragging || index == count - 1) OuterCorner else InnerCorner, spec)
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueRow(
    request: Request,
    shape: RoundedCornerShape,
    dragging: Boolean,
    onRemove: (String) -> Unit,
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
            IconButton(onClick = { onRemove(request.id) }, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    AppIcons.Close,
                    contentDescription = stringResource(Res.string.home_remove_request, request.track.name),
                )
            }
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
private fun SpotifyRow(track: Track, shape: Shape, modifier: Modifier = Modifier) {
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
private fun QueueRowLayout(
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    handle: (@Composable () -> Unit)? = null,
    remove: (@Composable () -> Unit)? = null,
    details: @Composable RowScope.() -> Unit,
) {
    Surface(modifier, shape = shape, color = color, shadowElevation = shadowElevation) {
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

/** Where the requests end and Spotify's own next tracks begin: a caption on a hairline, inside the list. */
@Composable
private fun ThenInSpotify(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.queue_then_in_spotify),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The request Spotify already has: distinct, and neither draggable nor removable. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HandedOverRow(request: Request, modifier: Modifier = Modifier) {
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

@Composable
private fun PlayedList(history: List<PlayedEntry>, modifier: Modifier = Modifier) {
    if (history.isEmpty()) {
        EmptyState(stringResource(Res.string.home_played_empty), modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RowGap),
        contentPadding = PaddingValues(bottom = Spacing.l),
    ) {
        itemsIndexed(history, key = { _, entry -> "${entry.request.id}@${entry.playedAtEpochMs}" }) { index, entry ->
            Surface(
                shape = connectedShape(index, history.size, dragging = false),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                RequestDetails(entry.request, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
            }
        }
    }
}

/** A request's track details, with the source chip over the requester and a bolt when it is paid. */
@Composable
private fun RequestDetails(
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
private fun TrackDetails(
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

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(
                AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(40.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
