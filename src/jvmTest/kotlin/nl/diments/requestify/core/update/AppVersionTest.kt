package nl.diments.requestify.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun parsesTagsAndVersions() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(255, 255, 65535), AppVersion.parse("255.255.65535"), "Windows Installer's limits are allowed")
        assertEquals("0.2.0", AppVersion(0, 2, 0).toString())
    }

    @Test
    fun rejectsWhatWindowsInstallerCannotTake() {
        for (text in listOf("256.0.0", "0.256.0", "0.0.65536", "0.2.0-beta", "dev", "", "1.2", "1.2.3.4", "v", "a.b.c", " 1.2.3", "-1.2.3")) {
            assertNull(AppVersion.parse(text), text)
        }
    }

    @Test
    fun comparesNumerically() {
        assertTrue(AppVersion(0, 10, 0) > AppVersion(0, 9, 9), "10 is more than 9, not less as text")
        assertTrue(AppVersion(1, 0, 0) > AppVersion(0, 255, 65535))
        assertTrue(AppVersion(0, 2, 1) > AppVersion(0, 2, 0))
        assertEquals(0, AppVersion(0, 2, 0).compareTo(AppVersion(0, 2, 0)))
    }

    @Test
    fun localBuildsAreZeroZero() {
        assertTrue(AppVersion(0, 0, 4711).isLocalBuild, "a build on the developer's PC")
        assertFalse(AppVersion(0, 1, 0).isLocalBuild)
        assertFalse(AppVersion(1, 0, 0).isLocalBuild)
    }
}
