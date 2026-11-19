package nl.diments.requestify.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.diments.requestify.core.settings.ChatPermission
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.rules_decrease
import nl.diments.requestify.resources.rules_increase
import nl.diments.requestify.resources.save_failed
import nl.diments.requestify.resources.setup_cancel_login
import nl.diments.requestify.resources.setup_copied
import nl.diments.requestify.resources.setup_copy
import nl.diments.requestify.resources.setup_logout
import nl.diments.requestify.resources.twitch_bits_hint
import nl.diments.requestify.resources.twitch_bits_not_affiliate
import nl.diments.requestify.resources.twitch_bits_title
import nl.diments.requestify.resources.twitch_chat_hint
import nl.diments.requestify.resources.twitch_chat_title
import nl.diments.requestify.resources.twitch_code_expired
import nl.diments.requestify.resources.twitch_code_hint
import nl.diments.requestify.resources.twitch_code_title
import nl.diments.requestify.resources.twitch_command_blacklist
import nl.diments.requestify.resources.twitch_command_blank
import nl.diments.requestify.resources.twitch_command_duplicate
import nl.diments.requestify.resources.twitch_command_whitespace
import nl.diments.requestify.resources.twitch_command_queue
import nl.diments.requestify.resources.twitch_command_remove
import nl.diments.requestify.resources.twitch_command_request
import nl.diments.requestify.resources.twitch_command_skip
import nl.diments.requestify.resources.twitch_command_song
import nl.diments.requestify.resources.twitch_command_wrong_song
import nl.diments.requestify.resources.twitch_commands_title
import nl.diments.requestify.resources.twitch_intro
import nl.diments.requestify.resources.twitch_login
import nl.diments.requestify.resources.twitch_login_again_as
import nl.diments.requestify.resources.twitch_login_expired
import nl.diments.requestify.resources.twitch_login_failed
import nl.diments.requestify.resources.twitch_open_browser
import nl.diments.requestify.resources.twitch_permission_everyone
import nl.diments.requestify.resources.twitch_permission_followers
import nl.diments.requestify.resources.twitch_permission_label
import nl.diments.requestify.resources.twitch_permission_moderators
import nl.diments.requestify.resources.twitch_permission_subscribers
import nl.diments.requestify.resources.twitch_permission_vips
import nl.diments.requestify.resources.twitch_requesting_code
import nl.diments.requestify.resources.twitch_section_bits
import nl.diments.requestify.resources.twitch_section_chat
import nl.diments.requestify.resources.twitch_tag_affiliate
import nl.diments.requestify.resources.twitch_tag_partner
import nl.diments.requestify.resources.twitch_title
import nl.diments.requestify.resources.twitch_unreachable
import nl.diments.requestify.resources.twitch_waiting
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.rules.RowDivider
import nl.diments.requestify.ui.rules.RowMinHeight
import nl.diments.requestify.ui.rules.RuleText
import nl.diments.requestify.ui.rules.Stepper
import nl.diments.requestify.ui.common.PlatformLogo
import nl.diments.requestify.ui.common.PlatformMark
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val COPIED_CONFIRMATION_MS = 2_000L

/** The bits minimum steps to the next multiple of this (and 1 at the bottom). */
internal const val BITS_STEP = 50

private val AvatarSize = 40.dp

private enum class Phase { LoggedOut, LoggingIn, LoggedIn }

