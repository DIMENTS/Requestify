package nl.diments.requestify.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import nl.diments.requestify.core.log.PreviousRun
import nl.diments.requestify.core.storage.AppJson

/**
 * In `running.json` while Requestify runs; removed on every orderly end. One that is still there at start means the
 * last run did not end properly. Names are stable: a new version must read the one an old version left.
 */
@Serializable
data class RunMarker(
    val startedAtEpochMs: Long,
    val lastAliveEpochMs: Long,
    val afterCrash: Boolean = false,
    val crashFile: String? = null,
    /** Which run wrote it: an orderly end removes only its own run's marker (plan 6). */
    val runId: String? = null,
)

/**
 * Catches what nothing else caught (any thread, the AWT event thread and the app's coroutines included), writes it to
 * `crash/<time>.txt` (the newest [keepFiles] are kept), and ends the broken process: a half-alive app is worse than a
 * restart, and the music keeps playing in Spotify meanwhile. An installed app starts itself again once ([relaunch] with
 * `--after-crash`); a second crash within [restartWindowMs] of such a restart (or a crash without a launcher, under
 * `gradlew run`) stops for good with a message instead ([showFatal]), so it can never loop. The running marker tells
 * the next start that this run did not end properly; Quit, Windows shutting down and logging off are orderly ends.
 */
