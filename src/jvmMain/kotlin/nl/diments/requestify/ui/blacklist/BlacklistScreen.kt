package nl.diments.requestify.ui.blacklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.add_clear
import nl.diments.requestify.resources.add_spotify_unavailable
import nl.diments.requestify.resources.blacklist_by_mod
import nl.diments.requestify.resources.blacklist_by_you
import nl.diments.requestify.resources.blacklist_empty_artists
import nl.diments.requestify.resources.blacklist_empty_songs
import nl.diments.requestify.resources.blacklist_empty_viewers
import nl.diments.requestify.resources.blacklist_no_results
import nl.diments.requestify.resources.blacklist_placeholder_artists
import nl.diments.requestify.resources.blacklist_placeholder_songs
import nl.diments.requestify.resources.blacklist_placeholder_viewers
import nl.diments.requestify.resources.blacklist_subtitle
import nl.diments.requestify.resources.blacklist_tab_artists
import nl.diments.requestify.resources.blacklist_tab_songs
import nl.diments.requestify.resources.blacklist_tab_viewers
import nl.diments.requestify.resources.blacklist_twitch_unavailable
import nl.diments.requestify.resources.blacklist_unblock
import nl.diments.requestify.resources.blacklist_viewer_not_found
import nl.diments.requestify.resources.blacklist_viewers_need_twitch
import nl.diments.requestify.resources.blacklist_wrong_link_artists
import nl.diments.requestify.resources.blacklist_wrong_link_generic
import nl.diments.requestify.resources.blacklist_wrong_link_songs
import nl.diments.requestify.resources.nav_blacklist
import nl.diments.requestify.resources.save_failed
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.PageScaffold
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.common.formatShortDate
import nl.diments.requestify.ui.home.EmptyState
import nl.diments.requestify.ui.home.OuterCorner
import nl.diments.requestify.ui.home.RowGap
import nl.diments.requestify.ui.home.connectedShape
import nl.diments.requestify.ui.home.kindName
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The test tag of each row of the blacklist. */
internal const val BLACKLIST_ROW_TAG = "blacklist-row"