/**
 * The Twitch card on Connections: the device-code login, then the account on top and, per source, one row with its
 * main setting and a switch (Chat, Bits), with the command names folded away in a row of their own.
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TwitchCard(vm: TwitchSetupViewModel) {
    val login by vm.login.collectAsState()
    val status by vm.authStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    val account = settings.twitch.account
    val phase = when {
        login is TwitchLoginState.Requesting || login is TwitchLoginState.ShowingCode -> Phase.LoggingIn
        // An account that is set up keeps its card even when the login is dead, so it can still be logged out.
        account != null -> Phase.LoggedIn
        else -> Phase.LoggedOut
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = Spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformLogo(PlatformMark.Twitch, Modifier.padding(end = Spacing.m))
                Text(stringResource(Res.string.twitch_title), style = MaterialTheme.typography.titleLargeEmphasized)
                Box(Modifier.weight(1f))
                ConnectionBadge(
                    when {
                        // With an account set up, any login that is not working needs a new one (as in the status chip).
                        status is TwitchAuthStatus.LoginNeeded || (account != null && status != TwitchAuthStatus.LoggedIn) ->
                            LoginStatus.LoginExpired
                        phase == Phase.LoggedIn -> LoginStatus.Connected
                        else -> LoginStatus.NotConnected
                    },
                )
            }
            TwitchSetupContent(vm)
        }
    }
}

/** How the device code is drawn: large, monospaced, selectable. The wizard draws it its own way. */
@Composable
internal fun TwitchCode(code: String) {
    SelectionContainer {
        Text(
            code,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = Spacing.xs),
        )
    }
}

/**
 * The Twitch setup under its header: log in (with the device code, drawn by [code]), and once logged in the sources
 * and the Channel Points reward. Shared by the Connections card and the welcome wizard.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TwitchSetupContent(vm: TwitchSetupViewModel, code: @Composable (String) -> Unit = { TwitchCode(it) }) {
    val login by vm.login.collectAsState()
    val status by vm.authStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    val saveFailed by vm.saveFailed.collectAsState()
    val account = settings.twitch.account
    val motion = MaterialTheme.motionScheme
    val phase = when {
        login is TwitchLoginState.Requesting || login is TwitchLoginState.ShowingCode -> Phase.LoggingIn
        account != null -> Phase.LoggedIn
        else -> Phase.LoggedOut
    }
    Column {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
        ) { shown ->
            when (shown) {
                Phase.LoggedOut -> LoggedOutContent(login, status, vm)
                Phase.LoggingIn -> CodePanel(login, vm, code)
                Phase.LoggedIn -> if (account != null) LoggedInContent(account, login, status != TwitchAuthStatus.LoggedIn, vm) else Box(Modifier)
            }
        }
        if (saveFailed) {
            ErrorText(stringResource(Res.string.save_failed), Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s))
        }
    }
}

/** Not logged in, or the login is dead: why, and the button. */
@Composable
private fun LoggedOutContent(login: TwitchLoginState, status: TwitchAuthStatus, vm: TwitchSetupViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(top = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (status is TwitchAuthStatus.LoginNeeded) {
            ErrorText(stringResource(Res.string.twitch_login_expired))
        } else {
            Text(
                stringResource(Res.string.twitch_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LoginErrorText(login)
        Button(onClick = vm::onLogin, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.twitch_login))
        }
    }
}

/** Why the last login did not work, in the card's own words; nothing when it did not fail. */
@Composable
private fun LoginErrorText(login: TwitchLoginState) {
    when (login) {
        TwitchLoginState.Expired -> ErrorText(stringResource(Res.string.twitch_code_expired))
        TwitchLoginState.Failed -> ErrorText(stringResource(Res.string.twitch_login_failed))
        TwitchLoginState.Unreachable -> ErrorText(stringResource(Res.string.twitch_unreachable))
        else -> Unit
    }
}

/** The login is dead: it says so, with a one-click way back in as the same account. The settings below stay usable. */
@Composable
private fun DeadLoginBanner(account: TwitchAccount, login: TwitchLoginState, vm: TwitchSetupViewModel) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(Res.string.twitch_login_expired), style = MaterialTheme.typography.bodyMedium)
            LoginErrorText(login)
            Button(onClick = vm::onLogin, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.twitch_login_again_as, account.displayName))
            }
        }
    }
}

