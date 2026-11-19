package nl.diments.requestify.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.ParseException
import java.util.Locale
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.donations_donolink_help
import nl.diments.requestify.resources.donations_intro
import nl.diments.requestify.resources.donations_jwt_label
import nl.diments.requestify.resources.donations_key_expires_in
import nl.diments.requestify.resources.donations_key_label
import nl.diments.requestify.resources.donations_key_refused_donolink
import nl.diments.requestify.resources.donations_key_refused_streamelements
import nl.diments.requestify.resources.donations_key_saved
import nl.diments.requestify.resources.donations_key_valid_until
import nl.diments.requestify.resources.donations_linked
import nl.diments.requestify.resources.donations_minimum
import nl.diments.requestify.resources.donations_minimum_hint_donolink
import nl.diments.requestify.resources.donations_minimum_hint_streamelements
import nl.diments.requestify.resources.donations_minimum_hint_streamelements_unknown
import nl.diments.requestify.resources.donations_minimum_invalid
import nl.diments.requestify.resources.donations_no_catch_up
import nl.diments.requestify.resources.donations_none
import nl.diments.requestify.resources.donations_open_dashboard
import nl.diments.requestify.resources.donations_save_test
import nl.diments.requestify.resources.donations_streamelements_help
import nl.diments.requestify.resources.donations_test_failed
import nl.diments.requestify.resources.donations_testing
import nl.diments.requestify.resources.donations_title
import nl.diments.requestify.ui.common.formatShortDate
import nl.diments.requestify.ui.common.PlatformLogo
import nl.diments.requestify.ui.common.PlatformMark
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * Connections → Donations: the one donation platform (none, DonoLink or StreamElements), its key with a live test,
 * and the minimum per request. A DonoLink key shows who it belongs to and until when it works; StreamElements says
 * that tips made while Requestify is off are not picked up.
 */
@Composable
fun DonationsCard(vm: DonationsSetupViewModel) {
    val state by vm.state.collectAsState()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformLogo(PlatformMark.Donations, Modifier.padding(end = Spacing.m))
                Text(stringResource(Res.string.donations_title), style = MaterialTheme.typography.titleLargeEmphasized)
                Box(Modifier.weight(1f))
                if (state.kind != DonationSourceKind.None) {
                    ConnectionBadge(
                        when (state.health.status) {
                            DonationStatus.Connected -> LoginStatus.Connected
                            DonationStatus.KeyRefused ->
                                if (state.kind == DonationSourceKind.StreamElements) LoginStatus.TokenRefused else LoginStatus.KeyRefused
                            else -> LoginStatus.NotConnected
                        },
                    )
                }
            }
            DonationsSetupContent(state, vm)
        }
    }
}

/** What the donations setup asks (the platform, its key, the minimum), shared by the card and the welcome wizard. */
@Composable
internal fun DonationsSetupContent(state: DonationsCardState, vm: DonationsSetupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text(
            stringResource(Res.string.donations_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceChoice(state.kind, vm::choose)
        if (state.kind != DonationSourceKind.None) Setup(state, vm)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceChoice(selected: DonationSourceKind, onSelect: (DonationSourceKind) -> Unit) {
    val all = DonationSourceKind.entries
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        all.forEachIndexed { index, kind ->
            ToggleButton(
                checked = selected == kind,
                onCheckedChange = { onSelect(kind) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    all.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(
                    when (kind) {
                        DonationSourceKind.None -> stringResource(Res.string.donations_none)
                        DonationSourceKind.DonoLink -> "DonoLink"
                        DonationSourceKind.StreamElements -> "StreamElements"
                    },
                )
            }
        }
    }
}

@Composable
private fun Setup(state: DonationsCardState, vm: DonationsSetupViewModel) {
    val streamElements = state.kind == DonationSourceKind.StreamElements
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(
            stringResource(if (streamElements) Res.string.donations_streamelements_help else Res.string.donations_donolink_help),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = vm::openDashboard) { Text(stringResource(Res.string.donations_open_dashboard)) }

        var key by remember(state.kind) { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                label = { Text(stringResource(if (streamElements) Res.string.donations_jwt_label else Res.string.donations_key_label)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    vm.pasteKey(key)
                    key = ""
                },
                enabled = key.isNotBlank(),
            ) { Text(stringResource(Res.string.donations_save_test)) }
        }

        KeyStatus(state)

        MinimumField(state, vm)

        if (streamElements && !state.health.canCatchUp) {
            Text(stringResource(Res.string.donations_no_catch_up), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KeyStatus(state: DonationsCardState) {
    val scheme = MaterialTheme.colorScheme
    val streamElements = state.kind == DonationSourceKind.StreamElements
    val refused = state.test == DonationTest.Refused || state.health.status == DonationStatus.KeyRefused
    val (text, problem) = when {
        refused -> stringResource(
            if (streamElements) Res.string.donations_key_refused_streamelements else Res.string.donations_key_refused_donolink,
        ) to true
        state.test == DonationTest.Testing -> stringResource(Res.string.donations_testing) to false
        state.test is DonationTest.Failed ->
            stringResource(Res.string.donations_test_failed, if (streamElements) "StreamElements" else "DonoLink", state.test.message) to true
        state.test is DonationTest.Ok -> {
            val linked = stringResource(Res.string.donations_linked, state.test.name)
            val days = state.keyDaysLeft
            val expires = state.test.keyExpiresAtEpochMs
            when {
                days != null && days <= 7 -> "$linked · ${stringResource(Res.string.donations_key_expires_in, days)}" to true
                expires != null -> "$linked · ${stringResource(Res.string.donations_key_valid_until, formatShortDate(expires))}" to false
                else -> linked to false
            }
        }
        state.hasKey -> stringResource(Res.string.donations_key_saved) to false
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (problem) scheme.error else scheme.onSurfaceVariant)
}

@Composable
private fun MinimumField(state: DonationsCardState, vm: DonationsSetupViewModel) {
    val locale = Locale.getDefault()
    var text by remember(state.kind) { mutableStateOf(formatAmount(state.minimumCents, locale)) }
    var invalid by remember(state.kind) { mutableStateOf(false) }
    val streamElements = state.kind == DonationSourceKind.StreamElements
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val cents = parseCents(it, locale)
            invalid = cents == null || !vm.setMinimum(cents)
        },
        singleLine = true,
        isError = invalid,
        label = { Text(stringResource(Res.string.donations_minimum)) },
        supportingText = {
            Text(
                stringResource(
                    when {
                        invalid -> Res.string.donations_minimum_invalid
                        streamElements && state.currencyKnown -> Res.string.donations_minimum_hint_streamelements
                        streamElements -> Res.string.donations_minimum_hint_streamelements_unknown
                        else -> Res.string.donations_minimum_hint_donolink
                    },
                ),
            )
        },
        modifier = Modifier.width(240.dp),
    )
}

private fun formatAmount(cents: Long, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(BigDecimal.valueOf(cents, 2))

/** "1,50", "1.50" or "2" as cents; null when it is no amount. */
internal fun parseCents(text: String, locale: Locale): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val normalized = trimmed.replace(',', '.')
    normalized.toBigDecimalOrNull()?.let { return it.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong() }
    return try {
        BigDecimal(NumberFormat.getNumberInstance(locale).parse(trimmed).toString()).movePointRight(2).toLong()
    } catch (_: ParseException) {
        null
    }
}
