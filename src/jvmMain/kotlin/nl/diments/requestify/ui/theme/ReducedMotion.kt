package nl.diments.requestify.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Windows' "Animation effects" are off: nothing moves by itself and celebrations are skipped; screens just crossfade.
 * Read once at start ([nl.diments.requestify.desktop.windowsAnimationsOff]).
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
