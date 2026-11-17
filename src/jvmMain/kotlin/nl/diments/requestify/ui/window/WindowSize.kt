package nl.diments.requestify.ui.window

import androidx.compose.ui.unit.DpSize
import java.awt.Dimension
import kotlin.math.ceil

/**
 * [size] as AWT user-space units, which is what `Window.minimumSize` takes: dp become device pixels with Compose's
 * [density], and device pixels become AWT units with the window's [awtScale]
 * (`graphicsConfiguration.defaultTransform.scaleX`). Rounded up, so the minimum is never a pixel short.
 */
fun awtMinimumSize(size: DpSize, density: Float, awtScale: Double): Dimension {
    fun awt(dp: Float): Int = ceil(dp.toDouble() * density / awtScale - ROUNDING_SLACK).toInt()
    return Dimension(awt(size.width.value), awt(size.height.value))
}

/** Float noise in the scale factors (1.1f is not 1.1) must not round an exact result up. */
private const val ROUNDING_SLACK = 1e-6
