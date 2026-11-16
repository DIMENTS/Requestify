package nl.diments.requestify.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.themeColorOrNull

/** Best colours below this HCT chroma are effectively grey and give no useful seed. */
const val MIN_SEED_CHROMA = 15.0

/** Pure function on pixels: safe off the UI thread. Null when the art has no usable colour. */
fun albumSeedFrom(image: ImageBitmap): Color? {
    val best = image.themeColorOrNull(filter = true, maxColors = 128) ?: return null
    return best.takeIf { Hct.fromInt(it.toArgb()).chroma >= MIN_SEED_CHROMA }
}

