package nl.diments.requestify.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.intake.RejectedEntry
import nl.diments.requestify.core.model.RejectReason
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.home_paid_request
import nl.diments.requestify.resources.home_rejected_empty
import nl.diments.requestify.resources.reject_below_minimum
import nl.diments.requestify.resources.reject_blacklisted
import nl.diments.requestify.resources.reject_closed
import nl.diments.requestify.resources.reject_cooldown
import nl.diments.requestify.resources.reject_duplicate
import nl.diments.requestify.resources.reject_explicit
import nl.diments.requestify.resources.reject_no_match
import nl.diments.requestify.resources.reject_not_a_track
import nl.diments.requestify.resources.reject_queue_full
import nl.diments.requestify.resources.reject_short_link
import nl.diments.requestify.resources.reject_below_minimum_amount
import nl.diments.requestify.resources.reject_other_currency
import nl.diments.requestify.ui.common.formatMoney
import java.util.Locale
import nl.diments.requestify.resources.reject_spotify_unavailable
import nl.diments.requestify.resources.reject_too_long
import nl.diments.requestify.resources.reject_too_short
import nl.diments.requestify.resources.reject_unplayable
import nl.diments.requestify.resources.reject_viewer_limit
import nl.diments.requestify.resources.rejected_add_anyway
import nl.diments.requestify.resources.rejected_dismiss
import nl.diments.requestify.resources.rejected_refunded
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.common.requesterLabel
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.theme.SourceChip
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The test tag of each row of the Rejected list. */
internal const val REJECTED_ROW_TAG = "rejected-row"

/** The text for why a request was not let in, for instance "Longer than 7:00". */
@Composable
internal fun RejectReason.text(): String = when (this) {
    is RejectReason.NotATrack -> stringResource(Res.string.reject_not_a_track)
    RejectReason.NoMatch -> stringResource(Res.string.reject_no_match)
    RejectReason.Unplayable -> stringResource(Res.string.reject_unplayable)
    RejectReason.SpotifyUnavailable -> stringResource(Res.string.reject_spotify_unavailable)
    RejectReason.ViewerBlocked, RejectReason.TrackBlocked, RejectReason.ArtistBlocked ->
        stringResource(Res.string.reject_blacklisted)
    RejectReason.Explicit -> stringResource(Res.string.reject_explicit)
    is RejectReason.TooShort -> stringResource(Res.string.reject_too_short, formatDuration(minMs))
    is RejectReason.TooLong -> stringResource(Res.string.reject_too_long, formatDuration(maxMs))
    RejectReason.Closed -> stringResource(Res.string.reject_closed)
    is RejectReason.QueueFull -> stringResource(Res.string.reject_queue_full, max)
    is RejectReason.ViewerLimit -> stringResource(Res.string.reject_viewer_limit, max)
    is RejectReason.Cooldown -> stringResource(Res.string.reject_cooldown, formatDuration(remainingMs))
    RejectReason.Duplicate -> stringResource(Res.string.reject_duplicate)
    is RejectReason.BelowMinimum -> stringResource(Res.string.reject_below_minimum, minBits)
    RejectReason.ShortLinkUnresolved -> stringResource(Res.string.reject_short_link)
    is RejectReason.BelowMinimumAmount ->
        stringResource(Res.string.reject_below_minimum_amount, formatMoney(minCents, currency, Locale.getDefault()))
    is RejectReason.OtherCurrency -> stringResource(Res.string.reject_other_currency, currency)
}

private fun RejectReason.icon(): ImageVector = when (this) {
    is RejectReason.NotATrack, RejectReason.NoMatch, RejectReason.Unplayable, RejectReason.SpotifyUnavailable -> AppIcons.MusicOff
    RejectReason.ViewerBlocked, is RejectReason.ViewerLimit, is RejectReason.Cooldown -> AppIcons.PersonOff
    else -> AppIcons.Block
}

/**
 * The Rejected tab: one connected list, every row alike. A row says who asked, what for and why it was not let
 * in (in the error colour, with "points refunded" when the points went back), with "Add anyway" (when the track
 * is known) and a dismiss button.
 */
@Composable
internal fun RejectedList(
    entries: List<RejectedEntry>,
    onAddAnyway: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        EmptyState(stringResource(Res.string.home_rejected_empty), modifier)
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RowGap),
        contentPadding = PaddingValues(bottom = Spacing.l),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            RejectedRow(
                entry = entry,
                shape = connectedShape(index, entries.size, dragging = false),
                onAddAnyway = { onAddAnyway(entry.id) },
                onDismiss = { onDismiss(entry.id) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RejectedRow(entry: RejectedEntry, shape: RoundedCornerShape, onAddAnyway: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val track = entry.request?.track
    Surface(Modifier.testTag(REJECTED_ROW_TAG), shape = shape, color = scheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.padding(start = Spacing.m, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (track != null) {
                AlbumArt(track.albumArtUrl, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                // The track could not be found: a quiet stand-in for the art.
                Surface(Modifier.size(44.dp), shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHighest) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.MusicOff, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(track?.name ?: entry.input, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track != null) {
                    Text(
                        track.artists.joinToString(", "),
                        style = typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(entry.reason.icon(), contentDescription = null, tint = scheme.error, modifier = Modifier.size(14.dp))
                    val reason = entry.reason.text()
                    Text(
                        if (entry.refunded) stringResource(Res.string.rejected_refunded, reason) else reason,
                        style = typography.labelMedium,
                        color = scheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SourceChip(entry.source, sourceLabel(entry.source, entry.payment))
                Text(
                    requesterLabel(entry.source, entry.requester),
                    style = typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box(Modifier.size(18.dp)) {
                if (entry.payment != null) {
                    Icon(
                        AppIcons.Bolt,
                        contentDescription = stringResource(Res.string.home_paid_request),
                        tint = scheme.tertiary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.request != null) {
                    val addLabel = stringResource(Res.string.rejected_add_anyway)
                    FilledTonalIconButton(onClick = onAddAnyway, shapes = IconButtonDefaults.shapes()) {
                        Icon(AppIcons.PlaylistAdd, contentDescription = addLabel)
                    }
                }
                val dismissLabel = stringResource(Res.string.rejected_dismiss)
                IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                    Icon(AppIcons.Close, contentDescription = dismissLabel)
                }
            }
        }
    }
}
