package nl.diments.requestify.desktop

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.deleteTestDir

class UninstallCleanupTest {
    private lateinit var dir: Path
    private val registry = FakeRegistry()
    private val exe = Path.of("C:\\Users\\Lisa\\AppData\\Local\\Requestify\\Requestify.exe")
    private val run = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val approved = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
    private val marker get() = dir.resolve("autostart-removed.json")

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-uninstall") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private fun installed() = RunKeyAutostart(registry, Installation(exe))

    /** What Task Manager writes when the streamer switches Requestify off under Startup apps. */
    private fun disableInTaskManager() = registry.setBinary(approved, "Requestify", byteArrayOf(3, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8))

    @Test
    fun cleanupRemembersAnEnabledEntry() {
        installed().enable()
        cleanUpForUninstall(registry, dir)
        assertNull(registry.getString(run, "Requestify"), "the Run entry is gone with the app")
        assertNull(registry.getBinary(approved, "Requestify"), "and Task Manager's switch with it")
        assertTrue("\"on\"" in marker.readText(), marker.readText())
    }

    @Test
    fun cleanupRemembersADisabledEntry() {
        installed().enable()
        disableInTaskManager()
        cleanUpForUninstall(registry, dir)
        assertNull(registry.getString(run, "Requestify"))
        assertTrue("\"disabled_in_task_manager\"" in marker.readText(), marker.readText())
    }

    @Test
    fun cleanupWithoutAnEntryLeavesNoMarker() {
        cleanUpForUninstall(registry, dir)
        assertFalse(marker.exists(), "nothing was removed, so nothing comes back")
    }

    @Test
    fun restoreBringsBackExactlyWhatWasRemoved() {
        installed().enable()
        cleanUpForUninstall(registry, dir)
        assertEquals(AutostartState.Off, installed().state(), "uninstalled: nothing starts")
        restoreAutostartAfterReinstall(dir, installed())
        assertEquals(AutostartState.On, installed().state())
        assertFalse(marker.exists(), "once")

        disableInTaskManager()
        cleanUpForUninstall(registry, dir)
        assertEquals(AutostartState.Off, installed().state())
        restoreAutostartAfterReinstall(dir, installed())
        assertEquals(AutostartState.DisabledInTaskManager, installed().state(), "switched off in Task Manager stays off")
    }

    @Test
    fun noMarkerNoRestore() {
        // The streamer removed the entry with a startup tool: plan 4's rule, it is not re-created.
        restoreAutostartAfterReinstall(dir, installed())
        assertEquals(AutostartState.Off, installed().state())
        assertEquals(0, registry.writes)
    }

    @Test
    fun aBrokenMarkerIsDeletedAndIgnored() {
        marker.writeText("not json")
        restoreAutostartAfterReinstall(dir, installed())
        assertEquals(AutostartState.Off, installed().state())
        assertFalse(marker.exists())
    }

    @Test
    fun theMarkerWaitsForAnInstalledBuild() {
        installed().enable()
        cleanUpForUninstall(registry, dir)
        restoreAutostartAfterReinstall(dir, RunKeyAutostart(registry, Installation(null))) // gradlew run
        assertTrue(marker.exists(), "kept for the installed build")
        assertNull(registry.getString(run, "Requestify"))
    }

    @Test
    fun cleanupNeverThrows() {
        installed().enable()
        val refusing = object : RegistryPort by registry {
            override fun delete(key: String, name: String) = throw RuntimeException("access denied")
        }
        cleanUpForUninstall(refusing, dir.resolve("missing").resolve("deeper"))
    }
}
