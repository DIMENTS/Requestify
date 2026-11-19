@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package nl.diments.requestify.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.IntSize

/**
 * How long new content waits before it fades in, in a [fadeThrough]: by then the old content (fading out on the fast
 * effects spec) is down to a few percent, so the two never show on top of each other.
 */
const val FADE_THROUGH_DELAY_MS = 70

/**
 * Material's fade through, on this scheme's specs, for content that is replaced by other content (a screen, a tab, a
 * label): the old fades out fast, the new fades in once the old has nearly gone, growing from [initialScale] when
 * given (on the spatial spec, so it does not grow with animation effects off).
 */
fun MotionScheme.fadeThrough(initialScale: Float? = null): ContentTransform {
    val grow = initialScale?.let { scaleIn(defaultSpatialSpec(), initialScale = it) } ?: EnterTransition.None
    return (fadeIn(delayed(FADE_THROUGH_DELAY_MS, defaultEffectsSpec())) + grow) togetherWith fadeOut(fastEffectsSpec())
}

/**
 * An [androidx.compose.animation.AnimatedContent]'s size change on this scheme's spatial spec, so it moves like
 * everything else (and snaps with animation effects off). [clip] as in [SizeTransform].
 */
fun MotionScheme.sizeTransform(clip: Boolean = false): SizeTransform {
    val spec = defaultSpatialSpec<IntSize>()
    return SizeTransform(clip) { _, _ -> spec }
}

/** The album art's pop on a new song: up from a little smaller, with a little overshoot. */
val ArtPopSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)

/** [spec], started [delayMillis] later; until then the value stays where it starts. */
fun <T> delayed(delayMillis: Int, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = DelayedSpec(delayMillis, spec)

private class DelayedSpec<T>(private val delayMillis: Int, private val spec: FiniteAnimationSpec<T>) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        DelayedVectorizedSpec(delayMillis * 1_000_000L, spec.vectorize(converter))

    override fun equals(other: Any?) = other is DelayedSpec<*> && other.delayMillis == delayMillis && other.spec == spec

    override fun hashCode() = 31 * delayMillis + spec.hashCode()
}

private class DelayedVectorizedSpec<V : AnimationVector>(
    private val delayNanos: Long,
    private val spec: VectorizedFiniteAnimationSpec<V>,
) : VectorizedFiniteAnimationSpec<V> {
    private fun inner(playTimeNanos: Long) = (playTimeNanos - delayNanos).coerceAtLeast(0)

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        spec.getValueFromNanos(inner(playTimeNanos), initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        spec.getVelocityFromNanos(inner(playTimeNanos), initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        delayNanos + spec.getDurationNanos(initialValue, targetValue, initialVelocity)
}
