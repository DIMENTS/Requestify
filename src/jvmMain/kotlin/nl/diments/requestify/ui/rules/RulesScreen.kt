package nl.diments.requestify.ui.rules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.rules.Rules
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.nav_rules
import nl.diments.requestify.resources.rules_block_explicit
import nl.diments.requestify.resources.rules_cooldown
import nl.diments.requestify.resources.rules_cooldown_hint
import nl.diments.requestify.resources.rules_decrease
import nl.diments.requestify.resources.rules_duplicate_window
import nl.diments.requestify.resources.rules_group_queue
import nl.diments.requestify.resources.rules_group_songs
import nl.diments.requestify.resources.rules_increase
import nl.diments.requestify.resources.rules_max_length
import nl.diments.requestify.resources.rules_max_queue
import nl.diments.requestify.resources.rules_min_length
import nl.diments.requestify.resources.rules_minutes
import nl.diments.requestify.resources.rules_no_duplicates
import nl.diments.requestify.resources.rules_no_duplicates_hint
import nl.diments.requestify.resources.rules_note
import nl.diments.requestify.resources.rules_off
import nl.diments.requestify.resources.rules_paid_first
import nl.diments.requestify.resources.rules_per_viewer
import nl.diments.requestify.resources.rules_subtitle
import nl.diments.requestify.resources.save_failed
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.PageScaffold
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The bounds and steps of the steppers: a value outside them (a hand-edited file) steps back inside them. */
private object Limits {
    val queueLength = 1..100
    val perViewer = 1..10
    val cooldownMinutes = 0..60
    val duplicateWindowMinutes = 15..240
    const val DUPLICATE_STEP_MINUTES = 15
    val minLengthMs = 0L..120_000L
    const val MIN_LENGTH_STEP_MS = 15_000L
    val maxLengthMs = 120_000L..900_000L
    const val MAX_LENGTH_STEP_MS = 30_000L
}

