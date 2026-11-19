package nl.diments.requestify.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.diments.requestify.ui.common.PlatformLogo
import nl.diments.requestify.ui.common.PlatformMark

const val WIZARD_CONFETTI_TAG = "wizard-confetti"
const val WIZARD_FLYER_TAG = "wizard-flyer"
const val WIZARD_SHAPES_TAG = "wizard-shapes"

/** How long a logo takes to fly from its card to the badges. */
internal const val FLIGHT_MS = 750

/** Soft colour shapes that slowly drift and change shape behind the card (the theme's colours, faint). */
@Composable
internal fun MovingShapes(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val time = rememberInfiniteTransition()
    val drift by time.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse))
    Box(modifier.fillMaxSize().testTag(WIZARD_SHAPES_TAG)) {
        Shape(scheme.primary.copy(alpha = 0.22f), x = -60f + 50f * drift, y = 60f + 40f * drift, corners = 40 + (30 * drift).toInt())
        Shape(scheme.tertiary.copy(alpha = 0.14f), x = 620f - 70f * drift, y = -40f + 60f * drift, corners = 70 - (35 * drift).toInt())
        Shape(scheme.secondary.copy(alpha = 0.12f), x = 420f + 40f * drift, y = 380f - 50f * drift, corners = 30 + (40 * drift).toInt())
    }
}

@Composable
private fun Shape(colour: Color, x: Float, y: Float, corners: Int) {
    Box(
        // Unbounded: the blur fades out past the shape's own box instead of being cut off at it.
        Modifier.offset(x.dp, y.dp).size(300.dp).blur(56.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .graphicsLayer { rotationZ = x / 3 }
            .background(colour, RoundedCornerShape(corners.coerceIn(30, 100))),
    )
}

/** One burst of confetti over the window, about 1.3 s, for [key] (a new key: a new burst). */
@Composable
internal fun ConfettiBurst(key: Any, modifier: Modifier = Modifier) {
    val colours = with(MaterialTheme.colorScheme) { listOf(primary, tertiary, secondary, Color(0xFF1ED760), Color(0xFF9146FF), Color(0xFFFFB38A), Color(0xFFFF8FC8)) }
    val pieces = remember(key) {
        val random = Random(key.hashCode())
        List(70) {
            val angle = random.nextDouble(0.0, 2 * Math.PI)
            val speed = 220f + random.nextFloat() * 380f
            Piece(cos(angle).toFloat() * speed, sin(angle).toFloat() * speed - 120f, random.nextFloat() * 720f - 360f, it % colours.size, it % 3 == 0)
        }
    }
    val progress = remember(key) { Animatable(0f) }
    var running by remember(key) { mutableStateOf(true) }
    LaunchedEffect(key) {
        progress.animateTo(1f, tween(1_300, easing = LinearOutSlowInEasing))
        running = false
    }
    if (!running) return
    Canvas(modifier.fillMaxSize().testTag(WIZARD_CONFETTI_TAG)) {
        val t = progress.value
        val origin = Offset(size.width / 2, size.height * 0.38f)
        pieces.forEach { piece ->
            val at = origin + Offset(piece.vx * t, piece.vy * t + 520f * t * t)
            val alpha = if (t < 0.75f) 1f else (1f - t) / 0.25f
            val colour = colours[piece.colour].copy(alpha = alpha)
            if (piece.round) {
                drawCircle(colour, radius = 5f, center = at)
            } else {
                drawContext.transform.rotate(piece.spin * t, at)
                drawRect(colour, topLeft = at - Offset(5f, 7f), size = Size(10f, 14f))
                drawContext.transform.rotate(-piece.spin * t, at)
            }
        }
    }
}

private class Piece(val vx: Float, val vy: Float, val spin: Float, val colour: Int, val round: Boolean)

/**
 * [mark]'s logo flying from [from] to [to] (pixels in the wizard's own space): it shrinks to badge size and spins
 * once, then [onLanded].
 */
@Composable
internal fun LogoFlight(mark: PlatformMark, from: Offset, to: Offset, onLanded: () -> Unit) {
    val position = remember(mark) { Animatable(from, Offset.VectorConverter) }
    val turn = remember(mark) { Animatable(0f) }
    LaunchedEffect(mark) {
        launch { turn.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing)) }
        position.animateTo(to, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
        onLanded()
    }
    Box(
        Modifier.offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
            .graphicsLayer {
                val scale = 1f - 0.45f * turn.value
                scaleX = scale
                scaleY = scale
                rotationZ = 360f * turn.value
            }
            .testTag(WIZARD_FLYER_TAG),
    ) {
        PlatformLogo(mark, size = 36.dp)
    }
}

/** A badge that just landed wiggles once. */
@Composable
internal fun Modifier.wiggleOnce(enabled: Boolean): Modifier {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (enabled) {
            angle.animateTo(0f, keyframes {
                durationMillis = 550
                -9f at 140
                7f at 330
                0f at 550
            })
        }
    }
    return graphicsLayer { rotationZ = angle.value }
}

/** A card coming in: it tilts in and springs straight. */
@Composable
internal fun Modifier.tiltIn(enabled: Boolean): Modifier {
    if (!enabled) return this
    val tilt = remember { Animatable(6f) }
    LaunchedEffect(Unit) { tilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    return graphicsLayer { rotationZ = tilt.value }
}

/** The card squashes and springs back when [key] (the platform that connected) appears. */
@Composable
internal fun Modifier.squashOn(key: Any?, enabled: Boolean): Modifier {
    val squash = remember { Animatable(0f) }
    LaunchedEffect(key) {
        if (enabled && key != null) {
            squash.snapTo(0f)
            squash.animateTo(0f, keyframes {
                durationMillis = 500
                1f at 150
                -0.6f at 320
                0f at 500
            })
        }
    }
    return graphicsLayer {
        scaleX = 1f + 0.04f * squash.value
        scaleY = 1f - 0.05f * squash.value
    }
}

/** The check pops in, overshooting, while its corners morph from round to the card's rounded square. */
@Composable
internal fun Modifier.popIn(enabled: Boolean): Modifier {
    if (!enabled) return this
    val scale = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        rotationZ = (1f - scale.value) * -25f
    }
}

/** The Twitch code with its letters popping in one by one. */
@Composable
internal fun PoppingCode(code: String) {
    Row {
        code.forEachIndexed { index, char ->
            val pop = remember(code) { Animatable(0f) }
            LaunchedEffect(code) {
                delay(250L + index * 70L)
                pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
            }
            Text(
                char.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer {
                    alpha = pop.value.coerceIn(0f, 1f)
                    scaleX = pop.value
                    scaleY = pop.value
                    translationY = (1f - pop.value) * 24f
                },
            )
        }
    }
}

/** A logo that keeps dancing (the Klaar card), each one [index] a beat later. */
@Composable
internal fun Modifier.dancing(index: Int, enabled: Boolean): Modifier {
    if (!enabled) return this
    val beat = rememberInfiniteTransition()
    val phase by beat.animateFloat(0f, 1f, infiniteRepeatable(tween(900, delayMillis = 0, easing = LinearEasing)))
    return graphicsLayer {
        val p = (phase + index * 0.17f) % 1f
        val swing = sin(p * 2 * Math.PI).toFloat()
        translationY = -7f * maxOf(0f, swing)
        rotationZ = 10f * swing
    }
}
