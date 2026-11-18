package nl.diments.requestify.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import nl.diments.requestify.core.model.IgnoredReason
import nl.diments.requestify.core.log.LogConnectionState
import nl.diments.requestify.core.log.LogKind
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.log.LogRow
import nl.diments.requestify.core.log.LogService
import nl.diments.requestify.core.log.LogStep
import nl.diments.requestify.core.log.LogStreamChange
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.playback.RemoveReason
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.log_conn_key_expiring
import nl.diments.requestify.resources.log_conn_key_refused
import nl.diments.requestify.resources.log_conn_login_expired
import nl.diments.requestify.resources.log_conn_lost
import nl.diments.requestify.resources.log_conn_restored
import nl.diments.requestify.resources.log_conn_restored_after
import nl.diments.requestify.resources.log_details_close
import nl.diments.requestify.resources.log_details_message
import nl.diments.requestify.resources.log_details_source
import nl.diments.requestify.resources.log_details_timeline
import nl.diments.requestify.resources.log_details_viewer
import nl.diments.requestify.resources.log_empty
import nl.diments.requestify.resources.log_empty_filtered
import nl.diments.requestify.resources.log_filter_all
import nl.diments.requestify.resources.log_filter_connections
import nl.diments.requestify.resources.log_filter_problems
import nl.diments.requestify.resources.log_filter_requests
import nl.diments.requestify.resources.log_gap
import nl.diments.requestify.resources.log_gap_before
import nl.diments.requestify.resources.log_gap_bits
import nl.diments.requestify.resources.log_gap_tips
import nl.diments.requestify.resources.log_search
import nl.diments.requestify.resources.log_service_log
import nl.diments.requestify.resources.log_service_files
import nl.diments.requestify.resources.log_app_no_crash
import nl.diments.requestify.resources.log_app_restarted
import nl.diments.requestify.resources.log_app_stopped
import nl.diments.requestify.resources.log_app_updated
import nl.diments.requestify.resources.log_update_failed
import nl.diments.requestify.resources.log_update_failed_code
import nl.diments.requestify.resources.log_save_failing
import nl.diments.requestify.resources.log_save_restored_after
import nl.diments.requestify.resources.log_step_fulfilled
import nl.diments.requestify.resources.log_step_handed_over
import nl.diments.requestify.resources.log_step_ignored
import nl.diments.requestify.resources.log_step_received_given_at
import nl.diments.requestify.resources.log_conn_ended_with_app
import nl.diments.requestify.resources.log_conn_source_changed
import nl.diments.requestify.resources.log_step_ignored_failed
import nl.diments.requestify.resources.log_step_ignored_moderation
import nl.diments.requestify.resources.log_step_queued_next
import nl.diments.requestify.resources.log_step_pending
import nl.diments.requestify.resources.log_step_played
import nl.diments.requestify.resources.log_step_queued
import nl.diments.requestify.resources.log_step_received
import nl.diments.requestify.resources.log_step_refund_gave_up
import nl.diments.requestify.resources.log_step_refund_pending
import nl.diments.requestify.resources.log_step_refunded
import nl.diments.requestify.resources.log_step_rejected
import nl.diments.requestify.resources.log_step_removed_blacklisted
import nl.diments.requestify.resources.log_step_removed_mod
import nl.diments.requestify.resources.log_step_removed_stream_ended
import nl.diments.requestify.resources.log_step_removed_streamer
import nl.diments.requestify.resources.log_step_removed_wrong_song
import nl.diments.requestify.resources.log_step_track
import nl.diments.requestify.resources.log_stream
import nl.diments.requestify.resources.log_stream_closed
import nl.diments.requestify.resources.log_stream_ended
import nl.diments.requestify.resources.log_stream_live
import nl.diments.requestify.resources.log_stream_offline
import nl.diments.requestify.resources.log_stream_opened
import nl.diments.requestify.resources.log_subtitle
import nl.diments.requestify.resources.log_title
import nl.diments.requestify.resources.log_today
import nl.diments.requestify.resources.log_write_error
import nl.diments.requestify.resources.log_yesterday
import nl.diments.requestify.resources.log_you
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.home.EmptyState
import nl.diments.requestify.ui.home.RowGap
import nl.diments.requestify.ui.home.connectedShape
import nl.diments.requestify.ui.home.text
import nl.diments.requestify.ui.theme.SourceChip
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The test tag of each row of the Log. */
internal const val LOG_ROW_TAG = "log-row"

