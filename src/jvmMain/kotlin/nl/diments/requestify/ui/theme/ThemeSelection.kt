package nl.diments.requestify.ui.theme

import androidx.compose.ui.graphics.Color
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.ThemeMode

/** Fallback seed, and the default custom colour. */
val BrandSeed = Color(0xFF7C5CFF)

/** [app] themes the chrome; [nowPlaying] themes the Now Playing panel. */
data class SchemeSeeds(val app: Color, val nowPlaying: Color)

fun selectSeeds(mode: ThemeMode, albumSeed: Color?): SchemeSeeds = when (mode) {
    ThemeMode.Album -> (albumSeed ?: BrandSeed).let { SchemeSeeds(it, it) }
    is ThemeMode.Custom -> {
        val custom = Color(mode.seedArgb)
        SchemeSeeds(app = custom, nowPlaying = albumSeed ?: custom)
    }
}

fun resolveDark(mode: DarkMode, systemDark: Boolean): Boolean = when (mode) {
    DarkMode.System -> systemDark
    DarkMode.Light -> false
    DarkMode.Dark -> true
}
