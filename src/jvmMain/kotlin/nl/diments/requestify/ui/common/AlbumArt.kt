package nl.diments.requestify.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Album art that cross-fades in over a music-note placeholder. Decorative (the title is always shown next to
 * it). The caller sizes and clips it through [modifier].
 */
@Composable
fun AlbumArt(url: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(
            AppIcons.MusicNote,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.4f),
        )
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
