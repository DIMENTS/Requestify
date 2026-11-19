package nl.diments.requestify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import nl.diments.requestify.core.update.AppVersion
import nl.diments.requestify.core.update.UpdateService
import nl.diments.requestify.core.update.UpdateState
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.about_check
import nl.diments.requestify.resources.about_check_failed
import nl.diments.requestify.resources.about_checking
import nl.diments.requestify.resources.about_credits
import nl.diments.requestify.resources.about_download_yourself
import nl.diments.requestify.resources.about_install_failed
import nl.diments.requestify.resources.about_licence
import nl.diments.requestify.resources.about_open_wizard
import nl.diments.requestify.resources.about_ready
import nl.diments.requestify.resources.about_ready_hint
import nl.diments.requestify.resources.about_title
import nl.diments.requestify.resources.about_up_to_date
import nl.diments.requestify.resources.about_update_now
import nl.diments.requestify.resources.about_update_when_offline
import nl.diments.requestify.resources.about_version
import nl.diments.requestify.resources.about_check_failed_note
import nl.diments.requestify.resources.about_waiting_offline
import nl.diments.requestify.resources.about_whats_new
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** What the buttons of [AboutCard] do. */
class AboutActions(
    val check: () -> Unit = {},
    val install: () -> Unit = {},
    val openRelease: () -> Unit = {},
    /** "Wat is er nieuw" for the version that runs (every state but Ready, which links its new version). */
    val openRunningRelease: () -> Unit = {},
    val openLicence: () -> Unit = {},
    val openNotice: () -> Unit = {},
    val openWizard: () -> Unit = {},
)

private const val REPO_URL = "https://github.com/DIMENTS/Requestify"

/** The release page of [version] (what is new in the version that runs); all releases for a local build or "dev". */
fun runningReleasePage(version: String): String =
    AppVersion.parse(version)?.takeUnless { it.isLocalBuild }?.let { "$REPO_URL/releases/tag/v$it" } ?: "$REPO_URL/releases"

/**
 * Settings → Over Requestify: the version, Requestify's own updates ([UpdateService]) and the links. [openUrl] opens a
 * page in the browser; [openWizard] (null: no button) starts the welcome wizard again.
 */
class AboutViewModel(
    private val updates: UpdateService,
    val version: String,
    private val openUrl: (String) -> Unit,
    private val openWizard: (() -> Unit)? = null,
) {
    val state: StateFlow<UpdateState> get() = updates.state
    val canInstallNow: StateFlow<Boolean> get() = updates.canInstallNow
    val checkFailedNote: StateFlow<Boolean> get() = updates.checkFailedNote
    val hasWizard: Boolean get() = openWizard != null

    val actions = AboutActions(
        check = updates::checkNow,
        install = updates::installNow,
        openRelease = { openUrl(releasePage()) },
        openRunningRelease = { openUrl(runningReleasePage(version)) },
        openLicence = { openUrl("$REPO_URL/blob/main/LICENSE") },
        openNotice = { openUrl("$REPO_URL/blob/main/NOTICE") },
        openWizard = { openWizard?.invoke() },
    )

    /** The page of the release the update is about, else all releases. */
    private fun releasePage(): String = when (val s = updates.state.value) {
        is UpdateState.Ready -> s.pageUrl
        is UpdateState.InstallFailed -> s.pageUrl
        else -> null
    }?.takeIf { it.isNotBlank() } ?: "$REPO_URL/releases"
}

@Composable
internal fun AboutSection(vm: AboutViewModel) {
    val state by vm.state.collectAsState()
    val canInstallNow by vm.canInstallNow.collectAsState()
    val checkFailedNote by vm.checkFailedNote.collectAsState()
    AboutCard(vm.version, state, canInstallNow, vm.hasWizard, vm.actions, checkFailedNote)
}

/**
 * Over Requestify. Updates stay quiet here: a check that failed on its own says nothing; one asked for by hand says so.
 * "Nu bijwerken" only while offline with requests closed ([canInstallNow]); the line under it says why otherwise.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutCard(
    version: String,
    state: UpdateState,
    canInstallNow: Boolean,
    hasWizard: Boolean,
    actions: AboutActions,
    checkFailedNote: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            Text(stringResource(Res.string.about_title), style = MaterialTheme.typography.titleLargeEmphasized)
            Text(stringResource(Res.string.about_version, version), style = MaterialTheme.typography.bodyLarge)
            when (state) {
                UpdateState.Off, UpdateState.Idle -> Unit
                is UpdateState.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.about_checking), style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.UpToDate -> Text(stringResource(Res.string.about_up_to_date), style = MaterialTheme.typography.bodyMedium)
                is UpdateState.CheckFailed -> if (state.manual) {
                    Text(stringResource(Res.string.about_check_failed), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                }
                is UpdateState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(Res.string.about_ready, state.version.toString()), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.about_ready_hint), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = actions.install, enabled = canInstallNow) { Text(stringResource(Res.string.about_update_now)) }
                        TextButton(onClick = actions.openRelease) { Text(stringResource(Res.string.about_whats_new)) }
                    }
                    if (!canInstallNow) {
                        Text(stringResource(Res.string.about_update_when_offline), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                is UpdateState.InstallFailed -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.about_install_failed), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                    TextButton(onClick = actions.openRelease) { Text(stringResource(Res.string.about_download_yourself)) }
                }
                is UpdateState.WaitingForOffline ->
                    Text(stringResource(Res.string.about_waiting_offline, state.version.toString()), style = MaterialTheme.typography.bodyMedium)
            }
            // A check by hand that failed while an update is ready (or failed): the update stays, with this line.
            if (checkFailedNote && (state is UpdateState.Ready || state is UpdateState.InstallFailed)) {
                Text(stringResource(Res.string.about_check_failed_note), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            if (state != UpdateState.Off) {
                OutlinedButton(onClick = actions.check, enabled = state !is UpdateState.Checking) { Text(stringResource(Res.string.about_check)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextButton(onClick = actions.openLicence) { Text(stringResource(Res.string.about_licence)) }
                if (state != UpdateState.Off && state !is UpdateState.Ready) {
                    TextButton(onClick = actions.openRunningRelease) { Text(stringResource(Res.string.about_whats_new)) }
                }
                TextButton(onClick = actions.openNotice) { Text(stringResource(Res.string.about_credits)) }
                if (hasWizard) TextButton(onClick = actions.openWizard) { Text(stringResource(Res.string.about_open_wizard)) }
            }
        }
    }
}
