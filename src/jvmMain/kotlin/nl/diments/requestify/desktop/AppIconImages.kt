package nl.diments.requestify.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * The app icon for the tray, the window and the taskbar, from one file: `icon/requestify-icon.webp` (the user's icon,
 * with its background). Replacing the icon is replacing that file. Skia decodes the WebP (Java's ImageIO cannot), and
 * every size is drawn from it with mipmapped sampling so small sizes stay clean. A [warning] mark is drawn in code
 * over whatever icon is in use.
 */
object AppIconImages {
    private const val SOURCE = "/icon/requestify-icon.webp"

    /** The sizes Windows asks a window for, from the title bar to the Alt+Tab view. */
    private val WINDOW_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 256)

    private const val WARNING_FILL = 0xFFFFB300.toInt() // amber
    private const val WARNING_RING = 0xFF2B1D00.toInt()

    private val source: Image by lazy {
        val bytes = AppIconImages::class.java.getResourceAsStream(SOURCE)?.use { it.readBytes() }
            ?: error("The app icon $SOURCE is missing")
        Image.makeFromEncoded(bytes)
    }

    private val cache = ConcurrentHashMap<Pair<Int, Boolean>, BufferedImage>()

    /** The icon's own size, in pixels. */
    fun sourceSize(): Pair<Int, Int> = source.width to source.height

    /** The icon at [sizePx] × [sizePx], with the warning mark in its bottom-right corner when [warning]. */
    fun awt(sizePx: Int, warning: Boolean = false): BufferedImage = cache.getOrPut(sizePx to warning) { draw(sizePx, warning) }

    /** Every size a window can show. */
    fun windowIcons(): List<BufferedImage> = WINDOW_SIZES.map { awt(it) }

    private fun draw(size: Int, warning: Boolean): BufferedImage {
        val surface = Surface.makeRasterN32Premul(size, size)
        val canvas = surface.canvas
        val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
        canvas.drawImageRect(source, Rect.makeWH(source.width.toFloat(), source.height.toFloat()), Rect.makeWH(size.toFloat(), size.toFloat()), sampling, null, true)
        if (warning) {
            // A dot with a dark ring, within the bottom-right quarter: about 45 % of the icon wide.
            val radius = size * 0.2f
            val ring = maxOf(1f, size * 0.05f)
            val centre = size - radius - ring / 2f - size * 0.01f
            canvas.drawCircle(centre, centre, radius, Paint().apply { color = WARNING_FILL; isAntiAlias = true })
            canvas.drawCircle(
                centre,
                centre,
                radius,
                Paint().apply {
                    color = WARNING_RING
                    mode = PaintMode.STROKE
                    strokeWidth = ring
                    isAntiAlias = true
                },
            )
        }
        return surface.makeImageSnapshot().toComposeImageBitmap().toAwtImage()
    }
}
