package nl.diments.requestify.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nl.diments.requestify.core.model.RequestSource

/** Fixed per-source colours; never follow the theme. CHAT and MANUAL are neutral (theme-derived). */
object SourceColors {
    fun container(s: RequestSource): Color? = when (s) {
        RequestSource.CHANNEL_POINTS -> Color(0xFF3B2A63)
        RequestSource.BITS -> Color(0xFF2C3266)
        RequestSource.STREAMELEMENTS -> Color(0xFF1D4636)
        RequestSource.DONOLINK -> Color(0xFF5B2F20)
        RequestSource.CHAT, RequestSource.MANUAL -> null
    }

    fun content(s: RequestSource): Color? = when (s) {
        RequestSource.CHANNEL_POINTS -> Color(0xFFDCCBFF)
        RequestSource.BITS -> Color(0xFFC9D0FF)
        RequestSource.STREAMELEMENTS -> Color(0xFFA8F0D0)
        RequestSource.DONOLINK -> Color(0xFFFFCDB6)
        RequestSource.CHAT, RequestSource.MANUAL -> null
    }
}

@Composable
fun SourceChip(source: RequestSource, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = SourceColors.container(source) ?: scheme.surfaceContainerHighest,
        contentColor = SourceColors.content(source) ?: scheme.onSurfaceVariant,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
