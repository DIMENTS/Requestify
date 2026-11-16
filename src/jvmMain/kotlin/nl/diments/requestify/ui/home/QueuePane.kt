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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.home_drag_to_reorder
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
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.SpotifyStatusChip
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.common.requesterLabel
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.theme.SourceChip
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Connected list rows: 4 dp apart, 20 dp corners on the outside of the group, 6 dp between rows. */
private val RowGap = 4.dp
private val OuterCorner = 20.dp
private val InnerCorner = 6.dp

private enum class QueueTab { Queue, Played }

/**
 * Left side of Home: the queue (drag to reorder, X to remove) with the request Spotify already has on top,
 * the played history, Spotify's status and the field to add a track.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueuePane(
    state: HomeUiState,
    onAddInput: (String) -> Unit,
    onAddTrack: (Track) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    addFieldFocus: FocusRequester = remember { FocusRequester() },
) {
    var tab by rememberSaveable { mutableStateOf(QueueTab.Queue) }
    val motion = MaterialTheme.motionScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.home_queue_title),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                fontWeight = EmphasizedWeight,
            )
            Spacer(Modifier.weight(1f))
            SpotifyStatusChip(state.health)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            QueueTabs(tab, queueSize = state.queue.size, onSelect = { tab = it })
            Spacer(Modifier.weight(1f))
            if (state.queueDurationMs > 0) {
                Text(
                    stringResource(Res.string.home_queue_total, formatDuration(state.queueDurationMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = state.saveFailing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            SaveFailingBanner()
        }
        AddTrackField(
            results = state.results,
            message = state.addMessage,
            onInput = onAddInput,
            onPick = onAddTrack,
            focusRequester = addFieldFocus,
        )
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            modifier = Modifier.weight(1f),
        ) { shown ->
            when (shown) {
                QueueTab.Queue -> QueueList(state.queue, state.handedOver, onRemove, onMove, Modifier.fillMaxSize())
                QueueTab.Played -> PlayedList(state.history, Modifier.fillMaxSize())
            }
        }
    }
}

/** The Expressive successor of segmented buttons: a connected button group; the checked one turns into a pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueTabs(selected: QueueTab, queueSize: Int, onSelect: (QueueTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
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

@Composable
private fun SaveFailingBanner() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.home_save_failing), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The queue as a connected list. While a row is dragged the list shows a local order; the move is committed
 * when the row is dropped, and the local order is dropped again once the queue reflects it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueList(
    queue: List<Request>,
    handedOver: Request?,
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

    if (queue.isEmpty() && handedOver == null) {
        EmptyState(stringResource(Res.string.home_queue_empty), modifier)
        return
    }
    val motion = MaterialTheme.motionScheme
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RowGap),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (handedOver != null) {
            item(key = HANDED_OVER_KEY) {
                HandedOverRow(
                    handedOver,
                    Modifier
                        .animateItem(motion.defaultEffectsSpec(), motion.defaultSpatialSpec(), motion.fastEffectsSpec())
                        .padding(bottom = 8.dp),
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
                    shape = connectedShape(index, shown.size, dragging),
                    dragging = dragging,
                    onRemove = onRemove,
                    dragHandle = Modifier.draggableHandle(
                        onDragStarted = { draggingId = request.id },
                        onDragStopped = { drop(request.id) },
                    ),
                )
            }
        }
    }
}

private const val HANDED_OVER_KEY = "handed-over"

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
    Surface(shape = shape, color = color, shadowElevation = elevation) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.DragIndicator,
                contentDescription = stringResource(Res.string.home_drag_to_reorder),
                tint = scheme.outline,
                modifier = dragHandle.padding(8.dp).size(20.dp),
            )
            RequestDetails(request, Modifier.weight(1f))
            IconButton(onClick = { onRemove(request.id) }, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    AppIcons.Close,
                    contentDescription = stringResource(Res.string.home_remove_request, request.track.name),
                )
            }
        }
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
        Column(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(Res.string.home_up_next_in_spotify),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            RequestDetails(
                request,
                Modifier.padding(start = 40.dp, end = 52.dp, top = 6.dp),
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
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        itemsIndexed(history, key = { _, entry -> "${entry.request.id}@${entry.playedAtEpochMs}" }) { index, entry ->
            Surface(
                shape = connectedShape(index, history.size, dragging = false),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                RequestDetails(entry.request, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
            }
        }
    }
}

/** Art, title and artist, then the source chip over the requester, a bolt for paid requests and the duration. */
@Composable
private fun RequestDetails(
    request: Request,
    modifier: Modifier = Modifier,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val typography = MaterialTheme.typography
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(request.track.albumArtUrl, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(request.track.name, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                request.track.artists.joinToString(", "),
                style = typography.bodyMedium,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SourceChip(request.source, sourceLabel(request))
            Text(requesterLabel(request), style = typography.labelMedium, color = secondaryColor, maxLines = 1)
        }
        Box(Modifier.size(18.dp)) {
            if (request.isPaid) {
                Icon(
                    AppIcons.Bolt,
                    contentDescription = stringResource(Res.string.home_paid_request),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Text(
            formatDuration(request.track.durationMs),
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
