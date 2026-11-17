package nl.diments.requestify.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.diments.requestify.ui.theme.Spacing

/** A page's content is at most this wide; on a wider window the rest stays empty on the right. */
private val PageMaxWidth = 760.dp

/**
 * A page beside the rail (Connections, Settings): its headline, an optional [subtitle] under it, then [content]
 * [Spacing.l] apart. It starts where Home starts, [Spacing.xl] from the rail and the title bar, left-aligned
 * (never centred, so switching pages doesn't move the content sideways), and scrolls when taller than the window. A page
 * with a lazy list passes `scrolls = false`: it then fills the window and gives its list the rest with `weight(1f)`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PageScaffold(title: String, subtitle: String? = null, scrolls: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Column(
            Modifier.padding(Spacing.xl).widthIn(max = PageMaxWidth).fillMaxWidth().then(if (scrolls) Modifier else Modifier.fillMaxHeight()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}