/**
 * The Rules screen: one scrolling page in two groups, Queue and Songs, under a note on what paid requests skip.
 * Every change is saved at once; one that cannot be saved is not applied, and the page says so until a later
 * change saves.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RulesScreen(settings: SettingsStore) {
    val current by settings.settings.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    fun save(change: (Settings) -> Settings) {
        saveFailed = !settings.tryUpdate(change)
    }
    fun saveRules(change: (Rules) -> Rules) = save { it.copy(rules = change(it.rules)) }

    val rules = current.rules
    val motion = MaterialTheme.motionScheme
    PageScaffold(stringResource(Res.string.nav_rules), subtitle = stringResource(Res.string.rules_subtitle)) {
        AnimatedVisibility(
            visible = saveFailed,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            ProblemBanner(stringResource(Res.string.save_failed))
        }
        InfoNote(stringResource(Res.string.rules_note))
        RuleGroup(stringResource(Res.string.rules_group_queue), AppIcons.QueueMusic) {
            StepperRow(
                title = stringResource(Res.string.rules_max_queue),
                value = rules.maxQueueLength.toString(),
                canDecrease = rules.maxQueueLength > Limits.queueLength.first,
                canIncrease = rules.maxQueueLength < Limits.queueLength.last,
                onDecrease = { saveRules { it.copy(maxQueueLength = (it.maxQueueLength - 1).coerceIn(Limits.queueLength)) } },
                onIncrease = { saveRules { it.copy(maxQueueLength = (it.maxQueueLength + 1).coerceIn(Limits.queueLength)) } },
            )
            RowDivider()
            StepperRow(
                title = stringResource(Res.string.rules_per_viewer),
                value = rules.maxPerViewer.toString(),
                canDecrease = rules.maxPerViewer > Limits.perViewer.first,
                canIncrease = rules.maxPerViewer < Limits.perViewer.last,
                onDecrease = { saveRules { it.copy(maxPerViewer = (it.maxPerViewer - 1).coerceIn(Limits.perViewer)) } },
                onIncrease = { saveRules { it.copy(maxPerViewer = (it.maxPerViewer + 1).coerceIn(Limits.perViewer)) } },
            )
            RowDivider()
            StepperRow(
                title = stringResource(Res.string.rules_cooldown),
                hint = stringResource(Res.string.rules_cooldown_hint),
                value = if (rules.cooldownMinutes <= 0) stringResource(Res.string.rules_off) else minutes(rules.cooldownMinutes),
                canDecrease = rules.cooldownMinutes > Limits.cooldownMinutes.first,
                canIncrease = rules.cooldownMinutes < Limits.cooldownMinutes.last,
                onDecrease = { saveRules { it.copy(cooldownMinutes = (it.cooldownMinutes - 1).coerceIn(Limits.cooldownMinutes)) } },
                onIncrease = { saveRules { it.copy(cooldownMinutes = (it.cooldownMinutes + 1).coerceIn(Limits.cooldownMinutes)) } },
            )
            RowDivider()
            SwitchRow(
                title = stringResource(Res.string.rules_no_duplicates),
                hint = stringResource(Res.string.rules_no_duplicates_hint),
                checked = rules.noDuplicates,
                onChange = { on -> saveRules { it.copy(noDuplicates = on) } },
            )
            AnimatedVisibility(
                visible = rules.noDuplicates,
                enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Column {
                    RowDivider()
                    StepperRow(
                        title = stringResource(Res.string.rules_duplicate_window),
                        value = minutes(rules.duplicateWindowMinutes),
                        canDecrease = rules.duplicateWindowMinutes > Limits.duplicateWindowMinutes.first,
                        canIncrease = rules.duplicateWindowMinutes < Limits.duplicateWindowMinutes.last,
                        onDecrease = {
                            saveRules {
                                it.copy(
                                    duplicateWindowMinutes = (it.duplicateWindowMinutes - Limits.DUPLICATE_STEP_MINUTES)
                                        .coerceIn(Limits.duplicateWindowMinutes),
                                )
                            }
                        },
                        onIncrease = {
                            saveRules {
                                it.copy(
                                    duplicateWindowMinutes = (it.duplicateWindowMinutes + Limits.DUPLICATE_STEP_MINUTES)
                                        .coerceIn(Limits.duplicateWindowMinutes),
                                )
                            }
                        },
                    )
                }
            }
            RowDivider()
            SwitchRow(
                title = stringResource(Res.string.rules_paid_first),
                checked = current.paidPriority,
                onChange = { on -> save { it.copy(paidPriority = on) } },
            )
        }
        RuleGroup(stringResource(Res.string.rules_group_songs), AppIcons.MusicNote) {
            StepperRow(
                title = stringResource(Res.string.rules_min_length),
                value = formatDuration(rules.minDurationMs),
                canDecrease = rules.minDurationMs > Limits.minLengthMs.first,
                canIncrease = rules.minDurationMs < Limits.minLengthMs.last,
                onDecrease = {
                    saveRules { it.copy(minDurationMs = (it.minDurationMs - Limits.MIN_LENGTH_STEP_MS).coerceIn(Limits.minLengthMs)) }
                },
                onIncrease = {
                    saveRules { it.copy(minDurationMs = (it.minDurationMs + Limits.MIN_LENGTH_STEP_MS).coerceIn(Limits.minLengthMs)) }
                },
            )
            RowDivider()
            StepperRow(
                title = stringResource(Res.string.rules_max_length),
                value = formatDuration(rules.maxDurationMs),
                canDecrease = rules.maxDurationMs > Limits.maxLengthMs.first,
                canIncrease = rules.maxDurationMs < Limits.maxLengthMs.last,
                onDecrease = {
                    saveRules { it.copy(maxDurationMs = (it.maxDurationMs - Limits.MAX_LENGTH_STEP_MS).coerceIn(Limits.maxLengthMs)) }
                },
                onIncrease = {
                    saveRules { it.copy(maxDurationMs = (it.maxDurationMs + Limits.MAX_LENGTH_STEP_MS).coerceIn(Limits.maxLengthMs)) }
                },
            )
            RowDivider()
            SwitchRow(
                title = stringResource(Res.string.rules_block_explicit),
                checked = rules.blockExplicit,
                onChange = { on -> saveRules { it.copy(blockExplicit = on) } },
            )
        }
    }
}

/** What paid requests skip, in a quiet container with a tertiary bolt: the same bolt a paid request wears. */
@Composable
private fun InfoNote(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A group of rules: its heading with an icon, then its rows, divided by hairlines, on one rounded surface. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuleGroup(title: String, icon: ImageVector, rows: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = Spacing.s)) {
            Row(
                modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            }
            rows()
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = Spacing.l),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** The room every row keeps, so a row with a hint and one without are about as tall. */
private val RowMinHeight = 56.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuleText(title: String, hint: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row with a switch; the whole row toggles, and is one control for assistive tools. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuleText(title, hint, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
    }
}

/** A row with a stepper: a pill with a minus, the value and a plus; a button is disabled at its bound. */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    hint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuleText(title, hint, Modifier.weight(1f))
        Stepper(
            value = value,
            canDecrease = canDecrease,
            canIncrease = canIncrease,
            onDecrease = onDecrease,
            onIncrease = onIncrease,
            decreaseLabel = stringResource(Res.string.rules_decrease, title),
            increaseLabel = stringResource(Res.string.rules_increase, title),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Stepper(
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease, enabled = canDecrease, shapes = IconButtonDefaults.shapes()) {
                Icon(AppIcons.Remove, contentDescription = decreaseLabel)
            }
            Text(
                value,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 56.dp),
                maxLines = 1,
            )
            IconButton(onClick = onIncrease, enabled = canIncrease, shapes = IconButtonDefaults.shapes()) {
                Icon(AppIcons.Add, contentDescription = increaseLabel)
            }
        }
    }
}

@Composable
private fun minutes(value: Int): String = stringResource(Res.string.rules_minutes, value)
