package nl.diments.requestify.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.add_clear
import nl.diments.requestify.resources.add_no_results
import nl.diments.requestify.resources.add_not_a_track
import nl.diments.requestify.resources.add_not_a_track_generic
import nl.diments.requestify.resources.add_placeholder
import nl.diments.requestify.resources.add_spotify_unavailable
import nl.diments.requestify.resources.spotify_kind_album
import nl.diments.requestify.resources.spotify_kind_artist
import nl.diments.requestify.resources.spotify_kind_episode
import nl.diments.requestify.resources.spotify_kind_playlist
import nl.diments.requestify.resources.spotify_kind_show
import nl.diments.requestify.resources.spotify_kind_track
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.theme.Spacing
import nl.diments.requestify.ui.theme.fadeThrough
import nl.diments.requestify.ui.theme.sizeTransform
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The M3 search bar for adding a track: search text or a pasted Spotify link. Results drop down inside the
 * bar, why there are none shows below it. Picking a result adds it; Enter asks [onSubmit] to add the first
 * result for the typed text. Either clears the field. While [resultsStale] (the next lookup is still running)
 * the previous results stay visible but dimmed and cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTrackField(
    results: List<Track>,
    resultsStale: Boolean,
    message: AddMessage?,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Boolean,
    onPick: (Track) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var text by rememberSaveable { mutableStateOf("") }
    val expanded = text.isNotBlank() && results.isNotEmpty()
    val change = { new: String ->
        text = new
        onInput(new)
    }
    val pick = { track: Track ->
        text = ""
        onPick(track)
    }

    Column(modifier) {
        DockedSearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = text,
                    onQueryChange = change,
                    onSearch = { if (onSubmit(text)) text = "" },
                    expanded = expanded,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    placeholder = {
                        Text(stringResource(Res.string.add_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    trailingIcon = if (text.isEmpty()) {
                        null
                    } else {
                        { ClearButton(onClick = { change("") }) }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.s)) {
                results.forEach { track -> ResultRow(track, enabled = !resultsStale, onClick = { pick(track) }) }
            }
        }
        MessageLine(message.takeIf { text.isNotBlank() })
    }
}

/** Why there are no results, as a supporting line under the bar. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MessageLine(message: AddMessage?) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = message,
        transitionSpec = {
            motion.fadeThrough() using motion.sizeTransform()
        },
    ) { shown ->
        if (shown != null) {
            val scheme = MaterialTheme.colorScheme
            Text(
                messageText(shown),
                style = MaterialTheme.typography.bodyMedium,
                color = if (shown == AddMessage.SpotifyUnavailable) scheme.error else scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.s),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClearButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(AppIcons.Close, contentDescription = stringResource(Res.string.add_clear))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultRow(track: Track, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        enabled = enabled,
        leadingContent = {
            AlbumArt(
                track.albumArtUrl,
                Modifier.size(40.dp).alpha(if (enabled) 1f else DISABLED_ALPHA).clip(RoundedCornerShape(10.dp)),
            )
        },
        supportingContent = { Text(track.artists.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelLarge) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun messageText(message: AddMessage): String = when (message) {
    AddMessage.NoResults -> stringResource(Res.string.add_no_results)
    AddMessage.SpotifyUnavailable -> stringResource(Res.string.add_spotify_unavailable)
    is AddMessage.NotATrack -> when (val kind = kindName(message.kind)) {
        null -> stringResource(Res.string.add_not_a_track_generic)
        else -> stringResource(Res.string.add_not_a_track, stringResource(kind))
    }
}

/** Plural nouns for the link kinds [nl.diments.requestify.core.spotify.classifySpotifyInput] reports. */
internal fun kindName(kind: String): StringResource? = when (kind) {
    "album" -> Res.string.spotify_kind_album
    "playlist" -> Res.string.spotify_kind_playlist
    "artist" -> Res.string.spotify_kind_artist
    "track" -> Res.string.spotify_kind_track
    "episode" -> Res.string.spotify_kind_episode
    "show" -> Res.string.spotify_kind_show
    else -> null
}

/** M3's opacity for disabled content, for the art of a result that is waiting to be replaced. */
private const val DISABLED_ALPHA = 0.38f
