package nl.diments.requestify.ui.common

import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconsTest {
    @Test
    fun phosphorIconsParse() {
        val icons = mapOf(
            "SpotifyLogo" to AppIcons.SpotifyLogo,
            "TwitchLogo" to AppIcons.TwitchLogo,
            "HandHeart" to AppIcons.HandHeart,
            "CheckBold" to AppIcons.CheckBold,
        )
        for ((name, icon) in icons) {
            assertEquals(name, icon.name)
            assertEquals(256f, icon.viewportWidth, "$name uses Phosphor's 256-unit grid")
            assertEquals(256f, icon.viewportHeight)
            val path = icon.root.filterIsInstance<VectorPath>().singleOrNull()
            assertTrue(path != null && path.pathData.size > 3, "$name has its path")
        }
    }
}
