package nl.diments.requestify.ui.home

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.ui.theme.RequestifyTheme
import nl.diments.requestify.ui.theme.albumSeedFrom
import nl.diments.requestify.ui.theme.resolveDark
import nl.diments.requestify.ui.theme.selectSeeds

/** From this window width the panes sit side by side; below it Now Playing moves above the queue. */
private val WideLayoutMinWidth = 900.dp
private val NowPlayingWidth = 320.dp

/** Album art is scaled down to this many pixels per side before its seed colour is picked. */
private const val SEED_ART_SIZE_PX = 128

/**
 * The default Expressive typography gives the emphasized display and headline styles the same weight as the
 * baseline ones, so Home sets the weight that makes them stand out (the mockup's heavy titles).
 */
internal val EmphasizedWeight = FontWeight.Bold

/**
 * The Home screen: the queue on the left, Now Playing on the right. Its colours follow the album art (or the
 * custom colour, which leaves Now Playing on the album). [addFieldFocus] lets the app's "Add" button focus
 * the add field.
 */
@Composable
fun HomeScreen(vm: HomeViewModel, addFieldFocus: FocusRequester = remember { FocusRequester() }) {
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val seeds = selectSeeds(settings.themeMode, rememberAlbumSeed(state.nowPlaying?.track))
    val dark = resolveDark(settings.darkMode, isSystemInDarkTheme())
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = windowWidth >= WideLayoutMinWidth

    RequestifyTheme(seeds.app, dark) {
        val queuePane = @Composable { modifier: Modifier ->
            QueuePane(
                state = state,
                onAddInput = vm::onAddInput,
                onAddTrack = vm::onAddTrack,
                onRemove = vm::onRemove,
                onMove = vm::onMove,
                modifier = modifier,
                addFieldFocus = addFieldFocus,
            )
        }
        val nowPlayingPane = @Composable { modifier: Modifier ->
            RequestifyTheme(seeds.nowPlaying, dark) {
                NowPlayingPane(state.nowPlaying, vm::onTogglePause, vm::onSkip, modifier, compact = !wide)
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (wide) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    queuePane(Modifier.weight(1f).fillMaxHeight())
                    nowPlayingPane(Modifier.width(NowPlayingWidth).fillMaxHeight())
                }
            } else {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    nowPlayingPane(Modifier.fillMaxWidth())
                    queuePane(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The seed colour of [track]'s album art, picked off the main thread once per track. Until the new art is
 * ready the previous colour stays, so the scheme moves straight from album to album. Null without art.
 */
@Composable
private fun rememberAlbumSeed(track: Track?): Color? {
    val context = LocalPlatformContext.current
    val seed by produceState<Color?>(initialValue = null, track?.id) {
        val url = track?.albumArtUrl
        value = if (url == null) {
            null
        } else {
            val request = ImageRequest.Builder(context).data(url).size(SEED_ART_SIZE_PX).build()
            val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
            image?.let { withContext(Dispatchers.Default) { albumSeedFrom(it.toBitmap().asComposeImageBitmap()) } }
        }
    }
    return seed
}
