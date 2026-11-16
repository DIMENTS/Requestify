package nl.diments.requestify.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.DynamicMaterialExpressiveTheme

/** Expressive scheme derived from [seed]; colours animate smoothly whenever [seed] or [dark] changes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RequestifyTheme(seed: Color, dark: Boolean, content: @Composable () -> Unit) {
    DynamicMaterialExpressiveTheme(
        seedColor = seed,
        isDark = dark,
        animate = true,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
