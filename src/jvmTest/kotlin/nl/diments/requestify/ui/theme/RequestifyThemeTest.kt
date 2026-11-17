package nl.diments.requestify.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RequestifyThemeTest {
    private val albumLikeSeeds = listOf(BrandSeed, Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047))

    /** Dull, greyish album art: just colourful enough to be a seed. */
    private val dullSeed = Color(0xFF6E7B8B)

    private fun schemeFor(seed: Color, dark: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        runComposeUiTest {
            setContent { RequestifyTheme(seed, dark) { scheme = MaterialTheme.colorScheme } }
            waitForIdle()
        }
        return scheme
    }

    private fun Color.hct() = Hct.fromInt(toArgb())

    private fun hueDistance(a: Color, b: Color): Double {
        val d = abs(a.hct().hue - b.hct().hue) % 360
        return if (d > 180) 360 - d else d
    }

    @Test
    fun theSecondaryColoursStayInTheSeedsHueFamily() {
        for (seed in albumLikeSeeds + dullSeed) {
            for (dark in listOf(true, false)) {
                val scheme = schemeFor(seed, dark)
                val secondaries = listOf("secondary" to scheme.secondary, "secondaryContainer" to scheme.secondaryContainer)
                for ((role, colour) in secondaries) {
                    val distance = hueDistance(scheme.primary, colour)
                    assertTrue(distance <= 30, "$role is $distance° from primary for seed $seed (dark=$dark)")
                }
            }
        }
    }

    @Test
    fun dullAlbumArtStillGivesALivelyScheme() {
        val primaryChroma = schemeFor(dullSeed, dark = true).primary.hct().chroma
        assertTrue(primaryChroma >= 40, "primary chroma $primaryChroma for dull art")
    }
}
