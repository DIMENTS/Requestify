package nl.diments.requestify.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import nl.diments.requestify.core.update.JustUpdated
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.updated_close
import nl.diments.requestify.resources.updated_title
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The "Bijgewerkt" card shows for [justUpdated] until the streamer closes it ([lastShownVersion] is then its version). */
fun showUpdatedCard(justUpdated: JustUpdated?, lastShownVersion: String?): Boolean =
    justUpdated != null && justUpdated.to != lastShownVersion

/**
 * A release's text (GitHub Markdown) as plain, readable text: heading marks and bold marks go, list items become
 * "• …", links keep their text. Anything else stays as written (an underscore in a name included).
 */
fun releaseNotesText(markdown: String): String = markdown.trim().lines().joinToString("\n") { line ->
    var text = line.trimEnd()
    text = text.replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
    text = text.replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")
    text = text.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    text.replace("**", "").replace("__", "")
}

/** On Home after an update, once: "Bijgewerkt naar X" with what is new, until the streamer closes it. */
@Composable
fun UpdatedCard(updated: JustUpdated, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.m, end = Spacing.s), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(Res.string.updated_title, updated.to), style = MaterialTheme.typography.titleMedium)
                val notes = releaseNotesText(updated.notes)
                if (notes.isNotEmpty()) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(Res.string.updated_close)) }
        }
    }
}
