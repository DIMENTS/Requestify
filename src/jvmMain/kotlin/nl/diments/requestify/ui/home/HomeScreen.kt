package nl.diments.requestify.ui.home

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nl.diments.requestify.ui.theme.RequestifyTheme
import nl.diments.requestify.ui.theme.Spacing
import nl.diments.requestify.ui.theme.resolveDark
import nl.diments.requestify.ui.theme.selectSeeds

/** From this width of Home the panes sit side by side; below it Now Playing moves above the queue. */
internal val WideLayoutMinWidth = 900.dp

/**
 * Beside the queue, Now Playing takes this share of Home's width, within [NowPlayingMinWidth]..[NowPlayingMaxWidth]:
 * at the default window size its art and details then about fill the card's height, and the queue keeps its room.
 */
private const val NOW_PLAYING_SHARE = 0.36f
private val NowPlayingMinWidth = 320.dp
private val NowPlayingMaxWidth = 400.dp

/**
 * On a tall window Now Playing may grow past [NowPlayingMaxWidth], up to this share of Home's height and at most
 * [NowPlayingTallMaxWidth], so its art fills the taller card instead of floating in it.
 */
private const val NOW_PLAYING_HEIGHT_SHARE = 0.5f
private val NowPlayingTallMaxWidth = 520.dp

/**
 * The widest the queue gets: on a maximized window its rows stay readable (title and duration within one glance)
 * and Now Playing stays right beside it. Like the pages, Home then keeps its start edge and leaves the rest empty.
 */
internal val QueueMaxWidth = 960.dp

/**
 * The Home screen: the queue on the left, Now Playing on the right. It uses the app theme of its caller; Now
 * Playing gets the colours of [albumSeed] (the current album art's seed colour, null without art) even when
 * the app uses a custom colour. [addFieldFocus] lets the app's "Add" button focus the add field.
 */
@Composable
fun HomeScreen(vm: HomeViewModel, albumSeed: Color?, addFieldFocus: FocusRequester = remember { FocusRequester() }) {
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val nowPlayingSeed = selectSeeds(settings.themeMode, albumSeed).nowPlaying
    val dark = resolveDark(settings.darkMode, isSystemInDarkTheme())

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Measured on the space Home gets (the window minus the app's navigation rail), not the window.
        BoxWithConstraints {
            val wide = maxWidth >= WideLayoutMinWidth
            // Movable, so the tab, the typed text and the list position survive a switch between layouts.
            val queuePane = remember(vm, addFieldFocus) {
                movableContentOf { paneState: HomeUiState, modifier: Modifier ->
                    QueuePane(
                        state = paneState,
                        onAddInput = vm::onAddInput,
                        onAddSubmit = vm::onAddSubmit,
                        onAddTrack = vm::onAddTrack,
                        onRemove = vm::onRemove,
                        onBlock = { request, target -> vm.onBlockFromRow(request, target) },
                        onAddAnyway = vm::onAddAnyway,
                        onDismiss = vm::onDismiss,
                        onMove = vm::onMove,
                        modifier = modifier,
                        addFieldFocus = addFieldFocus,
                        compact = !wide,
                    )
                }
            }
            val nowPlayingPane = @Composable { modifier: Modifier ->
                RequestifyTheme(nowPlayingSeed, dark) {
                    NowPlayingPane(
                        state.nowPlaying,
                        vm::onPrevious,
                        vm::onTogglePause,
                        vm::onSkip,
                        vm::onBlockCurrentAndSkip,
                        modifier,
                        compact = !wide,
                    )
                }
            }
            if (wide) {
                // Whole dp, so the panes and the gap between them land on whole pixels.
                val nowPlayingMax = (maxHeight * NOW_PLAYING_HEIGHT_SHARE).coerceIn(NowPlayingMaxWidth, NowPlayingTallMaxWidth)
                val nowPlayingWidth = (maxWidth * NOW_PLAYING_SHARE).coerceIn(NowPlayingMinWidth, nowPlayingMax)
                    .value.toInt().dp
                Row(Modifier.padding(Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    queuePane(state, Modifier.weight(1f, fill = false).widthIn(max = QueueMaxWidth).fillMaxWidth().fillMaxHeight())
                    nowPlayingPane(Modifier.width(nowPlayingWidth).fillMaxHeight())
                }
            } else {
                Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    nowPlayingPane(Modifier.fillMaxWidth())
                    queuePane(state, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
