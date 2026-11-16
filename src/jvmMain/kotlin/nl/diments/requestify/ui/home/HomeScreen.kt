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
import nl.diments.requestify.ui.theme.resolveDark
import nl.diments.requestify.ui.theme.selectSeeds

/** From this width of Home the panes sit side by side; below it Now Playing moves above the queue. */
private val WideLayoutMinWidth = 900.dp
private val NowPlayingWidth = 320.dp

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
                        onMove = vm::onMove,
                        modifier = modifier,
                        addFieldFocus = addFieldFocus,
                    )
                }
            }
            val nowPlayingPane = @Composable { modifier: Modifier ->
                RequestifyTheme(nowPlayingSeed, dark) {
                    NowPlayingPane(state.nowPlaying, vm::onTogglePause, vm::onSkip, modifier, compact = !wide)
                }
            }
            if (wide) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    queuePane(state, Modifier.weight(1f).fillMaxHeight())
                    nowPlayingPane(Modifier.width(NowPlayingWidth).fillMaxHeight())
                }
            } else {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    nowPlayingPane(Modifier.fillMaxWidth())
                    queuePane(state, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