/**
 * The Blacklist screen: tabs for songs, artists and viewers (each with how many are blocked), an add field that
 * adapts to the tab, and the blocked ones as one connected list that says who blocked each and when.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BlacklistScreen(vm: BlacklistViewModel) {
    val state by vm.state.collectAsState()
    val motion = MaterialTheme.motionScheme
    PageScaffold(stringResource(Res.string.nav_blacklist), subtitle = stringResource(Res.string.blacklist_subtitle), scrolls = false) {
        AnimatedVisibility(
            visible = state.blockFailing,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            ProblemBanner(stringResource(Res.string.save_failed))
        }
        val list = state.blacklist
        Tabs(state.tab, list.tracks.size, list.artists.size, list.viewers.size, vm::onTab)
        AddField(state, vm)
        val modifier = Modifier.weight(1f).fillMaxWidth()
        when (state.tab) {
            BlacklistTab.Songs -> BlockedList(
                list.tracks.sortedByDescending { it.atEpochMs },
                key = { it.track.id },
                empty = stringResource(Res.string.blacklist_empty_songs),
                modifier = modifier,
            ) { entry, shape ->
                BlockedRow(
                    title = entry.track.name,
                    detail = entry.track.artists.joinToString(", ").ifBlank { null },
                    subtitle = blockedBy(entry.by, entry.atEpochMs),
                    removeLabel = entry.track.name,
                    shape = shape,
                    onRemove = { vm.onRemove(entry.track.id) },
                    leading = { AlbumArt(entry.track.albumArtUrl, Modifier.size(LeadingSize).clip(RoundedCornerShape(12.dp))) },
                )
            }
            BlacklistTab.Artists -> BlockedList(
                list.artists.sortedByDescending { it.atEpochMs },
                key = { it.id ?: it.name },
                empty = stringResource(Res.string.blacklist_empty_artists),
                modifier = modifier,
            ) { entry, shape ->
                BlockedRow(
                    title = entry.name,
                    subtitle = blockedBy(entry.by, entry.atEpochMs),
                    removeLabel = entry.name,
                    shape = shape,
                    onRemove = { vm.onRemove(entry.id ?: entry.name) },
                    leading = { IconTile(AppIcons.PersonOff) },
                )
            }
            BlacklistTab.Viewers -> BlockedList(
                list.viewers.sortedByDescending { it.atEpochMs },
                key = { it.twitchUserId },
                empty = stringResource(Res.string.blacklist_empty_viewers),
                modifier = modifier,
            ) { entry, shape ->
                BlockedRow(
                    title = "@${entry.login}",
                    subtitle = blockedBy(entry.by, entry.atEpochMs),
                    removeLabel = "@${entry.login}",
                    shape = shape,
                    onRemove = { vm.onRemove(entry.twitchUserId) },
                    leading = { IconTile(AppIcons.Block) },
                )
            }
        }
    }
}

private val LeadingSize = 44.dp

@Composable
private fun blockedBy(by: String?, atEpochMs: Long): String {
    val date = formatShortDate(atEpochMs)
    return if (by == null) {
        stringResource(Res.string.blacklist_by_you, date)
    } else {
        stringResource(Res.string.blacklist_by_mod, by, date)
    }
}

/** The Expressive connected button group, like the queue's tabs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Tabs(selected: BlacklistTab, songs: Int, artists: Int, viewers: Int, onSelect: (BlacklistTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(
            checked = selected == BlacklistTab.Songs,
            onCheckedChange = { onSelect(BlacklistTab.Songs) },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.blacklist_tab_songs, songs))
        }
        ToggleButton(
            checked = selected == BlacklistTab.Artists,
            onCheckedChange = { onSelect(BlacklistTab.Artists) },
            shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.blacklist_tab_artists, artists))
        }
        ToggleButton(
            checked = selected == BlacklistTab.Viewers,
            onCheckedChange = { onSelect(BlacklistTab.Viewers) },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            modifier = Modifier.semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.blacklist_tab_viewers, viewers))
        }
    }
}

/**
 * The M3 search bar of the current tab: results drop down inside it, why there are none shows below it. Picking a
 * result (or Enter, for the first) blocks it and clears the field. Without Twitch the Viewers field is disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddField(state: BlacklistUiState, vm: BlacklistViewModel) {
    val enabled = state.tab != BlacklistTab.Viewers || state.twitchConnected
    var text by rememberSaveable(state.tab) { mutableStateOf("") }
    val expanded = enabled && text.isNotBlank() && state.results.isNotEmpty()
    val change = { new: String ->
        text = new
        vm.onInput(new)
    }
    Column {
        DockedSearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = text,
                    onQueryChange = change,
                    onSearch = { if (vm.onSubmit(text)) text = "" },
                    expanded = expanded,
                    onExpandedChange = {},
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            placeholder(state.tab, enabled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    trailingIcon = if (text.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { change("") }, shapes = IconButtonDefaults.shapes()) {
                                Icon(AppIcons.Close, contentDescription = stringResource(Res.string.add_clear))
                            }
                        }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.s)) {
                state.results.forEach { candidate ->
                    CandidateRow(candidate, enabled = !state.lookingUp) {
                        text = ""
                        vm.onAdd(candidate)
                    }
                }
            }
        }
        val message = state.message.takeIf { enabled && text.isNotBlank() }
        if (message != null) {
            Text(
                messageText(message, state.tab),
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.s),
            )
        }
    }
}

private val BlacklistMessage.isError: Boolean
    get() = this == BlacklistMessage.SpotifyUnavailable || this == BlacklistMessage.TwitchUnavailable

@Composable
private fun placeholder(tab: BlacklistTab, enabled: Boolean): String = when {
    !enabled -> stringResource(Res.string.blacklist_viewers_need_twitch)
    tab == BlacklistTab.Songs -> stringResource(Res.string.blacklist_placeholder_songs)
    tab == BlacklistTab.Artists -> stringResource(Res.string.blacklist_placeholder_artists)
    else -> stringResource(Res.string.blacklist_placeholder_viewers)
}

@Composable
private fun messageText(message: BlacklistMessage, tab: BlacklistTab): String = when (message) {
    BlacklistMessage.NoResults -> stringResource(Res.string.blacklist_no_results)
    BlacklistMessage.ViewerNotFound -> stringResource(Res.string.blacklist_viewer_not_found)
    BlacklistMessage.SpotifyUnavailable -> stringResource(Res.string.add_spotify_unavailable)
    BlacklistMessage.TwitchUnavailable -> stringResource(Res.string.blacklist_twitch_unavailable)
    is BlacklistMessage.WrongLink -> {
        val kind = kindName(message.kind)
        when {
            kind == null -> stringResource(Res.string.blacklist_wrong_link_generic)
            tab == BlacklistTab.Artists -> stringResource(Res.string.blacklist_wrong_link_artists, stringResource(kind))
            else -> stringResource(Res.string.blacklist_wrong_link_songs, stringResource(kind))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CandidateRow(candidate: BlockCandidate, enabled: Boolean, onClick: () -> Unit) {
    val dim = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
    ListItem(
        onClick = onClick,
        enabled = enabled,
        leadingContent = {
            when (candidate) {
                is BlockCandidate.Song -> AlbumArt(candidate.track.albumArtUrl, dim.size(40.dp).clip(RoundedCornerShape(10.dp)))
                is BlockCandidate.Artist -> AlbumArt(candidate.artist.imageUrl, dim.size(40.dp).clip(RoundedCornerShape(20.dp)))
                is BlockCandidate.Viewer -> Icon(AppIcons.PersonOff, contentDescription = null, modifier = dim.size(24.dp))
            }
        },
        supportingContent = when (candidate) {
            is BlockCandidate.Song -> {
                { Text(candidate.track.artists.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            is BlockCandidate.Viewer -> {
                { Text("@${candidate.user.login}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            is BlockCandidate.Artist -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        val name = when (candidate) {
            is BlockCandidate.Song -> candidate.track.name
            is BlockCandidate.Artist -> candidate.artist.name
            is BlockCandidate.Viewer -> candidate.user.displayName
        }
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** M3's opacity for disabled content, for results that are waiting to be replaced. */
private const val DISABLED_ALPHA = 0.38f

