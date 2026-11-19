package nl.diments.requestify.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import nl.diments.requestify.core.settings.TwitchAccount
import nl.diments.requestify.core.twitch.auth.TwitchAuthStatus
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.save_failed
import nl.diments.requestify.resources.twitch_bot_check_again
import nl.diments.requestify.resources.twitch_bot_connect
import nl.diments.requestify.resources.twitch_bot_disconnect
import nl.diments.requestify.resources.twitch_bot_intro
import nl.diments.requestify.resources.twitch_bot_intro_compact
import nl.diments.requestify.resources.twitch_bot_last_drop
import nl.diments.requestify.resources.twitch_bot_login_again
import nl.diments.requestify.resources.twitch_bot_login_expired
import nl.diments.requestify.resources.twitch_bot_mod_tip
import nl.diments.requestify.resources.twitch_bot_moderator
import nl.diments.requestify.resources.twitch_bot_own_account
import nl.diments.requestify.resources.twitch_bot_private_window
import nl.diments.requestify.resources.twitch_bot_replies_from
import nl.diments.requestify.resources.twitch_bot_replies_from_you
import nl.diments.requestify.resources.twitch_bot_title
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * "Bot-account (optioneel)" in the Twitch content (Connections and, in fewer words when [compact], the welcome
 * wizard): connect a bot with the device code (drawn by [code]), and once connected who the replies come from, whether
 * the bot is a moderator (with the `/mod` tip), the last message Twitch held back, and a dead login's banner.
 */
@Composable
internal fun TwitchBotSection(
    vm: TwitchBotSetupViewModel,
    compact: Boolean = false,
    code: @Composable (String) -> Unit = { TwitchCode(it) },
) {
    val login by vm.login.collectAsState()
    val account by vm.account.collectAsState()
    val status by vm.authStatus.collectAsState()
    val saveFailed by vm.saveFailed.collectAsState()
    SectionHeader(stringResource(Res.string.twitch_bot_title), AppIcons.Chat)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        val bot = account
        when {
            login is TwitchLoginState.Requesting || login is TwitchLoginState.ShowingCode -> CodePanel(
                login = login,
                onCopyCode = vm::onCopyCode,
                onOpenBrowser = vm::onOpenBrowser,
                onCancel = vm::onCancel,
                codeText = code,
                modifier = Modifier.fillMaxWidth(),
                note = {
                    Text(
                        stringResource(Res.string.twitch_bot_private_window),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                onCopyLink = vm::onCopyLink,
            )
            bot != null -> Connected(bot, login, status != TwitchAuthStatus.LoggedIn, vm)
            else -> NotConnected(login, compact, vm)
        }
        if (saveFailed) ErrorText(stringResource(Res.string.save_failed))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotConnected(login: TwitchLoginState, compact: Boolean, vm: TwitchBotSetupViewModel) {
    Text(
        stringResource(if (compact) Res.string.twitch_bot_intro_compact else Res.string.twitch_bot_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (login == TwitchLoginState.OwnAccount) ErrorText(stringResource(Res.string.twitch_bot_own_account)) else LoginErrorText(login)
    Button(onClick = vm::onConnect, shapes = ButtonDefaults.shapes()) {
        Text(stringResource(Res.string.twitch_bot_connect))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Connected(bot: TwitchAccount, login: TwitchLoginState, loginDead: Boolean, vm: TwitchBotSetupViewModel) {
    val moderator by vm.moderator.collectAsState()
    val lastDrop by vm.lastDrop.collectAsState()
    val health by vm.health.collectAsState()
    // The bot cannot send right now: replies come from the streamer, and the line says so.
    val repliesFromYou = loginDead || health.blockedReason != null
    if (loginDead) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(Res.string.twitch_bot_login_expired), style = MaterialTheme.typography.bodyMedium)
                if (login == TwitchLoginState.OwnAccount) ErrorText(stringResource(Res.string.twitch_bot_own_account)) else LoginErrorText(login)
                Button(onClick = vm::onConnect, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.twitch_bot_login_again))
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (repliesFromYou) Res.string.twitch_bot_replies_from_you else Res.string.twitch_bot_replies_from, bot.displayName),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::onDisconnect, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.twitch_bot_disconnect))
        }
    }
    when (moderator) {
        true -> Text(
            stringResource(Res.string.twitch_bot_moderator),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // Not a moderator, or not known yet (the check did not answer): the tip, with "Opnieuw kijken".
        false, null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(Res.string.twitch_bot_mod_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer {
                    Text("/mod ${bot.login}", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                }
                Box(Modifier.weight(1f))
                CopyCodeButton(onCopy = vm::onCopyModCommand)
                TextButton(onClick = vm::onCheckModerator, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.twitch_bot_check_again))
                }
            }
        }
    }
    lastDrop?.let { ErrorText(stringResource(Res.string.twitch_bot_last_drop, it)) }
}
