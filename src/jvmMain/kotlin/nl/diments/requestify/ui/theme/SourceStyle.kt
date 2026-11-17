package nl.diments.requestify.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
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

/** The room around a [SourceChip]'s label. */
private val ChipPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

@Composable
fun SourceChip(source: RequestSource, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = SourceColors.container(source) ?: scheme.surfaceContainerHighest,
        contentColor = SourceColors.content(source) ?: scheme.onSurfaceVariant,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(ChipPadding))
    }
}

/**
 * How tall a [SourceChip] is at the current density and text size: one line of its label and the room above and
 * below it, in whole pixels as it is laid out.
 */
@Composable
fun sourceChipHeight(): Dp {
    val label = rememberTextMeasurer().measure("H", MaterialTheme.typography.labelMedium).size.height
    return with(LocalDensity.current) {
        (label + ChipPadding.calculateTopPadding().roundToPx() + ChipPadding.calculateBottomPadding().roundToPx()).toDp()
    }
}
