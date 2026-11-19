package nl.diments.requestify.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle

/**
 * The Expressive type scale with emphasized styles that really stand out. The default gives the emphasized
 * display and headline styles Medium weight, which the system font draws like Regular, so emphasized display
 * and headline styles are Bold and emphasized titles at least SemiBold. The regular styles are unchanged.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val ExpressiveTypography: Typography = Typography().run {
    copy(
        displayLargeEmphasized = displayLargeEmphasized.atLeast(FontWeight.Bold),
        displayMediumEmphasized = displayMediumEmphasized.atLeast(FontWeight.Bold),
        displaySmallEmphasized = displaySmallEmphasized.atLeast(FontWeight.Bold),
        headlineLargeEmphasized = headlineLargeEmphasized.atLeast(FontWeight.Bold),
        headlineMediumEmphasized = headlineMediumEmphasized.atLeast(FontWeight.Bold),
        headlineSmallEmphasized = headlineSmallEmphasized.atLeast(FontWeight.Bold),
        titleLargeEmphasized = titleLargeEmphasized.atLeast(FontWeight.SemiBold),
        titleMediumEmphasized = titleMediumEmphasized.atLeast(FontWeight.SemiBold),
        titleSmallEmphasized = titleSmallEmphasized.atLeast(FontWeight.SemiBold),
    )
}

private fun TextStyle.atLeast(weight: FontWeight) = copy(fontWeight = maxOf(fontWeight ?: FontWeight.Normal, weight))

/**
 * How the scheme is drawn from the seed. Vibrant keeps the secondary colours in the seed's own hue family (the
 * Expressive style rotates them, which put green controls on a violet scheme) and stays lively for dull album
 * art (Content turns it grey, TonalSpot is muted for every album).
 */
private val SchemeStyle = PaletteStyle.Vibrant

/** Expressive scheme derived from [seed]; colours animate smoothly whenever [seed] or [dark] changes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RequestifyTheme(seed: Color, dark: Boolean, content: @Composable () -> Unit) {
    DynamicMaterialExpressiveTheme(
        seedColor = seed,
        isDark = dark,
        style = SchemeStyle,
        animate = true,
        motionScheme = if (LocalReducedMotion.current) ReducedMotionScheme else MotionScheme.expressive(),
        typography = ExpressiveTypography,
        content = content,
    )
}

/**
 * The motion while Windows' animation effects are off ([LocalReducedMotion]): nothing slides, scales, grows or
 * bounces (every spatial spec snaps), while colours and opacity still fade calmly, so a change is still noticed.
 * Screens then just crossfade.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object ReducedMotionScheme : MotionScheme {
    private val calm = MotionScheme.standard()

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = calm.defaultEffectsSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = calm.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = calm.slowEffectsSpec()
}
