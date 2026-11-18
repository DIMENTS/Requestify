package nl.diments.requestify.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The three things a streamer connects, each with its own logo, the same on Connections and in the welcome wizard. */
enum class PlatformMark { Spotify, Twitch, Donations }

/** Spotify's and Twitch's own colours; donations take the theme's tertiary colour. */
private val SpotifyGreen = Color(0xFF1ED760)
private val TwitchPurple = Color(0xFF9146FF)

/** [mark]'s Phosphor logo in its colour. */
@Composable
fun PlatformLogo(mark: PlatformMark, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val (icon, tint) = when (mark) {
        PlatformMark.Spotify -> AppIcons.SpotifyLogo to SpotifyGreen
        PlatformMark.Twitch -> AppIcons.TwitchLogo to TwitchPurple
        PlatformMark.Donations -> AppIcons.HandHeart to MaterialTheme.colorScheme.tertiary
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