/** The widest the Log grows on a wide window. */
private val LogMaxWidth = 960.dp

/**
 * The Logboek: what went right or wrong, and why. Rows by day, newest first; filter chips and a search narrow them;
 * a row opens its details with everything that happened to it. A banner says when the Log cannot save.
 */
@Composable
fun LogScreen(vm: LogViewModel) {
    val days by vm.days.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val selected by vm.selected.collectAsState()
    val writeError by vm.writeError.collectAsState()
    val zone = vm.zone

    Column(
        Modifier.fillMaxSize().padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl).widthIn(max = LogMaxWidth),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(Res.string.log_title), style = MaterialTheme.typography.headlineMediumEmphasized)
            Text(
                stringResource(Res.string.log_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        writeError?.let { ProblemBanner(stringResource(Res.string.log_write_error, it)) }
        Filters(filter, vm::setFilter)
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            singleLine = true,
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            placeholder = { Text(stringResource(Res.string.log_search)) },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        if (days.isEmpty()) {
            val empty = if (filter == LogFilter.All && query.isBlank()) Res.string.log_empty else Res.string.log_empty_filtered
            EmptyState(stringResource(empty), Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RowGap),
                contentPadding = PaddingValues(bottom = Spacing.l),
            ) {
                days.forEach { day ->
                    item(key = "day:${day.date}") { DayHeader(day.date, zone) }
                    itemsIndexed(day.rows, key = { _, row -> row.id }) { index, row ->
                        LogRowItem(row, zone, connectedShape(index, day.rows.size, dragging = false), onClick = { vm.select(row.id) })
                    }
                }
            }
        }
    }
    selected?.let { row -> Details(row, zone, onClose = { vm.select(null) }) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Filters(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    val all = LogFilter.entries
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        all.forEachIndexed { index, filter ->
            ToggleButton(
                checked = selected == filter,
                onCheckedChange = { onSelect(filter) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    all.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(filter.label()))
            }
        }
    }
}

private fun LogFilter.label() = when (this) {
    LogFilter.All -> Res.string.log_filter_all
    LogFilter.Requests -> Res.string.log_filter_requests
    LogFilter.Connections -> Res.string.log_filter_connections
    LogFilter.Problems -> Res.string.log_filter_problems
}

@Composable
private fun DayHeader(date: LocalDate, zone: ZoneId) {
    val today = LocalDate.now(zone)
    val text = when (date) {
        today -> stringResource(Res.string.log_today)
        today.minusDays(1) -> stringResource(Res.string.log_yesterday)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
    }
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
    )
}

