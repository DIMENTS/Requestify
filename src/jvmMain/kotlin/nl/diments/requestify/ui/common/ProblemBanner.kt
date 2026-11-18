package nl.diments.requestify.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.diments.requestify.ui.theme.Spacing

/**
 * A problem the streamer should know about (e.g. a file that cannot be saved), on the error container. [compact] (a
 * narrow window, where the queue needs the height) shows one line; a click shows all of it, and another click folds it
 * again.
 */
@Composable
fun ProblemBanner(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val oneLine = compact && !expanded
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = Spacing.l, vertical = if (oneLine) Spacing.s else Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = if (oneLine) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
