package nl.diments.requestify.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.diments.requestify.ui.common.ConnectionChip
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import nl.diments.requestify.ui.common.AppIcons
import org.jetbrains.compose.resources.stringResource

/** The title bar's height; as the window's custom title bar, this is also the height Windows drags by. */
val TitleBarHeight = 40.dp

/** Between the bar's content and its edges, or the caption buttons. */
private val EdgePadding = 16.dp

/**
 * The app's title bar, full width above the rail and the screens: the app's mark and name, the one connection status,
 * and the "Requests open/closed" toggle ([requestsSwitch]). It has no background of its own, so it
 * is one plane with the app below it, in the same animated colours.
 *
 * As the window's custom title bar, [captionInset] keeps the native caption buttons free and [onHitTest] gets
 * the hit test of every pointer event ([captionHitTest]): the status chip and the toggle are the bar's client
 * areas, the rest of the bar moves the window. Without them (the window keeps its own title bar) it is a header
 * at the top of the content.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TitleBar(
    problems: List<ConnectionProblem>,
    modifier: Modifier = Modifier,
    captionInset: Dp = 0.dp,
    onHitTest: ((client: Boolean) -> Unit)? = null,
    onConnectionsClick: () -> Unit = {},
    requestsSwitch: @Composable () -> Unit = {},
    /** Neither Spotify nor Twitch is set up: the chip says so instead of "All connected". */
    nothingConnected: Boolean = false,
) {
    val clientAreas = remember { TitleBarClientAreas() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .then(if (onHitTest != null) Modifier.captionHitTest(clientAreas, onHitTest) else Modifier)
            .padding(start = EdgePadding, end = EdgePadding + captionInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark()
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(Res.string.app_name),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(16.dp))
        // The status gets the room the toggle leaves (the toggle is measured first): in a narrow window a long status
        // ends in "…" instead of pushing the toggle under the caption buttons. The room around the chip moves the window.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            ConnectionChip(problems, onConnectionsClick, Modifier.titleBarClientArea(clientAreas), nothingConnected)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.titleBarClientArea(clientAreas)) { requestsSwitch() }
    }
}

/** The app's mark: its "music notes +" glyph on a small primary-container square; both follow the album colours. */
@Composable
private fun AppMark() {
    Surface(
        modifier = Modifier.size(24.dp),
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(AppIcons.RequestifyGlyph, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
