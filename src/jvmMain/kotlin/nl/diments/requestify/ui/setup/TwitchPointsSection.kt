package nl.diments.requestify.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.twitch.points.ScannedReward
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.rules_decrease
import nl.diments.requestify.resources.rules_increase
import nl.diments.requestify.resources.twitch_points_cancel
import nl.diments.requestify.resources.twitch_points_choose
import nl.diments.requestify.resources.twitch_points_close
import nl.diments.requestify.resources.twitch_points_copy
import nl.diments.requestify.resources.twitch_points_copy_body
import nl.diments.requestify.resources.twitch_points_copy_confirm
import nl.diments.requestify.resources.twitch_points_copy_title
import nl.diments.requestify.resources.twitch_points_cost_label
import nl.diments.requestify.resources.twitch_points_cost_value
import nl.diments.requestify.resources.twitch_points_create_again
import nl.diments.requestify.resources.twitch_points_create_duplicate
import nl.diments.requestify.resources.twitch_points_disabled
import nl.diments.requestify.resources.twitch_points_duplicate
import nl.diments.requestify.resources.twitch_points_failed
import nl.diments.requestify.resources.twitch_points_field_name
import nl.diments.requestify.resources.twitch_points_field_prompt
import nl.diments.requestify.resources.twitch_points_forbidden
import nl.diments.requestify.resources.twitch_points_group_other
import nl.diments.requestify.resources.twitch_points_group_own
import nl.diments.requestify.resources.twitch_points_hint_active
import nl.diments.requestify.resources.twitch_points_hint_choose
import nl.diments.requestify.resources.twitch_points_hint_off
import nl.diments.requestify.resources.twitch_points_limit_catchup
import nl.diments.requestify.resources.twitch_points_limit_paused
import nl.diments.requestify.resources.twitch_points_limit_readonly
import nl.diments.requestify.resources.twitch_points_limit_refunds
import nl.diments.requestify.resources.twitch_points_link_body
import nl.diments.requestify.resources.twitch_points_link_confirm
import nl.diments.requestify.resources.twitch_points_link_direct
import nl.diments.requestify.resources.twitch_points_link_needs_text
import nl.diments.requestify.resources.twitch_points_link_title
import nl.diments.requestify.resources.twitch_points_not_affiliate
import nl.diments.requestify.resources.twitch_points_out_of_sync
import nl.diments.requestify.resources.twitch_points_paused
import nl.diments.requestify.resources.twitch_points_readonly_hint
import nl.diments.requestify.resources.twitch_points_recommended
import nl.diments.requestify.resources.twitch_points_removed
import nl.diments.requestify.resources.twitch_points_scan_failed
import nl.diments.requestify.resources.twitch_points_scanning
import nl.diments.requestify.resources.twitch_points_sheet_create
import nl.diments.requestify.resources.twitch_points_sheet_create_button
import nl.diments.requestify.resources.twitch_points_sheet_create_hint
import nl.diments.requestify.resources.twitch_points_sheet_title
import nl.diments.requestify.resources.twitch_points_title
import nl.diments.requestify.resources.twitch_points_try_again
import nl.diments.requestify.resources.twitch_points_unmanaged
import nl.diments.requestify.resources.twitch_points_use
import nl.diments.requestify.resources.twitch_section_points
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.rules.RuleText
import nl.diments.requestify.ui.rules.Stepper
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

private val SheetWidth = 560.dp

/**
 * The Channel Points row of the Twitch card: the switch, the reward's cost, its status, and below it the name and text
 * (read-only for a reward that is linked without management). Without a reward it offers "Choose reward"; a reward
 * removed on Twitch offers "Create again". [eligible] is false for channels that are no Affiliate or Partner.
 */
