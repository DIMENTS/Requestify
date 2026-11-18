package nl.diments.requestify.desktop

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchTest {
    @Test
    fun argumentsGiveTheMode() {
        assertEquals(LaunchMode.Normal, launchMode(emptyArray()))
        assertEquals(LaunchMode.Autostart, launchMode(arrayOf("--autostart")))
        assertEquals(LaunchMode.AfterCrash, launchMode(arrayOf("--after-crash")))
        assertEquals(LaunchMode.Normal, launchMode(arrayOf("--something-else")))
    }

    @Test
    fun notInstalledWithoutJpackagePath() {
        assertFalse(Installation.fromAppPath(null).isInstalled, "`gradlew run` has no launcher")
        assertFalse(Installation.fromAppPath("C:\\nowhere\\Requestify.exe").isInstalled, "a launcher that is not there")
        val exe = createTempFile("Requestify", ".exe")
        try {
            val installed = Installation.fromAppPath(exe.toString())
            assertTrue(installed.isInstalled)
            assertEquals(exe, installed.launcherExe)
        } finally {
            exe.deleteIfExists()
        }
    }
}