class CrashGuard(
    private val dir: Path,
    private val clock: () -> Long,
    private val mode: LaunchMode,
    private val installation: Installation,
    private val version: String,
    private val relaunch: (List<String>) -> Unit,
    private val showFatal: (String) -> Unit,
    private val exit: (Int) -> Unit,
    private val fatalText: () -> String,
    private val restartWindowMs: Long = 600_000,
    private val keepFiles: Int = 10,
    /** Whether the JVM is ending already, also before our own [shutdownHook] ran (hooks run in any order). */
    private val isShuttingDown: () -> Boolean = ::jvmIsShuttingDown,
) {
    /** This run's id in its marker. */
    private val runId = UUID.randomUUID().toString()

    private val markerFile: Path get() = dir.resolve("running.json")
    private val crashDir: Path get() = dir.resolve("crash")
    private val lock = Any()

    /** Set once a crash is being handled: nothing after that may look like an orderly end. */
    @Volatile
    private var crashing = false

    /** Guarded by [lock]: this run's marker. */
    private var marker: RunMarker? = null

    /** Guarded by [lock]: the run ended in order; a late [touch] writes nothing. */
    private var ended = false

    /** Set when the JVM starts ending (Windows shutting down, logoff, Quit): an error after that is no crash. */
    @Volatile
    var shuttingDown = false
        private set

    /**
     * Stops the app's work and hides it before the fatal message ([showFatal]) is shown, so nothing keeps running
     * behind it. Set once the app exists; a failure here still shows the message.
     */
    @Volatile
    var beforeFatal: () -> Unit = {}

    /** Saves what is still pending (bounded) when the JVM ends: Windows may shut down right after a change. */
    @Volatile
    var flushOnShutdown: () -> Unit = {}

    /**
     * Runs on Windows shutdown, logoff and every normal exit: saves what is pending ([flushOnShutdown]), then an orderly
     * end (unless a crash is being handled).
     */
    val shutdownHook: Thread = Thread({
        shuttingDown = true
        try {
            flushOnShutdown()
        } catch (_: Throwable) {
            // the end stays orderly
        }
        markOrderlyEnd()
    }, "requestify-orderly-end")

    /** The run before this one, when it did not end properly. Read before [markRunning]. */
    fun previousRun(): PreviousRun? {
        val left = readMarker() ?: return null
        val crashLine = left.crashFile?.let { name ->
            try {
                Files.readAllLines(crashDir.resolve(name)).firstOrNull()
            } catch (_: IOException) {
                null
            }
        }
        return PreviousRun(left.lastAliveEpochMs, crashLine, restarted = mode == LaunchMode.AfterCrash)
    }

    fun markRunning() {
        val now = clock()
        write(RunMarker(now, now, afterCrash = mode == LaunchMode.AfterCrash, runId = runId))
    }

    /** Remembers that the app is still alive now (the last sign of life the next start reports). */
    fun touch() {
        synchronized(lock) {
            if (crashing || ended) return
            marker?.let { write(it.copy(lastAliveEpochMs = clock())) }
        }
    }

    /**
     * This run ended in order: its marker goes. A marker of another run (a new instance that started while this one
     * quit) stays.
     */
    fun markOrderlyEnd() {
        synchronized(lock) {
            if (crashing) return
            ended = true
            marker = null
            val onDisk = readMarker()
            if (onDisk != null && onDisk.runId != runId) return
            try {
                Files.deleteIfExists(markerFile)
            } catch (_: IOException) {
            }
        }
    }

    fun onUncaught(t: Throwable) {
        if (shuttingDown || isShuttingDown()) {
            // The JVM is ending already (Windows shuts down or signs out): keep the error for diagnosis, but no restart
            // and no message. Exiting here would block until the shutdown hooks are done; the end comes anyway.
            try {
                writeCrashFile(t)
            } catch (_: Throwable) {
            }
            return
        }
        synchronized(lock) {
            if (crashing) {
                exit(1) // an error while handling one: just end
                return
            }
            crashing = true
        }
        try {
            val file = writeCrashFile(t)
            synchronized(lock) { marker }?.let { write(it.copy(crashFile = file)) }
                ?: write(RunMarker(clock(), clock(), crashFile = file, runId = runId))
            val exe = installation.launcherExe
            val loop = mode == LaunchMode.AfterCrash && clock() - (synchronized(lock) { marker }?.startedAtEpochMs ?: 0) < restartWindowMs
            if (exe != null && !loop) {
                relaunch(listOf(exe.toString(), AFTER_CRASH_ARG))
            } else {
                try {
                    beforeFatal()
                } catch (_: Throwable) {
                    // the message still shows
                }
                showFatal(fatalText())
            }
        } catch (_: Throwable) {
            // nothing more can be done: end anyway
        } finally {
            exit(1)
        }
    }

    /** Sends every otherwise uncaught error, on any thread (the AWT event thread included), to [onUncaught]. */
    fun installHandlers() {
        Thread.setDefaultUncaughtExceptionHandler { _, t -> onUncaught(t) }
    }

    /** [installHandlers], the [shutdownHook], and the handler for the app's coroutine scope. */
    fun install(): CoroutineExceptionHandler {
        installHandlers()
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        return CoroutineExceptionHandler { _, t -> onUncaught(t) }
    }

    /** Writes the crash file and keeps the newest [keepFiles]; returns its name. */
    private fun writeCrashFile(t: Throwable): String {
        Files.createDirectories(crashDir)
        val name = "${FILE_TIME.format(Instant.ofEpochMilli(clock()))}.txt"
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }
        Files.writeString(crashDir.resolve(name), "$t\nRequestify $version, ${Instant.ofEpochMilli(clock())}\n\n$trace")
        Files.list(crashDir).use { files ->
            files.filter { it.fileName.toString().endsWith(".txt") }.sorted(Comparator.reverseOrder()).skip(keepFiles.toLong())
                .forEach { Files.deleteIfExists(it) }
        }
        return name
    }

    private fun readMarker(): RunMarker? = try {
        if (!Files.exists(markerFile)) null else AppJson.instance.decodeFromString(RunMarker.serializer(), Files.readString(markerFile))
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun write(value: RunMarker) {
        synchronized(lock) { marker = value }
        try {
            Files.createDirectories(dir)
            val tmp = markerFile.resolveSibling("running.json.tmp")
            Files.writeString(tmp, AppJson.instance.encodeToString(RunMarker.serializer(), value))
            Files.move(tmp, markerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // the marker is a hint for the next start; never let it end this one
        }
    }

    private companion object {
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * Whether the JVM is shutting down (Windows shutting down or signing out, or an exit): adding a shutdown hook then
 * throws. Independent of which hook runs first.
 */
fun jvmIsShuttingDown(): Boolean = try {
    val probe = Thread {}
    Runtime.getRuntime().addShutdownHook(probe)
    Runtime.getRuntime().removeShutdownHook(probe)
    false
} catch (_: IllegalStateException) {
    true
}

/** A message that stays on screen after Requestify is gone, also when its own window can no longer draw. */
fun windowsMessageBox(text: String) {
    MessageBoxApi.INSTANCE.MessageBox(null, text, "Requestify", MB_OK or MB_ICONERROR or MB_TOPMOST)
}

/** user32's MessageBoxW, which JNA's own User32 does not map. */
private interface MessageBoxApi : StdCallLibrary {
    @Suppress("FunctionName")
    fun MessageBox(hWnd: WinDef.HWND?, text: String, caption: String, type: Int): Int

    companion object {
        val INSTANCE: MessageBoxApi = Native.load("user32", MessageBoxApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

private const val MB_OK = 0x0
private const val MB_ICONERROR = 0x10
private const val MB_TOPMOST = 0x40000
