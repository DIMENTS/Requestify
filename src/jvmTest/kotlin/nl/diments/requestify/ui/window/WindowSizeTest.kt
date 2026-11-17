package nl.diments.requestify.ui.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowSizeTest {
    private val minimum = DpSize(720.dp, 640.dp)

    @Test
    fun `a window whose AWT scale equals the density keeps the dp numbers`() {
        assertEquals(Dimension(720, 640), awtMinimumSize(minimum, density = 1.5f, awtScale = 1.5))
    }

    @Test
    fun `AWT units that are device pixels get the dp scaled up`() {
        assertEquals(Dimension(1080, 960), awtMinimumSize(minimum, density = 1.5f, awtScale = 1.0))
    }

    @Test
    fun `no scaling leaves the dp numbers`() {
        assertEquals(Dimension(720, 640), awtMinimumSize(minimum, density = 1f, awtScale = 1.0))
    }
}
