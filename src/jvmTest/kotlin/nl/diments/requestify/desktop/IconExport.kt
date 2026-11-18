package nl.diments.requestify.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes the installer's icon (`packaging/requestify.ico`) from the app icon, so the installer, the Start menu and
 * Settings → Apps show the same icon as the tray and the window. Run once with `REQUESTIFY_ICON_OUT=packaging`
 * (from the project folder); the result is committed. The format check always runs.
 */
class IconExport {
    @Test
    fun theIcoHasAHeaderAndOneEntryPerSize() {
        val bytes = icoBytes(ICO_SIZES.map { AppIconImages.awt(it) })
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.getShort(0).toInt(), "reserved")
        assertEquals(1, buffer.getShort(2).toInt(), "an icon, not a cursor")
        assertEquals(ICO_SIZES.size, buffer.getShort(4).toInt())
        ICO_SIZES.forEachIndexed { i, size ->
            val entry = 6 + i * 16
            assertEquals(if (size == 256) 0 else size, bytes[entry].toInt() and 0xFF, "width of entry $i (0 means 256)")
            val offset = buffer.getInt(entry + 12)
            assertEquals(0x89.toByte(), bytes[offset], "entry $i is a PNG")
        }
    }

    @Test
    fun export() {
        val out = System.getenv("REQUESTIFY_ICON_OUT")?.takeIf { it.isNotBlank() } ?: return
        val dir = Path.of(out).createDirectories()
        Files.write(dir.resolve("requestify.ico"), icoBytes(ICO_SIZES.map { AppIconImages.awt(it) }))
    }

    private companion object {
        val ICO_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 256)

        /** An ICO file with one PNG-compressed image per entry (Windows Vista and later read these). */
        fun icoBytes(images: List<BufferedImage>): ByteArray {
            val pngs = images.map { image -> ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() }
            val header = 6 + 16 * images.size
            val buffer = ByteBuffer.allocate(header + pngs.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(0).putShort(1).putShort(images.size.toShort())
            var offset = header
            images.zip(pngs).forEach { (image, png) ->
                buffer.put((image.width % 256).toByte()).put((image.height % 256).toByte())
                buffer.put(0).put(0) // no palette, reserved
                buffer.putShort(1).putShort(32) // colour planes, bits per pixel
                buffer.putInt(png.size).putInt(offset)
                offset += png.size
            }
            pngs.forEach { buffer.put(it) }
            return buffer.array()
        }
    }
}
