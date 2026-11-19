package nl.diments.requestify.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs

/**
 * Layout checks for whole screens: text that is cut off and controls that cannot be reached. Text that ends in "…"
 * is shortened on purpose and passes; text that is simply clipped (by its own room, a parent or the window) does
 * not. Whatever sits in a scrolling container can be scrolled to and is left to [reachable] checks.
 */
internal object LayoutChecks {
    /** One pixel of slack for rounding. */
    private const val SLACK = 1f

    /** Every problem with the texts on screen (the [window]'s bounds), as readable lines; empty when all is well. */
    fun SemanticsNodeInteractionsProvider.clippedTexts(window: Rect): List<String> {
        return onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.EditableText) == null } // fields scroll their own text
            .mapNotNull { node -> textProblem(node, window) }
    }

    private fun textProblem(node: SemanticsNode, window: Rect): String? {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: return null
        if (text.isBlank()) return null
        val layout = mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.firstOrNull()
        if (layout != null && layout.layoutInput.overflow == TextOverflow.Clip && layout.isCutOff()) {
            return "\"$text\" does not fit its room (${layout.size}, ${layout.lineCount} lines)"
        }
        if (node.inScrollingContainer()) return null
        val whole = node.unclippedBoundsInRoot()
        val shown = node.boundsInRoot
        if (whole.width <= 0f || whole.height <= 0f) return null
        if (!window.containsWithSlack(whole)) return "\"$text\" reaches outside the window: $whole in $window"
        if (abs(whole.width - shown.width) > SLACK || abs(whole.height - shown.height) > SLACK) {
            return "\"$text\" is cut off by a parent: shows $shown of $whole"
        }
        return null
    }

    /**
     * More lines than it may show, lines taller than its room, or a line wider than its room. (The paragraph's own
     * width is the room it was offered, not what it needs, so each line's width is compared.)
     */
    private fun TextLayoutResult.isCutOff(): Boolean =
        multiParagraph.didExceedMaxLines ||
            multiParagraph.height > size.height + SLACK ||
            (0 until lineCount).any { getLineRight(it) - getLineLeft(it) > size.width + SLACK }

    /** Whether [node] is inside something that scrolls (a lazy list, a scrolling column). */
    fun SemanticsNode.inScrollingContainer(): Boolean {
        var p = parent
        while (p != null) {
            if (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                p.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            ) {
                return true
            }
            p = p.parent
        }
        return false
    }

    /** The node's bounds in the root without its parents' clipping. */
    fun SemanticsNode.unclippedBoundsInRoot(): Rect {
        val coordinates = layoutInfo.coordinates
        var root = coordinates
        while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
        return root.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    fun Rect.containsWithSlack(other: Rect): Boolean =
        other.left >= left - SLACK && other.top >= top - SLACK && other.right <= right + SLACK && other.bottom <= bottom + SLACK

    /** Whether all of [node] shows: inside the window and not cut off by a parent. */
    fun wholeInWindow(node: SemanticsNode, window: Rect): Boolean {
        val whole = node.unclippedBoundsInRoot()
        val shown = node.boundsInRoot
        return window.containsWithSlack(whole) && abs(whole.width - shown.width) <= SLACK && abs(whole.height - shown.height) <= SLACK
    }
}
