package nl.diments.requestify.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemeSelectionTest {
    private val red = Color(0xFFE53935)
    private val green = Color(0xFF43A047)

    @Test fun albumModeUsesAlbumEverywhere() = assertEquals(SchemeSeeds(red, red), selectSeeds(ThemeMode.Album, red))
    @Test fun albumModeWithoutArtFallsBackToBrand() = assertEquals(SchemeSeeds(BrandSeed, BrandSeed), selectSeeds(ThemeMode.Album, null))
    @Test fun customModeThemesChromeOnly() = assertEquals(SchemeSeeds(green, red), selectSeeds(ThemeMode.Custom(green.toArgbForTest()), red))
    @Test fun customModeWithoutArt() = assertEquals(SchemeSeeds(green, green), selectSeeds(ThemeMode.Custom(green.toArgbForTest()), null))

    @Test fun resolveDark() {
        assertEquals(true, resolveDark(DarkMode.System, true))
        assertEquals(false, resolveDark(DarkMode.System, false))
        assertEquals(false, resolveDark(DarkMode.Light, true))
        assertEquals(true, resolveDark(DarkMode.Dark, false))
    }

    @Test fun sourceColoursAreDistinct() {
        val coloured = listOf(RequestSource.CHANNEL_POINTS, RequestSource.BITS, RequestSource.STREAMELEMENTS, RequestSource.DONOLINK)
        val containers = coloured.map { SourceColors.container(it) }
        assertEquals(4, containers.toSet().size)
        coloured.forEach { assertNotEquals(null, SourceColors.content(it)) }
    }

    private fun Color.toArgbForTest() = toArgb()
}
