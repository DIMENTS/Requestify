package nl.diments.requestify.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.materialkolor.DynamicMaterialExpressiveTheme

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

/** Expressive scheme derived from [seed]; colours animate smoothly whenever [seed] or [dark] changes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RequestifyTheme(seed: Color, dark: Boolean, content: @Composable () -> Unit) {
    DynamicMaterialExpressiveTheme(
        seedColor = seed,
        isDark = dark,
        animate = true,
        motionScheme = MotionScheme.expressive(),
        typography = ExpressiveTypography,
        content = content,
    )
}
