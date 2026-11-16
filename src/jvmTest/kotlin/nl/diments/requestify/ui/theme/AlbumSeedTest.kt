package nl.diments.requestify.ui.theme

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumSeedTest {
    private fun solid(color: Color): ImageBitmap {
        val bmp = ImageBitmap(64, 64)
        Canvas(bmp).drawRect(Rect(0f, 0f, 64f, 64f), Paint().apply { this.color = color })
        return bmp
    }

    @Test fun redImageGivesRedSeed() {
        val seed = assertNotNull(albumSeedFrom(solid(Color(0xFFE53935))))
        val argb = seed.toArgb()
        val hue = java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)[0] * 360f
        val d = abs(hue - 2f).let { minOf(it, 360f - it) }
        assertTrue(d <= 20f, "hue $hue too far from red")
    }

    @Test fun greyImageGivesNull() = assertNull(albumSeedFrom(solid(Color(0xFF808080))))
}
