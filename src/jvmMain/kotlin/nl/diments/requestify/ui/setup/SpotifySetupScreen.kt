package nl.diments.requestify.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.spotify.auth.SPOTIFY_REDIRECT_PORT
import nl.diments.requestify.core.spotify.auth.SPOTIFY_REDIRECT_URI
import nl.diments.requestify.core.spotify.auth.SpotifyAuthStatus
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.nav_connections
import nl.diments.requestify.resources.save_failed
import nl.diments.requestify.resources.setup_browser_failed
import nl.diments.requestify.resources.setup_cancel_login
import nl.diments.requestify.resources.setup_check_after_login
import nl.diments.requestify.resources.setup_client_id_invalid
import nl.diments.requestify.resources.setup_client_id_label
import nl.diments.requestify.resources.setup_copied
import nl.diments.requestify.resources.setup_copy
import nl.diments.requestify.resources.setup_device_hint
import nl.diments.requestify.resources.setup_device_ok
import nl.diments.requestify.resources.setup_intro
import nl.diments.requestify.resources.setup_logged_in
import nl.diments.requestify.resources.setup_logging_in
import nl.diments.requestify.resources.setup_login
import nl.diments.requestify.resources.setup_login_expired
import nl.diments.requestify.resources.setup_login_failed
import nl.diments.requestify.resources.setup_login_port_in_use
import nl.diments.requestify.resources.setup_login_timed_out
import nl.diments.requestify.resources.setup_logout
import nl.diments.requestify.resources.setup_open_dashboard
import nl.diments.requestify.resources.setup_premium_checking
import nl.diments.requestify.resources.setup_premium_missing
import nl.diments.requestify.resources.setup_premium_ok
import nl.diments.requestify.resources.setup_premium_unknown
import nl.diments.requestify.resources.setup_spotify
import nl.diments.requestify.resources.setup_status_connected
import nl.diments.requestify.resources.setup_status_login_expired
import nl.diments.requestify.resources.setup_status_not_connected
import nl.diments.requestify.resources.setup_step_check_title
import nl.diments.requestify.resources.setup_step_client_id_body
import nl.diments.requestify.resources.setup_step_client_id_title
import nl.diments.requestify.resources.setup_step_dashboard_body
import nl.diments.requestify.resources.setup_step_dashboard_title
import nl.diments.requestify.resources.setup_step_login_body
import nl.diments.requestify.resources.setup_step_login_title
import nl.diments.requestify.resources.setup_step_redirect_body
import nl.diments.requestify.resources.setup_step_redirect_title
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.PageScaffold
import nl.diments.requestify.ui.common.SpotifyStatusChip
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
private const val COPIED_CONFIRMATION_MS = 2_000L

/** The connection badge is as tall as an M3 chip. */
private val BadgeHeight = 32.dp
private val StepBadgeSize = 36.dp

/** Connections: the guided Spotify setup, one numbered step at a time, with a live result at the end. */
@Composable
fun SpotifySetupScreen(vm: SpotifySetupViewModel) {
    val state by vm.state.collectAsState()
    PageScaffold(stringResource(Res.string.nav_connections), subtitle = stringResource(Res.string.setup_intro)) {
        SpotifyCard(state, vm)
    }
}

@Composable
private fun SpotifyCard(state: SpotifySetupState, vm: SpotifySetupViewModel) {
    val loggedIn = state.auth == SpotifyAuthStatus.LoggedIn
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.setup_spotify), style = MaterialTheme.typography.titleLargeEmphasized)
                Spacer(Modifier.weight(1f))
                ConnectionBadge(state.auth)
            }
            Step(1, Res.string.setup_step_dashboard_title, Res.string.setup_step_dashboard_body, done = loggedIn) {
                val uriHandler = LocalUriHandler.current
                FilledTonalButton(
                    onClick = { runCatching { uriHandler.openUri(DASHBOARD_URL) } },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    ButtonIcon(AppIcons.OpenInNew)
                    Text(stringResource(Res.string.setup_open_dashboard))
                }
            }
            Step(2, Res.string.setup_step_redirect_title, Res.string.setup_step_redirect_body, done = loggedIn) {
                CopyableText(SPOTIFY_REDIRECT_URI)
            }
            Step(3, Res.string.setup_step_client_id_title, Res.string.setup_step_client_id_body, done = state.clientIdValid) {
                // Locked while logging in too: a login started with one ID must keep refreshing with that ID.
                ClientIdField(state.clientId, enabled = !loggedIn && !state.loggingIn, onChange = vm::onClientIdChange)
                if (state.saveFailure == SaveFailure.ClientId) ErrorText(stringResource(Res.string.save_failed))
            }
            Step(4, Res.string.setup_step_login_title, Res.string.setup_step_login_body, done = loggedIn) {
                LoginControls(state, vm)
            }
            Step(5, Res.string.setup_step_check_title, body = null, done = loggedIn && state.premium == PremiumCheck.Premium) {
                CheckResult(state)
            }
        }
    }
}

