package nl.diments.requestify.desktop

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.update.PendingUpdate
import nl.diments.requestify.core.update.UpdateStore
import nl.diments.requestify.core.update.sha256Of

class UpdateLauncherTest {
    private lateinit var dir: Path
    private val home = "C:\\Users\\O'Neil Smith\\AppData\\Local"
    private val msi = Path.of("$home\\Requestify-updates\\Requestify-0.2.1.msi")
    private val log = Path.of("$home\\Requestify-updates\\install.log")
    private val exitCode = Path.of("$home\\Requestify-updates\\install-exit-code.txt")
    private val launcher = Path.of("$home\\Requestify\\Requestify.exe")

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-launcher") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    /** The PowerShell script the helper runs: readable, as one `-Command` argument. */
    private fun script(command: List<String>): String {
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command"), command.dropLast(1))
        return command.last()
    }

    private val marker = Path.of("$home\\Requestify-updates\\installing.txt")

    /** A downloaded installer in [store] and its pending note, with the right checksum. */
    private fun downloaded(store: UpdateStore): PendingUpdate {
        val file = store.dir.resolve("Requestify-0.2.1.msi")
        java.nio.file.Files.write(file, byteArrayOf(1, 2, 3))
        return PendingUpdate("0.2.1", "Requestify-0.2.1.msi", checkNotNull(sha256Of(file)), "", "").also(store::savePending)
    }

    private fun script(hidden: Boolean = false, pids: List<Long> = listOf(4242)) =
        script(updateHelperCommand(msi, log, exitCode, marker, pids, launcher, hidden))

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
        assertTrue(
            "'/i ' + \$q + 'C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\Requestify-0.2.1.msi' + \$q" in s,
            "msiexec gets the path in double quotes (\$q), the quote in it doubled\n$s",
        )
        assertTrue("/l*v ' + \$q + 'C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\install.log' + \$q" in s, s)
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
        val pending = downloaded(store)
        UpdateLauncher(start = { 4242L }).launch(pending, store, launcher, hidden = false)
        assertTrue(updateInProgress(store, isAlive = { it == 4242L }), "the helper is still installing: a new start leaves it alone")
        assertFalse(updateInProgress(store, isAlive = { false }), "a helper that is gone left an old marker")
        assertFalse(updateInProgress(store, isAlive = { true }), "and that old marker was cleared")
    }

    @Test
    fun aHelperThatCannotStartIsReportedNotThrown() {
        val store = UpdateStore(dir)
        val pending = downloaded(store)
        val started = UpdateLauncher(start = { throw java.io.IOException("powershell.exe not found") }).launch(pending, store, launcher, hidden = false)
        assertFalse(started, "Requestify then simply keeps running")
        assertFalse(updateInProgress(store, isAlive = { true }), "and no start is held back")
    }

    @Test
    fun theHelperRelaunchesWhateverIsInstalled() {
        val s = script()
        val statements = s.split("; ")
        assertEquals(1, statements.count { it.startsWith("if ") }, "only the exit code is looked at\n$s")
        assertTrue(
            statements.last().startsWith("Start-Process -FilePath 'C:"),
            "after a failed install the old version is back, and it starts too, unconditionally\n$s",
        )
    }

    @Test
    fun itStartsDetached() {
        val store = UpdateStore(dir)
        val pending = downloaded(store)
        val started = mutableListOf<List<String>>()
        UpdateLauncher(start = { started += it; 1L }).launch(pending, store, launcher, hidden = false)
        val s = script(started.single())
        assertTrue("Wait-Process -Id ${ProcessHandle.current().pid()} " in s, "it waits for this process to end")
        assertTrue(store.dir.resolve("Requestify-0.2.1.msi").toString().replace("'", "''") in s)
        assertTrue(store.exitCodeFile.toString().replace("'", "''") in s)
    }

    @Test
    fun aChangedMsiIsNotInstalled() {
        val store = UpdateStore(dir)
        val pending = downloaded(store)
        java.nio.file.Files.write(store.dir.resolve(pending.msiFile), byteArrayOf(9, 9, 9)) // changed since it was checked
        val started = mutableListOf<List<String>>()
        assertFalse(UpdateLauncher(start = { started += it; 1L }).launch(pending, store, launcher, hidden = false))
        assertEquals(emptyList(), started, "nothing is installed")
        assertNull(store.pending(), "it is downloaded again later")
    }

    @Test
    fun theHelperIsAReadableCommand() {
        val s = script(pids = listOf(11, 22))
        assertFalse('"' in s, "no double quote of its own: Java's argument quoting cannot break it\n$s")
        assertTrue("Wait-Process -Id 11,22 -Timeout 60" in s, "the app and its launcher\n$s")
        assertTrue("if (\$c -eq 3010 -or \$c -eq 1641) { \$c = 0 }" in s, "installed, a restart of Windows pending: success\n$s")
        assertTrue("Set-Content -LiteralPath 'C:\\Users\\O''Neil Smith\\AppData\\Local\\Requestify-updates\\install-exit-code.txt' -Value \$c" in s, s)
    }
}
