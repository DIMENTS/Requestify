package nl.diments.requestify.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** With Windows' animation effects off, nothing slides, scales or bounces: only colours and opacity still fade. */
@OptIn(ExperimentalTestApi::class)
class ReducedMotionThemeTest {
    private class Specs(val spatial: List<FiniteAnimationSpec<Float>>, val effects: List<FiniteAnimationSpec<Float>>)

    private fun specs(reduced: Boolean): Specs {
        lateinit var specs: Specs
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    RequestifyTheme(BrandSeed, dark = true) {
                        val m = MaterialTheme.motionScheme
                        specs = Specs(
                            listOf(m.defaultSpatialSpec(), m.fastSpatialSpec(), m.slowSpatialSpec()),
                            listOf(m.defaultEffectsSpec(), m.fastEffectsSpec(), m.slowEffectsSpec()),
                        )
                    }
                }
            }
            waitForIdle()
        }
        return specs
    }

    @Test
    fun withAnimationEffectsOffMovementSnapsAndFadesStay() {
        val specs = specs(reduced = true)
        assertTrue(specs.spatial.all { it is SnapSpec }, "spatial: ${specs.spatial}")
        assertTrue(specs.effects.none { it is SnapSpec }, "effects: ${specs.effects}")
    }

    @Test
    fun withAnimationEffectsOnTheExpressiveMotionStays() {
        assertFalse(specs(reduced = false).spatial.any { it is SnapSpec })
    }
}
