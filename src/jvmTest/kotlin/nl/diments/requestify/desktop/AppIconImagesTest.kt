package nl.diments.requestify.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import nl.diments.requestify.ui.common.AppIcons

class AppIconImagesTest {
    @Test
    fun theSourceDecodes() {
        val source = AppIconImages.sourceSize()
        assertEquals(1024 to 1024, source, "the user's icon, decoded from WebP")
    }

    @Test
    fun everySizeIsSquareAndTheRightSize() {
        for (size in listOf(16, 20, 24, 32, 48, 256)) {
            val image = AppIconImages.awt(size)
            assertEquals(size, image.width)
            assertEquals(size, image.height)
        }
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64, 256), AppIconImages.windowIcons().map { it.width })
    }

    @Test
    fun theWarningMarkChangesOnlyTheBottomRight() {
        val size = 32
        val plain = AppIconImages.awt(size)
        val marked = AppIconImages.awt(size, warning = true)
        var changedInCorner = 0
        for (x in 0 until size) for (y in 0 until size) {
            val same = plain.getRGB(x, y) == marked.getRGB(x, y)
            if (x < size / 2 || y < size / 2) {
                assertTrue(same, "pixel ($x,$y) outside the bottom-right quarter changed")
            } else if (!same) {
                changedInCorner++
            }
        }
        assertTrue(changedInCorner > size * size / 16, "the mark is visible ($changedInCorner pixels)")
    }

    @Test
    fun theGlyphIsTheSvgPath() {
        val glyph = AppIcons.RequestifyGlyph
        assertEquals(256f, glyph.viewportWidth)
        assertEquals(256f, glyph.viewportHeight)
        assertNotEquals(0, glyph.root.size, "it has the music-notes-plus path")
    }
}