@Composable
private fun LogRowItem(row: LogRow, zone: ZoneId, shape: RoundedCornerShape, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(
        Modifier.fillMaxWidth().testTag(LOG_ROW_TAG).clickable(onClick = onClick),
        shape = shape,
        color = scheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(timeOf(row.atEpochMs, zone), style = typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
            RowChip(row)
            Column(Modifier.weight(1f)) {
                // A row without a second line (a gap, an outage) shows its problem in the title itself.
                val titleColor = if (row.isProblem && row.kind != LogKind.Request) scheme.error else scheme.onSurface
                Text(row.title(zone), style = typography.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.stateText(zone)?.let {
                    Text(
                        it,
                        style = typography.labelMedium,
                        color = if (row.isProblem) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowChip(row: LogRow) {
    when (row.kind) {
        LogKind.Request -> row.received?.let { SourceChip(it.source, sourceLabel(it.source, it.payment)) }
        LogKind.Connection -> {
            val service = (row.records.first() as LogRecord.ConnectionChanged).service
            SourceChip(RequestSource.MANUAL, service.label())
        }
        LogKind.Stream -> SourceChip(RequestSource.MANUAL, stringResource(Res.string.log_stream))
        LogKind.Gap -> {
            val source = (row.records.first() as LogRecord.GapNoticed).source
            SourceChip(source, gapWhat(source))
        }
        LogKind.App -> SourceChip(RequestSource.MANUAL, "Requestify")
    }
}

/** The row's main line: who and what for a request, else what happened. */
@Composable
private fun LogRow.title(zone: ZoneId): String = when (kind) {
    LogKind.Request -> {
        val who = received?.requester?.displayName?.ifBlank { null } ?: stringResource(Res.string.log_you)
        "$who · ${track?.name ?: received?.input.orEmpty()}"
    }
    LogKind.Connection -> {
        val changes = records.filterIsInstance<LogRecord.ConnectionChanged>()
        val last = changes.last()
        if (last.service == LogService.Files) {
            val took = shortDuration(last.atEpochMs - changes.first().atEpochMs)
            if (last.state == LogConnectionState.Restored) {
                stringResource(Res.string.log_save_restored_after, took)
            } else {
                stringResource(Res.string.log_save_failing, changes.first().detail.orEmpty())
            }
        } else if (last.state == LogConnectionState.Restored) {
            stringResource(Res.string.log_conn_restored_after, shortDuration(last.atEpochMs - changes.first().atEpochMs))
        } else {
            last.state.text()
        }
    }
    LogKind.Stream -> (records.first() as LogRecord.StreamChanged).change.text(this)
    LogKind.Gap -> (records.first() as LogRecord.GapNoticed).text(zone)
    LogKind.App -> when (val first = records.first()) {
        is LogRecord.AppStopped -> first.title(zone)
        is LogRecord.AppUpdated -> stringResource(Res.string.log_app_updated, first.from, first.to)
        is LogRecord.UpdateFailed -> stringResource(Res.string.log_update_failed, first.version)
        else -> ""
    }
}

/** The second line: where a request stands now; null for other rows. */
@Composable
private fun LogRow.stateText(zone: ZoneId): String? {
    if (kind != LogKind.Request) return null
    return latestStep?.text() ?: stringResource(Res.string.log_step_received)
}

@Composable
private fun LogStep.text(): String = when (this) {
    // 0: the request went to Spotify before its place was read; there is no place 0.
    is LogStep.Queued -> if (position >= 1) stringResource(Res.string.log_step_queued, position) else stringResource(Res.string.log_step_queued_next)
    is LogStep.Rejected -> stringResource(Res.string.log_step_rejected, reason.text())
    LogStep.Pending -> stringResource(Res.string.log_step_pending)
    is LogStep.Ignored -> when (reason) {
        IgnoredReason.NoLink -> stringResource(Res.string.log_step_ignored)
        IgnoredReason.ModerationRejected -> stringResource(Res.string.log_step_ignored_moderation)
        IgnoredReason.TipFailed -> stringResource(Res.string.log_step_ignored_failed)
    }
    LogStep.HandedOver -> stringResource(Res.string.log_step_handed_over)
    LogStep.Played -> stringResource(Res.string.log_step_played)
    is LogStep.Removed -> stringResource(
        when (why) {
            RemoveReason.Streamer -> Res.string.log_step_removed_streamer
            RemoveReason.WrongSong -> Res.string.log_step_removed_wrong_song
            RemoveReason.ModRemove -> Res.string.log_step_removed_mod
            RemoveReason.StreamEnded -> Res.string.log_step_removed_stream_ended
            RemoveReason.Blacklisted -> Res.string.log_step_removed_blacklisted
        },
    )
    is LogStep.RefundPending -> stringResource(Res.string.log_step_refund_pending)
    LogStep.Refunded -> stringResource(Res.string.log_step_refunded)
    LogStep.RefundGaveUp -> stringResource(Res.string.log_step_refund_gave_up)
    LogStep.Fulfilled -> stringResource(Res.string.log_step_fulfilled)
    is LogStep.Resolved -> stringResource(Res.string.log_step_track, track.name)
}

@Composable
private fun LogConnectionState.text(): String = stringResource(
    when (this) {
        LogConnectionState.Lost -> Res.string.log_conn_lost
        LogConnectionState.Restored -> Res.string.log_conn_restored
        LogConnectionState.LoginExpired -> Res.string.log_conn_login_expired
        LogConnectionState.KeyRefused -> Res.string.log_conn_key_refused
        LogConnectionState.KeyExpiring -> Res.string.log_conn_key_expiring
        LogConnectionState.SourceChanged -> Res.string.log_conn_source_changed
        LogConnectionState.EndedWithApp -> Res.string.log_conn_ended_with_app
    },
)

@Composable
private fun LogStreamChange.text(row: LogRow): String = when (this) {
    LogStreamChange.Live -> stringResource(Res.string.log_stream_live)
    LogStreamChange.Offline -> stringResource(Res.string.log_stream_offline)
    LogStreamChange.OpenedByHand -> stringResource(Res.string.log_stream_opened)
    LogStreamChange.ClosedByHand -> stringResource(Res.string.log_stream_closed)
    LogStreamChange.StreamEnded -> stringResource(Res.string.log_stream_ended, row.clearedCount, row.refundedCount)
}

@Composable
private fun LogRecord.GapNoticed.text(zone: ZoneId): String {
    val what = gapWhat(source)
    val from = fromEpochMs
    return if (from == null) {
        stringResource(Res.string.log_gap_before, what, timeOf(toEpochMs, zone))
    } else {
        stringResource(Res.string.log_gap, what, timeOf(from, zone), timeOf(toEpochMs, zone))
    }
}

@Composable
private fun gapWhat(source: RequestSource): String =
    stringResource(if (source == RequestSource.BITS) Res.string.log_gap_bits else Res.string.log_gap_tips)

@Composable
private fun LogService.label(): String = when (this) {
    LogService.Spotify -> "Spotify"
    LogService.Twitch -> "Twitch"
    LogService.DonoLink -> "DonoLink"
    LogService.StreamElements -> "StreamElements"
    LogService.Log -> stringResource(Res.string.log_service_log)
    LogService.Files -> stringResource(Res.string.log_service_files)
}

/** One record of a row's timeline, as text. */
@Composable
private fun LogRecord.text(row: LogRow, zone: ZoneId): String = when (this) {
    is LogRecord.RequestReceived -> occurredAtEpochMs?.takeIf { atEpochMs - it >= 60_000 }
        ?.let { stringResource(Res.string.log_step_received_given_at, timeOf(it, zone)) }
        ?: stringResource(Res.string.log_step_received)
    is LogRecord.RequestStep -> step.text()
    is LogRecord.ConnectionChanged -> state.text()
    is LogRecord.StreamChanged -> change.text(row)
    is LogRecord.GapNoticed -> text(zone)
    is LogRecord.AppStopped -> crashLine ?: stringResource(Res.string.log_app_no_crash)
    is LogRecord.AppUpdated -> stringResource(Res.string.log_app_updated, from, to)
    is LogRecord.UpdateFailed -> exitCode?.let { stringResource(Res.string.log_update_failed_code, it) }
        ?: reason
        ?: stringResource(Res.string.log_update_failed, version)
}

/** "Requestify stopped unexpectedly (last active 21:14)", and " · restarted" when it started itself again. */
@Composable
private fun LogRecord.AppStopped.title(zone: ZoneId): String =
    stringResource(Res.string.log_app_stopped, timeOf(lastAliveEpochMs, zone)) +
        if (restarted) stringResource(Res.string.log_app_restarted) else ""

@Composable
private fun Details(row: LogRow, zone: ZoneId, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(row.title(zone)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.received?.let { r ->
                    Field(stringResource(Res.string.log_details_source), sourceLabel(r.source, r.payment))
                    r.requester.displayName.takeIf { it.isNotBlank() }?.let { Field(stringResource(Res.string.log_details_viewer), it) }
                    Field(stringResource(Res.string.log_details_message), r.input)
                }
                Text(
                    stringResource(Res.string.log_details_timeline),
                    style = typography.titleSmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(top = Spacing.s),
                )
                row.records.forEach { record ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        Text(timeOf(record.atEpochMs, zone), style = typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                        Text(record.text(row, zone), style = typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.log_details_close)) } },
    )
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** How long an outage lasted, roughly: "4 s", "3 min", "2 h". */
internal fun shortDuration(ms: Long): String = when {
    ms < 60_000 -> "${(ms / 1_000).coerceAtLeast(1)} s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h"
}

private fun timeOf(epochMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(zone))
