package nl.diments.requestify.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame

/** Who draws the window's title bar. */
enum class TitleBarMode {
    /** The app's [TitleBar], as the JetBrains Runtime's custom title bar; Windows keeps the caption buttons. */
    Custom,

    /** Windows' own title bar; the app's [TitleBar] is a header at the top of the content. */
    Native,
}

/**
 * Custom when the JetBrains Runtime's API is available and supports window decorations, otherwise native. A
 * probe that throws (another JDK, a runtime whose API doesn't match) also means native: the app always starts.
 */
fun titleBarMode(jbrAvailable: () -> Boolean, decorationsSupported: () -> Boolean): TitleBarMode {
    val custom = runCatching { jbrAvailable() && decorationsSupported() }.getOrDefault(false)
    return if (custom) TitleBarMode.Custom else TitleBarMode.Native
}

/** The custom title bar in place: the room its native caption buttons take, and where hit tests go. */
class WindowCaption(val buttonsInset: Dp, val hitTest: (client: Boolean) -> Unit)

/**
 * Makes [window]'s title bar the app's own: the JetBrains Runtime's custom title bar, [TitleBarHeight] tall,
 * with native caption buttons for a light or [dark] background, kept in step with the window's scale. Null
 * while the window has its own title bar: on another JDK, without a window (tests), before the custom bar is
 * in place, or after it failed (the window then gets its own back).
 */
@Composable
fun rememberWindowCaption(window: Frame?, dark: Boolean): WindowCaption? {
    val jbrBar = remember(window) { window?.let(JbrTitleBar::createFor) }
    val density = LocalDensity.current
    var caption by remember(jbrBar) { mutableStateOf<WindowCaption?>(null) }
    LaunchedEffect(jbrBar, density, dark) { caption = jbrBar?.show(TitleBarHeight, density, dark) }
    return caption?.takeUnless { jbrBar?.failed == true }
}

/** The JetBrains Runtime's custom title bar on [window]; after a failure the window keeps its own title bar. */
internal class JbrTitleBar internal constructor(
    private val window: Frame,
    private val decorations: WindowDecorations,
    private val bar: WindowDecorations.CustomTitleBar,
) {
    /** Observable, so the app's bar stops leaving room for caption buttons the window no longer has. */
    var failed by mutableStateOf(false)
        private set

    fun show(height: Dp, density: Density, dark: Boolean): WindowCaption? {
        if (failed) return null
        return try {
            // The runtime measures in AWT's logical pixels and scales them to the screen itself.
            val awtScale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: density.density
            bar.height = with(density) { height.toPx() } / awtScale
            bar.putProperty("controls.dark", dark)
            decorations.setCustomTitleBar(window, bar)
            WindowCaption(with(density) { (bar.rightInset * awtScale).toDp() }, ::hitTest)
        } catch (e: Exception) {
            fallBack(e)
        } catch (e: LinkageError) {
            fallBack(e)
        }
    }

    /** Runs inside Compose's event dispatch, where an exception would close the window, so a failure falls back. */
    private fun hitTest(client: Boolean) {
        if (failed) return
        try {
            bar.forceHitTest(client)
        } catch (e: Exception) {
            fallBack(e)
        } catch (e: LinkageError) {
            fallBack(e)
        }
    }

    private fun fallBack(cause: Throwable): WindowCaption? {
        failed = true
        System.err.println("Requestify: the custom title bar failed, so the window keeps its own: $cause")
        runCatching { decorations.setCustomTitleBar(window, null) }
        return null
    }

    companion object {
        /** Null when the window keeps its own title bar ([titleBarMode]) or the runtime can't make one. */
        fun createFor(window: Frame): JbrTitleBar? {
            if (titleBarMode(JBR::isAvailable, JBR::isWindowDecorationsSupported) != TitleBarMode.Custom) return null
            return runCatching {
                val decorations = JBR.getWindowDecorations()
                JbrTitleBar(window, decorations, decorations.createCustomTitleBar())
            }.onFailure {
                System.err.println("Requestify: couldn't make the custom title bar, so the window keeps its own: $it")
            }.getOrNull()
        }
    }
}

/**
 * The parts of the title bar that belong to the app ("client areas"): there a press goes to the element, not
 * to the window's caption. Elements join with [titleBarClientArea]; [captionHitTest] asks per pointer event.
 */
class TitleBarClientAreas {
    internal val nodes = mutableSetOf<ClientAreaNode>()

    /** Whether [position], in [bar]'s coordinates, is on one of the client areas. */
    internal fun contain(bar: LayoutCoordinates, position: Offset): Boolean =
        nodes.any { bar.localBoundingBoxOf(it.requireLayoutCoordinates()).contains(position) }
}

/** Makes this element of the title bar a client area of [areas]: it gets the clicks, the window doesn't move. */
fun Modifier.titleBarClientArea(areas: TitleBarClientAreas): Modifier = this then ClientAreaElement(areas)

private data class ClientAreaElement(val areas: TitleBarClientAreas) : ModifierNodeElement<ClientAreaNode>() {
    override fun create() = ClientAreaNode(areas)

    override fun update(node: ClientAreaNode) {
        if (node.areas === areas) return
        if (node.isAttached) {
            node.areas.nodes -= node
            areas.nodes += node
        }
        node.areas = areas
    }
}

internal class ClientAreaNode(var areas: TitleBarClientAreas) : Modifier.Node() {
    override fun onAttach() {
        areas.nodes += this
    }

    override fun onDetach() {
        areas.nodes -= this
    }
}

/**
 * Reports to [onHitTest] whether each pointer event over the title bar is on a client area of [areas] (true:
 * the app gets it) or on the caption (false: Windows drags the window, maximizes or restores it on a
 * double-click, and offers snap layouts).
 *
 * How it works: the JetBrains Runtime keeps the bar non-client to Windows and forwards its mouse events to the
 * window as ordinary AWT events, which Compose dispatches synchronously. For each one (except exit and scroll,
 * which the runtime ignores) this modifier calls [onHitTest], which is the runtime's
 * `CustomTitleBar.forceHitTest`. Windows decides a press with the value from the moves before it, so hovering
 * already has to say "client" over a control; that is why the decision is by position (the registered client
 * areas), not by whether a control consumed the event. A press that starts on a client area stays the app's
 * until it is released, so dragging off a control never turns into a window drag.
 */
fun Modifier.captionHitTest(areas: TitleBarClientAreas, onHitTest: (client: Boolean) -> Unit): Modifier =
    this then CaptionHitTestElement(areas, onHitTest)

private data class CaptionHitTestElement(
    val areas: TitleBarClientAreas,
    val onHitTest: (client: Boolean) -> Unit,
) : ModifierNodeElement<CaptionHitTestNode>() {
    override fun create() = CaptionHitTestNode(areas, onHitTest)

    override fun update(node: CaptionHitTestNode) {
        node.areas = areas
        node.onHitTest = onHitTest
    }
}

private class CaptionHitTestNode(
    var areas: TitleBarClientAreas,
    var onHitTest: (client: Boolean) -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    private var pressedOnClientArea = false

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val type = pointerEvent.type
        if (pass != PointerEventPass.Main || type == PointerEventType.Exit || type == PointerEventType.Scroll) return
        val bar = requireLayoutCoordinates()
        val onClientArea = pointerEvent.changes.any { areas.contain(bar, it.position) }
        if (type == PointerEventType.Press) pressedOnClientArea = onClientArea
        onHitTest(onClientArea || pressedOnClientArea)
        if (type == PointerEventType.Release) pressedOnClientArea = false
    }

    override fun onCancelPointerInput() {
        pressedOnClientArea = false
    }
}