/** The device code: big, with Copy and Open browser, and a loading shape until the user confirms at Twitch. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CodePanel(login: TwitchLoginState, vm: TwitchSetupViewModel, codeText: @Composable (String) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(top = Spacing.m),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            val code = (login as? TwitchLoginState.ShowingCode)?.code
            if (code == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text(stringResource(Res.string.twitch_requesting_code), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.weight(1f))
                    TextButton(onClick = vm::onCancelLogin, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.setup_cancel_login))
                    }
                }
                return@Column
            }
            Text(stringResource(Res.string.twitch_code_title), style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                stringResource(Res.string.twitch_code_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            codeText(code.userCode)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                CopyCodeButton(onCopy = vm::onCopyCode)
                Button(onClick = vm::onOpenBrowser, shapes = ButtonDefaults.shapes()) {
                    ButtonIcon(AppIcons.OpenInNew)
                    Text(stringResource(Res.string.twitch_open_browser))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(40.dp))
                Text(
                    stringResource(Res.string.twitch_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f))
                TextButton(onClick = vm::onCancelLogin, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.setup_cancel_login))
                }
            }
        }
    }
}

/** Copy, which says "Copied" for a moment after it copied. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CopyCodeButton(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_CONFIRMATION_MS)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            onCopy()
            copied = true
        },
        shapes = ButtonDefaults.shapes(),
    ) {
        ButtonIcon(if (copied) AppIcons.Check else AppIcons.ContentCopy)
        Text(stringResource(if (copied) Res.string.setup_copied else Res.string.setup_copy))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoggedInContent(account: TwitchAccount, login: TwitchLoginState, loginDead: Boolean, vm: TwitchSetupViewModel) {
    val settings by vm.settings.collectAsState()
    val twitch = settings.twitch
    val eligibleForBits = account.broadcasterType == "affiliate" || account.broadcasterType == "partner"
    Column(Modifier.padding(top = Spacing.m)) {
        if (loginDead) DeadLoginBanner(account, login, vm)
        AccountRow(account, onLogout = vm::onLogout)

        SectionHeader(stringResource(Res.string.twitch_section_chat), AppIcons.Chat)
        SourceRow(
            title = stringResource(Res.string.twitch_chat_title, twitch.commands.request),
            hint = stringResource(Res.string.twitch_chat_hint),
            checked = twitch.chatEnabled,
            onCheckedChange = vm::onChatEnabled,
        ) {
            PermissionMenu(twitch.chatPermission, onPick = vm::onChatPermission)
        }
        RowDivider()

        ChannelPointsSection(vm, eligible = eligibleForBits)
        RowDivider()

        SectionHeader(stringResource(Res.string.twitch_section_bits), AppIcons.Diamond)
        val bitsTitle = stringResource(Res.string.twitch_bits_title)
        SourceRow(
            title = bitsTitle,
            hint = stringResource(if (eligibleForBits) Res.string.twitch_bits_hint else Res.string.twitch_bits_not_affiliate),
            checked = twitch.bitsEnabled && eligibleForBits,
            enabled = eligibleForBits,
            onCheckedChange = vm::onBitsEnabled,
        ) {
            Stepper(
                value = twitch.bitsMinimum.toString(),
                canDecrease = eligibleForBits && twitch.bitsMinimum > BITS_MINIMUM_MIN,
                canIncrease = eligibleForBits && twitch.bitsMinimum < BITS_MINIMUM_MAX,
                onDecrease = { vm.onBitsMinimum(stepDown(twitch.bitsMinimum)) },
                onIncrease = { vm.onBitsMinimum(stepUp(twitch.bitsMinimum)) },
                decreaseLabel = stringResource(Res.string.rules_decrease, bitsTitle),
                increaseLabel = stringResource(Res.string.rules_increase, bitsTitle),
            )
        }
        RowDivider()

        CommandsRow(vm)
    }
}

internal fun stepUp(value: Int) = ((value / BITS_STEP) + 1) * BITS_STEP

internal fun stepDown(value: Int) = if (value % BITS_STEP == 0) value - BITS_STEP else (value / BITS_STEP) * BITS_STEP

/** The logged-in account: its initial on an Expressive cookie shape, its name, its Affiliate/Partner tag and Log out. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountRow(account: TwitchAccount, onLogout: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(AvatarSize).background(scheme.primaryContainer, MaterialShapes.Cookie12Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = scheme.onPrimaryContainer,
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                account.displayName,
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val tag = when (account.broadcasterType) {
                "affiliate" -> Res.string.twitch_tag_affiliate
                "partner" -> Res.string.twitch_tag_partner
                else -> null
            }
            if (tag != null) {
                Surface(shape = CircleShape, color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer) {
                    Text(
                        stringResource(tag),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                }
            }
        }
        TextButton(onClick = onLogout, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.setup_logout))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.m, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
    }
}

/** One source: its main setting's name and hint, a [control] for the setting, and its on/off switch. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SourceRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = RowMinHeight).padding(horizontal = Spacing.xl, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuleText(title, hint, Modifier.weight(1f))
        control()
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            thumbContent = if (checked) {
                { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** Who may use the chat command: a pill with the current choice that opens an Expressive menu. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionMenu(selected: ChatPermission, onPick: (ChatPermission) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.twitch_permission_label)
    Box {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Row(
                modifier = Modifier.heightIn(min = ButtonDefaults.MinHeight).padding(start = Spacing.l, end = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(selected.label()), style = MaterialTheme.typography.labelLarge)
                Icon(AppIcons.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                val all = ChatPermission.entries
                all.forEachIndexed { index, permission ->
                    DropdownMenuItem(
                        text = { Text(stringResource(permission.label())) },
                        onClick = {
                            expanded = false
                            onPick(permission)
                        },
                        shape = menuItemShape(index, all.size),
                        leadingIcon = {
                            if (permission == selected) Icon(AppIcons.Check, contentDescription = null) else Box(Modifier.size(24.dp))
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun menuItemShape(index: Int, count: Int): Shape = when {
    count == 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}

private fun ChatPermission.label(): StringResource = when (this) {
    ChatPermission.Everyone -> Res.string.twitch_permission_everyone
    ChatPermission.Followers -> Res.string.twitch_permission_followers
    ChatPermission.Subscribers -> Res.string.twitch_permission_subscribers
    ChatPermission.Vips -> Res.string.twitch_permission_vips
    ChatPermission.Moderators -> Res.string.twitch_permission_moderators
}

private fun CommandField.label(): StringResource = when (this) {
    CommandField.Request -> Res.string.twitch_command_request
    CommandField.Song -> Res.string.twitch_command_song
    CommandField.Queue -> Res.string.twitch_command_queue
    CommandField.WrongSong -> Res.string.twitch_command_wrong_song
    CommandField.Skip -> Res.string.twitch_command_skip
    CommandField.Remove -> Res.string.twitch_command_remove
    CommandField.Blacklist -> Res.string.twitch_command_blacklist
}

/** The command names, folded away: the row shows them in one line, and opens to seven fields. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommandsRow(vm: TwitchSetupViewModel) {
    val settings by vm.settings.collectAsState()
    val typed by vm.commands.collectAsState()
    val errors by vm.commandErrors.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    val saved = settings.twitch.commands
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = Spacing.xl, vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuleText(
                stringResource(Res.string.twitch_commands_title),
                CommandField.entries.joinToString(" · ") { "!${it.get(saved)}" },
                Modifier.weight(1f),
            )
            val turn by animateFloatAsState(if (expanded) 180f else 0f, motion.defaultSpatialSpec())
            Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.xl).padding(top = Spacing.xs, bottom = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                CommandField.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { field ->
                            CommandFieldInput(
                                field = field,
                                value = field.get(typed),
                                error = errors[field],
                                onChange = { text -> vm.onCommandName { names -> field.set(names, text) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandFieldInput(
    field: CommandField,
    value: String,
    error: CommandError?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(field.label())) },
        prefix = { Text("!") },
        isError = error != null,
        supportingText = error?.let {
            {
                val text = when (it) {
                    CommandError.Blank -> Res.string.twitch_command_blank
                    CommandError.Duplicate -> Res.string.twitch_command_duplicate
                    CommandError.Whitespace -> Res.string.twitch_command_whitespace
                }
                Text(stringResource(text))
            }
        },
        modifier = modifier,
    )
}
