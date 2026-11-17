package nl.diments.requestify.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jetbrains.WindowDecorations
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CustomTitleBarSupportTest {
    @Test
    fun theJetBrainsRuntimeWithWindowDecorationsGetsTheCustomBar() {
        assertEquals(TitleBarMode.Custom, titleBarMode(jbrAvailable = { true }, decorationsSupported = { true }))
    }

    @Test
    fun withoutWindowDecorationsTheWindowKeepsItsOwnBar() {
        assertEquals(TitleBarMode.Native, titleBarMode(jbrAvailable = { true }, decorationsSupported = { false }))
    }

    @Test
    fun onAnotherJdkTheWindowKeepsItsOwnBarWithoutAskingForDecorations() {
        var asked = false
        val mode = titleBarMode(jbrAvailable = { false }, decorationsSupported = { asked = true; true })
        assertEquals(TitleBarMode.Native, mode)
        assertFalse(asked, "the decorations are only asked for on the JetBrains Runtime")
    }

    @Test
    fun aProbeThatFailsKeepsTheWindowsOwnBar() {
        val noApi = titleBarMode({ throw NoClassDefFoundError("com/jetbrains/JBR") }, decorationsSupported = { true })
        val brokenService = titleBarMode(jbrAvailable = { true }, decorationsSupported = { error("service failed") })
        assertEquals(TitleBarMode.Native, noApi)
        assertEquals(TitleBarMode.Native, brokenService)
    }

    @Test
    fun aHitTestThatFailsGivesTheWindowItsOwnTitleBarBack() {
        val installed = mutableListOf<WindowDecorations.CustomTitleBar?>()
        val decorations = object : WindowDecorations {
            override fun setCustomTitleBar(frame: Frame, bar: WindowDecorations.CustomTitleBar?) {
                installed += bar
            }
            override fun setCustomTitleBar(dialog: Dialog, bar: WindowDecorations.CustomTitleBar?) = error("unused")
            override fun createCustomTitleBar() = error("unused")
        }
        var hitTests = 0
        val bar = FailingHitTestBar { hitTests++ }
        val frame = Frame()
        try {
            val titleBar = JbrTitleBar(frame, decorations, bar)
            val caption = assertNotNull(titleBar.show(TitleBarHeight, Density(1f), dark = true))
            assertSame(bar, installed.last())

            caption.hitTest(true) // the runtime throws; nothing may reach Compose's event dispatch
            assertTrue(titleBar.failed)
            assertNull(installed.last(), "the window has its own title bar again")

            caption.hitTest(false)
            assertEquals(1, hitTests, "the failed bar is not asked again")
            assertNull(titleBar.show(TitleBarHeight, Density(1f), dark = false), "and is not shown again")
        } finally {
            frame.dispose()
        }
    }

    /** A custom title bar whose hit test fails in the runtime, after telling [onHitTest]. */
    private class FailingHitTestBar(private val onHitTest: () -> Unit) : WindowDecorations.CustomTitleBar {
        private val properties = mutableMapOf<String, Any>()
        private var height = 0f
        override fun getHeight() = height
        override fun setHeight(height: Float) {
            this.height = height
        }
        override fun getProperties(): Map<String, Any> = properties
        override fun putProperties(map: Map<String, *>) = map.forEach { (key, value) -> putProperty(key, value) }
        override fun putProperty(key: String, value: Any?) {
            if (value == null) properties -= key else properties[key] = value
        }
        override fun getLeftInset() = 0f
        override fun getRightInset() = 141f
        override fun forceHitTest(client: Boolean) {
            onHitTest()
            throw IllegalStateException("the native hit test failed")
        }
        override fun getContainingWindow(): Window? = null
    }

    @Test
    fun aPressThatStartsOnAClientAreaStaysWithTheAppUntilReleased() = runComposeUiTest {
        val hits = mutableListOf<Boolean>()
        setContent {
            val areas = remember { TitleBarClientAreas() }
            Row(Modifier.size(300.dp, 40.dp).captionHitTest(areas) { hits += it }) {
                Box(Modifier.size(40.dp).titleBarClientArea(areas).testTag("control"))
                Box(Modifier.size(40.dp).testTag("empty"))
            }
        }
        val control = onNodeWithTag("control").fetchSemanticsNode().boundsInRoot.center
        val empty = onNodeWithTag("empty").fetchSemanticsNode().boundsInRoot.center

        onRoot().performMouseInput { moveTo(empty) }
        assertEquals(false, hits.last(), "empty bar is caption")
        onRoot().performMouseInput { moveTo(control) }
        assertEquals(true, hits.last(), "a client area is the app's")
        onRoot().performMouseInput { press() }
        onRoot().performMouseInput { moveTo(empty) }
        assertEquals(true, hits.last(), "dragging off the control does not turn into a window drag")
        onRoot().performMouseInput { release() }
        onRoot().performMouseInput { moveBy(Offset(1f, 0f)) }
        assertEquals(false, hits.last(), "after the release empty bar is caption again")
    }
}