/** One tab's list: connected rows, or a quiet line when nothing is blocked. */
@Composable
private fun <T> BlockedList(
    entries: List<T>,
    key: (T) -> Any,
    empty: String,
    modifier: Modifier,
    row: @Composable (T, RoundedCornerShape) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState(empty, modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RowGap),
        contentPadding = PaddingValues(bottom = Spacing.l),
    ) {
        itemsIndexed(entries, key = { _, entry -> key(entry) }) { index, entry ->
            row(entry, connectedShape(index, entries.size, dragging = false))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BlockedRow(
    title: String,
    subtitle: String,
    removeLabel: String,
    shape: RoundedCornerShape,
    onRemove: () -> Unit,
    leading: @Composable () -> Unit,
    /** Shown before [subtitle] (a song's artists); it ends in "…" when room is short, so who blocked it and when stay whole. */
    detail: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(Modifier.testTag(BLACKLIST_ROW_TAG), shape = shape, color = scheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.padding(start = Spacing.m, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The subtitle is measured first (it has no weight), the detail gets what is left.
                Row {
                    val style = typography.bodyMedium
                    val color = scheme.onSurfaceVariant
                    if (detail != null) {
                        Text(detail, Modifier.weight(1f, fill = false), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(" · ", style = style, color = color, maxLines = 1)
                    }
                    Text(subtitle, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val label = stringResource(Res.string.blacklist_unblock, removeLabel)
            IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
                Icon(AppIcons.Close, contentDescription = label)
            }
        }
    }
}

/** A quiet stand-in for art, for rows that have none (artists, viewers). */
@Composable
private fun IconTile(icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.size(LeadingSize), shape = RoundedCornerShape(OuterCorner / 2), color = scheme.surfaceContainerHighest) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
    }
}
