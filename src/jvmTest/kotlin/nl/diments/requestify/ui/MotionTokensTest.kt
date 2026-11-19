package nl.diments.requestify.ui

import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the app's motion on one set of tokens, so every screen moves alike and everything calms down with Windows'
 * animation effects off: each animation takes a spec from the theme's motion scheme (`MaterialTheme.motionScheme`)
 * or from `ui/theme`, and each `AnimatedContent` sizes with `motion.sizeTransform()`. Compose's own defaults (a
 * spring that ignores the scheme) and raw `tween`/`spring` calls belong in `ui/theme` only. The welcome wizard's
 * playful motion is its own, with its own reduced-motion path, and is left out.
 */
class MotionTokensTest {
    private val ui = Path.of("src/jvmMain/kotlin/nl/diments/requestify/ui")

    private val exempt = setOf("theme/", "welcome/WizardMotion.kt", "welcome/WelcomeWizard.kt")

    /** Calls whose arguments must name a spec. */
    private val needsSpec = Regex("""\b(fadeIn|fadeOut|scaleIn|scaleOut|expandVertically|shrinkVertically|expandHorizontally|shrinkHorizontally|slideIn|slideOut|slideInVertically|slideOutVertically|slideInHorizontally|slideOutHorizontally|animate\w*AsState|animateItem|animateContentSize|Crossfade)\(""")

    private val raw = Regex("""\b(tween|spring|keyframes|SizeTransform)\(""")

    private val animatedContent = Regex("""\bAnimatedContent\(""")

    private val animatedVisibility = Regex("""\bAnimatedVisibility\(""")

    /** The text between the parenthesis at [open] and its match. */
    private fun argumentsAt(source: String, open: Int): String {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        return source.substring(open + 1)
    }

    @Test
    fun everyAnimationUsesTheMotionScheme() {
        val problems = mutableListOf<String>()
        ui.walk().filter { it.toString().endsWith(".kt") }.forEach { file ->
            val name = file.relativeTo(ui).invariantSeparatorsPathString
            if (exempt.any { name.startsWith(it) }) return@forEach
            val source = file.readText().lines().filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            fun line(at: Int) = source.substring(0, at).count { it == '\n' } + 1
            needsSpec.findAll(source).forEach { match ->
                val args = argumentsAt(source, match.range.last)
                if (!args.contains("spec", ignoreCase = true)) problems += "$name:${line(match.range.first)} ${match.value} without a motion spec"
            }
            raw.findAll(source).forEach { match ->
                problems += "$name:${line(match.range.first)} ${match.value} outside ui/theme: use the motion scheme"
            }
            animatedContent.findAll(source).forEach { match ->
                if (!argumentsAt(source, match.range.last).contains("sizeTransform(")) {
                    problems += "$name:${line(match.range.first)} AnimatedContent without motion.sizeTransform()"
                }
            }
            animatedVisibility.findAll(source).forEach { match ->
                val args = argumentsAt(source, match.range.last)
                if (!args.contains("enter =") || !args.contains("exit =")) {
                    problems += "$name:${line(match.range.first)} AnimatedVisibility with Compose's own enter or exit"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private val button = Regex("""\b((?:Filled|FilledTonal|Outlined|Elevated|Text)?Button|(?:Filled|FilledTonal|Outlined)?IconButton|ToggleButton)\(""")

    /**
     * Settings → About (`settings/AboutCard.kt`) belongs to the plan-7 work being merged; its buttons get their shapes
     * once that is in.
     */
    private val buttonsLater = setOf("settings/AboutCard.kt")

    /** Every button squeezes into its pressed shape (Material 3 Expressive), so they all answer a press alike. */
    @Test
    fun everyButtonMorphsWhenPressed() {
        val problems = mutableListOf<String>()
        ui.walk().filter { it.toString().endsWith(".kt") }.forEach { file ->
            val name = file.relativeTo(ui).invariantSeparatorsPathString
            if (name in buttonsLater) return@forEach
            val source = file.readText().lines().filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            button.findAll(source).forEach { match ->
                if (!argumentsAt(source, match.range.last).contains("shapes =")) {
                    problems += "$name:${source.substring(0, match.range.first).count { it == '\n' } + 1} ${match.value} without shapes = …"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
