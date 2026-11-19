package nl.diments.requestify.ui.theme

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlin.test.Test
import kotlin.test.assertEquals

class MotionTest {
    private val ms = 1_000_000L

    @Test
    fun aDelayedSpecHoldsItsStartUntilTheDelayHasPassed() {
        val spec = delayed(80, tween<Float>(100, easing = LinearEasing)).vectorize(Float.VectorConverter)
        val from = Float.VectorConverter.convertToVector(0f)
        val to = Float.VectorConverter.convertToVector(1f)
        val still = Float.VectorConverter.convertToVector(0f)
        fun at(t: Long) = spec.getValueFromNanos(t * ms, from, to, still).value

        assertEquals(0f, at(0))
        assertEquals(0f, at(79))
        assertEquals(0.5f, at(130), 0.01f)
        assertEquals(1f, at(180))
        assertEquals(180 * ms, spec.getDurationNanos(from, to, still))
    }
}
