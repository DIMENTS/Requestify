package nl.diments.requestify.desktop

import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.update.PendingUpdate
import nl.diments.requestify.core.update.UpdateStore

class UpdateLauncherTest {
    private lateinit var dir: Path
    private val home = "C:\\Users\\O'Neil Smith\\AppData\\Local"
    private val msi = Path.of("$home\\Requestify-updates\\Requestify-0.2.1.msi")
    private val log = Path.of("$home\\Requestify-updates\\install.log")
    private val exitCode = Path.of("$home\\Requestify-updates\\install-exit-code.txt")
    private val launcher = Path.of("$home\\Requestify\\Requestify.exe")

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-launcher") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    /** The PowerShell script the helper runs (the command carries it encoded, so no quoting can break it). */
    private fun script(command: List<String>): String {
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand"), command.dropLast(1))
        return String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
    }

    private val marker = Path.of("$home\\Requestify-updates\\installing.txt")

    private fun script(hidden: Boolean = false) = script(updateHelperCommand(msi, log, exitCode, marker, 4242, launcher, hidden))

    @Test
    fun theCommandWaitsInstallsRecordsAndRelaunches() {
        val s = script()
        val steps = listOf("Wait-Process -Id 4242", "Start-Process -FilePath 'msiexec.exe'", "Set-Content -LiteralPath", "Start-Process -FilePath 'C:")
        val at = steps.map { step -> s.indexOf(step).also { assertTrue(it >= 0, "missing: $step\n$s") } }
        assertEquals(at.sorted(), at, "wait for the app to end, install, note the result, start Requestify\n$s")
        for (part in listOf("/i ", "/norestart", "REQUESTIFY_UPDATER=1", "/l*v", "-Wait", "-PassThru", "\$p.ExitCode")) {
            assertTrue(part in s, "missing: $part\n$s")
        }
    }

    @Test
    fun pathsWithSpacesAndQuotesSurvive() {
        val s = script()
        assertTrue("-FilePath 'C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify\\Requestify.exe'" in s, s)
        assertTrue("-LiteralPath 'C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\install-exit-code.txt'" in s, s)
        assertTrue("/i \"C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\Requestify-0.2.1.msi\"" in s, "msiexec gets the path in double quotes, inside PowerShell's single-quoted string\n$s")
        assertTrue("/l*v \"C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\install.log\"" in s, s)
    }

    @Test
    fun aHiddenAppComesBackHidden() {
        assertTrue(script(hidden = true).trimEnd().endsWith("-ArgumentList '--autostart'"), "started by Windows: back in the tray")
        assertFalse("--autostart" in script(hidden = false), "opened by the streamer: back with the window")
    }

    @Test
    fun aVisibleAppShowsTheInstallProgress() {
        assertTrue("/passive" in script(hidden = false) && "/qn" !in script(hidden = false), "opened by the streamer: a progress bar, no questions")
        assertTrue("/qn" in script(hidden = true) && "/passive" !in script(hidden = true), "in the tray: nothing on screen")
    }

    @Test
    fun theMarkerGoesJustBeforeTheRelaunch() {
        val s = script()
        val removed = s.indexOf("Remove-Item -LiteralPath 'C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\installing.txt'")
        assertTrue(removed > s.indexOf("Set-Content"), "after the install\n$s")
        assertTrue(removed < s.indexOf("Start-Process -FilePath 'C:"), "before Requestify starts again, or it would step aside\n$s")
    }

    @Test
    fun aStartDuringTheInstallStepsAside() {
        val store = UpdateStore(dir)
        val pending = PendingUpdate("0.2.1", "Requestify-0.2.1.msi", "ab", "", "")
        UpdateLauncher(start = { 4242L }).launch(pending, store, launcher, hidden = false)
        assertTrue(updateInProgress(store, isAlive = { it == 4242L }), "the helper is still installing: a new start leaves it alone")
        assertFalse(updateInProgress(store, isAlive = { false }), "a helper that is gone left an old marker")
        assertFalse(updateInProgress(store, isAlive = { true }), "and that old marker was cleared")
    }

    @Test
    fun theHelperRelaunchesWhateverIsInstalled() {
        val s = script()
        assertFalse("if (" in s || "if(" in s, "after a failed install the old version is back, and it starts too\n$s")
    }

    @Test
    fun itStartsDetached() {
        val store = UpdateStore(dir)
        val pending = PendingUpdate("0.2.1", "Requestify-0.2.1.msi", "ab", "", "")
        val started = mutableListOf<List<String>>()
        UpdateLauncher(start = { started += it; 1L }).launch(pending, store, launcher, hidden = false)
        val s = script(started.single())
        assertTrue("Wait-Process -Id ${ProcessHandle.current().pid()} " in s, "it waits for this process to end")
        assertTrue(store.dir.resolve("Requestify-0.2.1.msi").toString().replace("'", "''") in s)
        assertTrue(store.exitCodeFile.toString().replace("'", "''") in s)
    }
}