@Composable
internal fun ChannelPointsSection(vm: TwitchSetupViewModel, eligible: Boolean) {
    val settings by vm.settings.collectAsState()
    val open by vm.requestsOpen.collectAsState()
    val problem by vm.rewardProblem.collectAsState()
    val outOfSync by vm.rewardOutOfSync.collectAsState()
    val points = settings.twitch.channelPoints
    val hasReward = points.rewardId != null
    val active = points.enabled && eligible
    val title = stringResource(Res.string.twitch_points_title)

    SectionHeader(stringResource(Res.string.twitch_section_points), AppIcons.Bolt)
    val hint = when {
        !eligible -> Res.string.twitch_points_not_affiliate
        !points.enabled -> if (hasReward && !points.rewardRemoved) Res.string.twitch_points_disabled else Res.string.twitch_points_hint_off
        !hasReward || points.rewardRemoved -> Res.string.twitch_points_hint_choose
        !points.managed -> Res.string.twitch_points_unmanaged
        !open -> Res.string.twitch_points_paused
        else -> Res.string.twitch_points_hint_active
    }
    SourceRow(
        title = title,
        hint = stringResource(hint),
        checked = active,
        enabled = eligible,
        onCheckedChange = vm::onPointsEnabled,
    ) {
        when {
            active && !hasReward -> TextButton(onClick = vm::onChooseReward, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_choose))
            }
            active && !points.rewardRemoved && points.managed -> {
                val label = stringResource(Res.string.twitch_points_cost_label)
                Stepper(
                    value = points.cost.toString(),
                    canDecrease = points.cost > REWARD_COST_MIN,
                    canIncrease = points.cost < REWARD_COST_MAX,
                    onDecrease = { vm.onRewardEdit(cost = stepDown(points.cost)) },
                    onIncrease = { vm.onRewardEdit(cost = stepUp(points.cost)) },
                    decreaseLabel = stringResource(Res.string.rules_decrease, label),
                    increaseLabel = stringResource(Res.string.rules_increase, label),
                )
            }
            active && !points.rewardRemoved -> Text(
                stringResource(Res.string.twitch_points_cost_value, points.cost.toString()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (outOfSync && hasReward && points.managed) {
        ErrorText(stringResource(Res.string.twitch_points_out_of_sync), Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs))
    }
    if (active && hasReward) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (points.rewardRemoved) {
                ErrorText(stringResource(Res.string.twitch_points_removed))
                problem?.let { ErrorText(it.message(creating = true)) }
                FilledTonalButton(onClick = vm::onCreateAgain, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.twitch_points_create_again))
                }
            } else {
                val readOnly = !points.managed
                val readOnlyHint = if (readOnly) stringResource(Res.string.twitch_points_readonly_hint) else null
                RewardTextField(
                    label = stringResource(Res.string.twitch_points_field_name),
                    value = points.title.orEmpty(),
                    readOnly = readOnly,
                    maxLength = REWARD_TITLE_MAX,
                    error = if (problem == RewardProblem.DuplicateTitle) stringResource(Res.string.twitch_points_duplicate) else null,
                    supporting = null,
                    onCommit = { vm.onRewardEdit(title = it) },
                )
                RewardTextField(
                    label = stringResource(Res.string.twitch_points_field_prompt),
                    value = points.prompt.orEmpty(),
                    readOnly = readOnly,
                    maxLength = PROMPT_MAX,
                    error = null,
                    supporting = readOnlyHint,
                    onCommit = { vm.onRewardEdit(prompt = it) },
                )
                if (problem != null && problem != RewardProblem.DuplicateTitle) ErrorText(problem!!.message(creating = false))
                if (readOnly) {
                    OutlinedButton(onClick = vm::onCopyLinkedReward, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.twitch_points_copy))
                    }
                }
            }
        }
    }
    RewardDialogs(vm)
}

/** Twitch's longest reward text. */
private const val PROMPT_MAX = 200

@Composable
private fun RewardProblem.message(creating: Boolean): String = stringResource(
    when (this) {
        RewardProblem.DuplicateTitle -> if (creating) Res.string.twitch_points_create_duplicate else Res.string.twitch_points_duplicate
        RewardProblem.NotAffiliate -> Res.string.twitch_points_forbidden
        RewardProblem.Failed -> Res.string.twitch_points_failed
    },
)

/** A name or text field that writes to Twitch when it loses focus or the user presses Enter, and only when it changed. */
@Composable
private fun RewardTextField(
    label: String,
    value: String,
    readOnly: Boolean,
    maxLength: Int,
    error: String?,
    supporting: String?,
    onCommit: (String) -> Unit,
) {
    var typed by remember(value) { mutableStateOf(value) }
    fun commit() {
        if (!readOnly && typed != value) onCommit(typed)
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.take(maxLength) },
        readOnly = readOnly,
        singleLine = true,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
    )
}