@Composable
private fun ConnectionBadge(auth: SpotifyAuthStatus) {
    val scheme = MaterialTheme.colorScheme
    val (text, container, content) = when (auth) {
        SpotifyAuthStatus.LoggedIn ->
            Triple(Res.string.setup_status_connected, scheme.primaryContainer, scheme.onPrimaryContainer)
        SpotifyAuthStatus.LoggedOut ->
            Triple(Res.string.setup_status_not_connected, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        is SpotifyAuthStatus.LoginNeeded ->
            Triple(Res.string.setup_status_login_expired, scheme.errorContainer, scheme.onErrorContainer)
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Box(Modifier.heightIn(min = BadgeHeight).padding(horizontal = Spacing.m), contentAlignment = Alignment.Center) {
            Text(stringResource(text), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A numbered step; its number turns into a check on an Expressive cookie shape when the step is [done]. */
@Composable
private fun Step(
    number: Int,
    title: StringResource,
    body: StringResource?,
    done: Boolean,
    content: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        StepBadge(number, done)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMediumEmphasized)
            if (body != null) {
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = done,
        transitionSpec = {
            (scaleIn(motion.defaultSpatialSpec(), initialScale = 0.5f) + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(fadeOut(motion.fastEffectsSpec()))
        },
    ) { isDone ->
        if (isDone) {
            Box(
                Modifier.size(StepBadgeSize).background(scheme.primary, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        } else {
            Box(
                Modifier.size(StepBadgeSize).background(scheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = MaterialTheme.typography.titleMediumEmphasized, color = scheme.onSecondaryContainer)
            }
        }
    }
}

/** [text] in a monospace box with a copy button that confirms briefly with "Copied". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun CopyableText(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            // As tall as the button beside it.
            Box(
                Modifier.heightIn(min = ButtonDefaults.MinHeight).padding(horizontal = Spacing.m),
                contentAlignment = Alignment.CenterStart,
            ) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    // The system clipboard throws while another program holds it; then there is simply no "Copied".
                    if (runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }.isSuccess) {
                        copied = true
                        delay(COPIED_CONFIRMATION_MS)
                        copied = false
                    }
                }
            },
            shapes = ButtonDefaults.shapes(),
        ) {
            AnimatedContent(
                targetState = copied,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f))
                        .togetherWith(fadeOut(motion.fastEffectsSpec()))
                        .using(SizeTransform(clip = false))
                },
            ) { isCopied ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ButtonIcon(if (isCopied) AppIcons.Check else AppIcons.ContentCopy)
                    Text(stringResource(if (isCopied) Res.string.setup_copied else Res.string.setup_copy))
                }
            }
        }
    }
}

/** Keeps the typed text locally, so typing never waits for the view model's state to come back. */
@Composable
private fun ClientIdField(saved: String, enabled: Boolean, onChange: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved) }
    val valid = isValidSpotifyClientId(text)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(Res.string.setup_client_id_label)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        isError = text.isNotBlank() && !valid,
        supportingText = if (text.isNotBlank() && !valid) {
            { Text(stringResource(Res.string.setup_client_id_invalid)) }
        } else {
            null
        },
        trailingIcon = if (valid) {
            { Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private enum class LoginPhase { LoggedOut, LoggingIn, LoggedIn }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoginControls(state: SpotifySetupState, vm: SpotifySetupViewModel) {
    val motion = MaterialTheme.motionScheme
    val phase = when {
        state.auth == SpotifyAuthStatus.LoggedIn -> LoginPhase.LoggedIn
        state.loggingIn -> LoginPhase.LoggingIn
        else -> LoginPhase.LoggedOut
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (state.auth is SpotifyAuthStatus.LoginNeeded && phase == LoginPhase.LoggedOut) {
            ErrorText(stringResource(Res.string.setup_login_expired))
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
        ) { shown ->
            when (shown) {
                LoginPhase.LoggedOut -> Button(
                    onClick = vm::onLogin,
                    enabled = state.clientIdValid,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.setup_login))
                }
                LoginPhase.LoggingIn -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text(stringResource(Res.string.setup_logging_in), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::onCancelLogin, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.setup_cancel_login))
                    }
                }
                LoginPhase.LoggedIn -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.setup_logged_in), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = vm::onLogout, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.setup_logout))
                    }
                }
            }
        }
        val url = state.pendingLoginUrl
        AnimatedVisibility(
            visible = url != null && state.loggingIn,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(stringResource(Res.string.setup_browser_failed), style = MaterialTheme.typography.bodyMedium)
                CopyableText(url.orEmpty())
            }
        }
        state.loginError?.let { error ->
            ErrorText(
                when (error) {
                    LoginError.TimedOut -> stringResource(Res.string.setup_login_timed_out)
                    LoginError.PortInUse -> stringResource(Res.string.setup_login_port_in_use, SPOTIFY_REDIRECT_PORT)
                    LoginError.Failed -> stringResource(Res.string.setup_login_failed)
                },
            )
        }
        if (state.saveFailure == SaveFailure.Logout) ErrorText(stringResource(Res.string.save_failed))
    }
}

/** Premium and the device hint, once logged in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckResult(state: SpotifySetupState) {
    val scheme = MaterialTheme.colorScheme
    if (state.auth != SpotifyAuthStatus.LoggedIn) {
        Text(
            stringResource(Res.string.setup_check_after_login),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            when (state.premium) {
                null -> {
                    LoadingIndicator(Modifier.size(24.dp))
                    Text(stringResource(Res.string.setup_premium_checking), style = MaterialTheme.typography.bodyLarge)
                }
                PremiumCheck.Premium -> ResultLine(AppIcons.CheckCircle, scheme.primary, Res.string.setup_premium_ok)
                PremiumCheck.NotPremium -> ResultLine(AppIcons.Warning, scheme.error, Res.string.setup_premium_missing)
                PremiumCheck.Unknown -> ResultLine(AppIcons.Info, scheme.onSurfaceVariant, Res.string.setup_premium_unknown)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            SpotifyStatusChip(state.health)
            val hint = when (state.health) {
                SpotifyHealth.Ok -> Res.string.setup_device_ok
                SpotifyHealth.NothingPlaying, SpotifyHealth.NoActiveDevice -> Res.string.setup_device_hint
                else -> null
            }
            if (hint != null) {
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ResultLine(icon: ImageVector, tint: Color, text: StringResource) {
    Icon(icon, contentDescription = null, tint = tint)
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.padding(end = ButtonDefaults.IconSpacing).size(ButtonDefaults.IconSize),
    )
}
