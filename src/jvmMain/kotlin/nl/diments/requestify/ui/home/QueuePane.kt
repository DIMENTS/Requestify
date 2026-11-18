package nl.diments.requestify.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.home_block_failing
import nl.diments.requestify.resources.home_load_failing
import nl.diments.requestify.resources.home_played_empty
import nl.diments.requestify.resources.home_played_tab
import nl.diments.requestify.resources.home_queue_empty
import nl.diments.requestify.resources.home_queue_tab
import nl.diments.requestify.resources.home_queue_title
import nl.diments.requestify.resources.home_rejected_paid_badge
import nl.diments.requestify.resources.home_rejected_tab
import nl.diments.requestify.resources.home_queue_total
import nl.diments.requestify.resources.queue_no_requests_yet
import nl.diments.requestify.resources.queue_then_in_spotify
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class QueueTab { Queue, Played, Rejected }

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
    onBlock: (Request, BlockTarget) -> Unit,
    onAddAnyway: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    addFieldFocus: FocusRequester = remember { FocusRequester() },
    /** The narrow layout: problem banners take one line, so the queue keeps its room. */
    compact: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(QueueTab.Queue) }
    val motion = MaterialTheme.motionScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(Res.string.home_queue_title), style = MaterialTheme.typography.headlineMediumEmphasized)
            Row {
                QueueTabs(tab, queueSize = state.queue.size, rejectedBadge = state.rejectedBadge, onSelect = { tab = it }, Modifier.alignByBaseline())
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
            ProblemBanner(stringResource(Res.string.home_load_failing), compact = compact)
        }
        AnimatedVisibility(
            visible = state.blockFailing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            ProblemBanner(stringResource(Res.string.home_block_failing), compact = compact)
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
                    onBlock = onBlock,
                    onMove = onMove,
                    modifier = Modifier.fillMaxSize(),
                )
                QueueTab.Played -> PlayedList(state.history, Modifier.fillMaxSize())
                QueueTab.Rejected -> RejectedList(state.rejected, onAddAnyway, onDismiss, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The Expressive successor of segmented buttons: a connected button group; the checked one turns into a pill. The
 * Rejected tab carries a badge with the number of paid requests that were rejected ([rejectedBadge]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueTabs(
    selected: QueueTab,
    queueSize: Int,
    rejectedBadge: Int,
    onSelect: (QueueTab) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.home_played_tab))
        }
        ToggleButton(
            checked = selected == QueueTab.Rejected,
            onCheckedChange = { onSelect(QueueTab.Rejected) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.home_rejected_tab))
            if (rejectedBadge > 0) {
                val paid = stringResource(Res.string.home_rejected_paid_badge, rejectedBadge)
                Spacer(Modifier.width(Spacing.s))
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.semantics { contentDescription = paid },
                ) {
                    Text(rejectedBadge.toString())
                }
            }
        }
    }
}

/**
 * Everything that plays next, in playing order: the [handedOver] request, the queue and then [upcoming], the
 * tracks Spotify itself has lined up. The queue and Spotify's tracks form one connected list with a caption where
 * the requests end; without any request, a quiet line in their place says where they will show up. Only the
 * requests can be dragged, and only among themselves. While a row is dragged the list shows a local order; the move
 * is committed when the row is dropped, and the local order is dropped again once the queue reflects it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueList(
    queue: List<Request>,
    handedOver: Request?,
    upcoming: List<Track>,
    onRemove: (String) -> Unit,
    onBlock: (Request, BlockTarget) -> Unit,
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
        } else if (shown.isEmpty()) {
            // Only Spotify's own tracks follow: say where requests will show up.
            item(key = NO_REQUESTS_KEY) {
                NoRequestsYet(
                    Modifier.animateItem(
                        motion.defaultEffectsSpec(),
                        motion.defaultSpatialSpec(),
                        motion.fastEffectsSpec(),
                    ),
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
                    onBlock = { target -> onBlock(request, target) },
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
private const val NO_REQUESTS_KEY = "no-requests"
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

/** In place of the requests while there are none, above Spotify's own next tracks: one quiet line. */
@Composable
private fun NoRequestsYet(modifier: Modifier = Modifier) {
    Text(
        stringResource(Res.string.queue_no_requests_yet),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
    )
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

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
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
