package nl.diments.requestify.desktop

import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.diments.requestify.core.log.PreviousRun
import nl.diments.requestify.core.storage.deleteTestDir

class CrashGuardTest {
    private lateinit var dir: Path
    private var now = 1_800_000_000_000L
    private val relaunched = CopyOnWriteArrayList<List<String>>()
    private val fatal = CopyOnWriteArrayList<String>()
    private val exits = CopyOnWriteArrayList<Int>()
    private val exe = Path.of("C:\\Program Files\\Requestify\\Requestify.exe")

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-crash") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private fun guard(mode: LaunchMode = LaunchMode.Normal, installation: Installation = Installation(exe)) = CrashGuard(
        dir = dir,
        clock = { now },
        mode = mode,
        installation = installation,
        version = "0.1.0",
        relaunch = { relaunched += it },
        showFatal = { fatal += it },
        exit = { exits += it },
        fatalText = { "Requestify is gestopt door een fout. Start het opnieuw." },
    )

    @Test
    fun aCrashWritesAFileAndKeepsTheNewestTen() {
        repeat(11) {
            now += 1_000
            guard().onUncaught(IllegalStateException("boom $it"))
        }
        val files = dir.resolve("crash").listDirectoryEntries("*.txt").sortedBy { it.name }
        assertEquals(10, files.size)
        assertTrue("boom 10" in files.last().readText(), "the newest is kept")
        assertTrue(files.none { "boom 0\n" in it.readText() || it.readText().startsWith("java.lang.IllegalStateException: boom 0") }, "the oldest went")
        assertTrue(files.last().readText().contains("0.1.0"), "with the app version")
    }

    @Test
    fun aCrashRestartsOnceWithAfterCrash() {
        val guard = guard()
        guard.markRunning()
        guard.onUncaught(IllegalStateException("boom"))
        assertEquals(listOf(listOf(exe.toString(), "--after-crash")), relaunched)
        assertEquals(emptyList(), fatal)
        assertEquals(listOf(1), exits, "and the broken process ends")
    }

    @Test
    fun theRelaunchPassesThePathAsOneArgument() {
        guard().onUncaught(RuntimeException("x"))
        assertEquals("C:\\Program Files\\Requestify\\Requestify.exe", relaunched.single().first(), "one element, spaces and all")
    }

    @Test
    fun aSecondCrashWithinTenMinutesStopsWithTheMessage() {
        val restarted = guard(LaunchMode.AfterCrash)
        restarted.markRunning()
        now += 5 * 60_000
        restarted.onUncaught(IllegalStateException("again"))
        assertEquals(emptyList(), relaunched, "no restart loop")
        assertEquals(listOf("Requestify is gestopt door een fout. Start het opnieuw."), fatal)
        assertEquals(listOf(1), exits)
    }

    @Test
    fun aCrashLongAfterARestartRestartsAgain() {
        val restarted = guard(LaunchMode.AfterCrash)
        restarted.markRunning()
        now += 11 * 60_000
        restarted.onUncaught(IllegalStateException("much later"))
        assertEquals(1, relaunched.size)
        assertEquals(emptyList(), fatal)
    }

    @Test
    fun notInstalledShowsTheMessageInsteadOfRestarting() {
        val dev = guard(installation = Installation(null))
        dev.markRunning()
        dev.onUncaught(IllegalStateException("boom"))
        assertEquals(emptyList(), relaunched, "`gradlew run` has no launcher to start")
        assertEquals(1, fatal.size)
    }

    @Test
    fun aSecondErrorWhileHandlingOneEndsAtOnce() {
        val guard = guard()
        guard.onUncaught(IllegalStateException("first"))
        guard.onUncaught(IllegalStateException("while handling"))
        assertEquals(1, relaunched.size)
        assertEquals(listOf(1, 1), exits)
    }

    @Test
    fun aLeftoverMarkerIsReportedWithItsCrashLine() {
        val first = guard()
        first.markRunning()
        now += 60_000
        first.touch()
        now += 10_000
        first.onUncaught(IllegalStateException("boom"))

        val restarted = guard(LaunchMode.AfterCrash)
        val previous = assertNotNull(restarted.previousRun())
        assertEquals(PreviousRun(lastAliveEpochMs = now - 10_000, crashLine = "java.lang.IllegalStateException: boom", restarted = true), previous)
    }

    @Test
    fun aKilledRunIsReportedWithoutACrashLine() {
        val first = guard()
        first.markRunning() // then Task Manager ends it: no crash file, the marker stays
        val next = guard()
        assertEquals(PreviousRun(now, crashLine = null, restarted = false), next.previousRun())
    }

    @Test
    fun anOrderlyEndLeavesNoMarker() {
        val first = guard()
        first.markRunning()
        first.markOrderlyEnd()
        assertNull(guard().previousRun())
    }

    @Test
    fun theShutdownHookEndsOrderly() {
        val first = guard()
        first.markRunning()
        first.shutdownHook.run() // Windows shuts down or the user logs off
        assertNull(guard().previousRun(), "a PC shutdown is no crash")
    }

    @Test
    fun theShutdownHookDoesNotEraseACrash() {
        val first = guard()
        first.markRunning()
        first.onUncaught(IllegalStateException("boom"))
        first.shutdownHook.run() // exiting after the crash runs the hooks too
        assertNotNull(guard().previousRun())
    }

    @Test
    fun anErrorDuringShutdownIsNoCrash() {
        val first = guard()
        first.markRunning()
        first.shutdownHook.run() // Windows signs out or shuts down: the hook runs first
        first.onUncaught(IllegalStateException("while shutting down"))
        assertEquals(emptyList(), relaunched, "no restart while Windows shuts down")
        assertEquals(emptyList(), fatal, "and no message")
        assertEquals(emptyList(), exits, "the JVM is ending already; exiting during the hooks would block")
        assertNull(guard().previousRun(), "the end stays orderly")
        assertTrue(
            dir.resolve("crash").listDirectoryEntries("*.txt").single().readText().contains("while shutting down"),
            "the error is still kept for diagnosis",
        )
    }

    @Test
    fun anExceptionOnTheEventThreadReachesTheGuard() {
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val guard = guard()
            guard.installHandlers()
            EventQueue.invokeLater { throw IllegalStateException("on the UI thread") }
            repeat(200) { if (exits.isEmpty()) Thread.sleep(10) }
            assertEquals(listOf(1), exits, "an error on the AWT event thread is handled like any other")
            assertTrue(dir.resolve("crash").listDirectoryEntries("*.txt").single().readText().contains("on the UI thread"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
    }
}