/** The "Choose reward" sheet, the copy dialog and the link warning: only the ones that are open are composed. */
@Composable
private fun RewardDialogs(vm: TwitchSetupViewModel) {
    val sheet by vm.rewardSheet.collectAsState()
    val copy by vm.copyDraft.collectAsState()
    val warning by vm.linkWarning.collectAsState()
    sheet?.let { ChooseRewardSheet(it, vm) }
    copy?.let { CopyDialog(it, vm) }
    warning?.let { LinkWarningDialog(vm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogSurface(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = SheetWidth),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChooseRewardSheet(sheet: RewardSheet, vm: TwitchSetupViewModel) {
    DialogSurface(onDismiss = vm::onCloseSheet) {
        Text(stringResource(Res.string.twitch_points_sheet_title), style = MaterialTheme.typography.headlineSmall)

        // "Create new reward" comes first, and does not wait for the scan.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.twitch_points_sheet_create), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(Res.string.twitch_points_sheet_create_hint), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = vm::onCreateReward, enabled = !sheet.creating, shapes = ButtonDefaults.shapes()) {
                    ButtonIcon(AppIcons.Add)
                    Text(stringResource(Res.string.twitch_points_sheet_create_button))
                }
            }
        }
        sheet.problem?.let { ErrorText(it.message(creating = true)) }

        when (val scan = sheet.scan) {
            RewardScan.Scanning -> Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator(Modifier.size(40.dp))
                Text(stringResource(Res.string.twitch_points_scanning), style = MaterialTheme.typography.bodyMedium)
            }
            RewardScan.Failed -> Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ErrorText(stringResource(Res.string.twitch_points_scan_failed), Modifier.weight(1f, fill = false))
                TextButton(onClick = vm::onChooseReward, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.twitch_points_try_again))
                }
            }
            is RewardScan.Done -> {
                if (scan.own.isNotEmpty()) {
                    GroupLabel(stringResource(Res.string.twitch_points_group_own))
                    scan.own.forEach { OwnRewardRow(it, vm) }
                }
                if (scan.other.isNotEmpty()) {
                    GroupLabel(stringResource(Res.string.twitch_points_group_other))
                    scan.other.forEach { ForeignRewardRow(it, vm) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = vm::onCloseSheet, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_close))
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.s),
    )
}

@Composable
private fun RewardSummary(reward: ScannedReward, modifier: Modifier = Modifier) {
    RuleText(reward.reward.title, stringResource(Res.string.twitch_points_cost_value, reward.reward.cost.toString()), modifier)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OwnRewardRow(reward: ScannedReward, vm: TwitchSetupViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RewardSummary(reward, Modifier.weight(1f))
        FilledTonalButton(onClick = { vm.onUseOwnReward(reward) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.twitch_points_use))
        }
    }
}

/**
 * A reward made elsewhere: "Copy as Requestify reward" (recommended) and, behind a warning, "Link directly". Only a
 * reward that asks viewers for text can be linked: without it a redemption carries no song.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ForeignRewardRow(reward: ScannedReward, vm: TwitchSetupViewModel) {
    val linkable = reward.reward.isUserInputRequired
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        RewardSummary(reward)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.onCopyReward(reward.reward) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_copy))
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                Text(
                    stringResource(Res.string.twitch_points_recommended),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                )
            }
            Box(Modifier.weight(1f))
            if (linkable) {
                TextButton(onClick = { vm.onLinkDirectly(reward) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.twitch_points_link_direct))
                }
            }
        }
        if (!linkable) {
            Text(
                stringResource(Res.string.twitch_points_link_needs_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CopyDialog(draft: CopyDraft, vm: TwitchSetupViewModel) {
    DialogSurface(onDismiss = vm::onDismissCopy) {
        Text(stringResource(Res.string.twitch_points_copy_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(Res.string.twitch_points_copy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.title,
            onValueChange = vm::onCopyTitle,
            singleLine = true,
            label = { Text(stringResource(Res.string.twitch_points_field_name)) },
            isError = draft.problem != null,
            supportingText = draft.problem?.let { { Text(it.message(creating = false)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
            TextButton(onClick = vm::onDismissCopy, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_cancel))
            }
            Button(onClick = vm::onConfirmCopy, enabled = !draft.busy && draft.title.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_copy_confirm))
            }
        }
    }
}

/** The four limits of a reward that is linked without management, before it is linked. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkWarningDialog(vm: TwitchSetupViewModel) {
    DialogSurface(onDismiss = vm::onDismissLinkWarning) {
        Text(stringResource(Res.string.twitch_points_link_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.twitch_points_link_body), style = MaterialTheme.typography.bodyMedium)
        listOf(
            Res.string.twitch_points_limit_refunds,
            Res.string.twitch_points_limit_paused,
            Res.string.twitch_points_limit_catchup,
            Res.string.twitch_points_limit_readonly,
        ).forEach { limit ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Icon(AppIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Text(stringResource(limit), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
            TextButton(onClick = vm::onDismissLinkWarning, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_cancel))
            }
            OutlinedButton(onClick = vm::onConfirmLink, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_points_link_confirm))
            }
        }
    }
}
