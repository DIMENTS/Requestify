package nl.diments.requestify.desktop

import java.nio.file.Files
import java.nio.file.Path
import nl.diments.requestify.core.update.PendingUpdate
import nl.diments.requestify.core.update.UpdateStore
import nl.diments.requestify.core.update.sha256Of

/**
 * The command for the install helper: a hidden PowerShell that waits for Requestify ([waitForPids]: the app, and the
 * launcher that started it) to end, installs
 * [msi] per user (`msiexec`, logging to [msiLog], `REQUESTIFY_UPDATER=1` so the MSI does not start the app itself),
 * writes `msiexec`'s exit code to [exitCodeFile] (3010 and 1641, installed with a restart of Windows pending, count as
 * success: 0), removes [installingMarker] and starts [launcher] again.
 * - Opened by the streamer: `/passive`, a progress bar without questions. Hidden in the tray ([hidden]): `/qn`, nothing
 *   on screen, and the app comes back in the tray.
 * - It starts the launcher whatever the result: after a failed install Windows Installer has put the old version back.
 * - The marker goes just before that start, so the new start does not take the helper for an install still running.
 * - The script is readable (`-Command`, not encoded: an unsigned app starting an encoded PowerShell looks suspicious to
 *   virus scanners). It has no double quote of its own (msiexec's are made from `$q`) and every path is in single
 *   quotes with its quotes doubled, so neither Java's argument quoting nor a path can break it.
 */
fun updateHelperCommand(
    msi: Path,
    msiLog: Path,
    exitCodeFile: Path,
    installingMarker: Path,
    waitForPids: List<Long>,
    launcher: Path,
    hidden: Boolean,
): List<String> {
    fun quoted(path: Path) = "'" + path.toString().replace("'", "''") + "'"
    // msiexec wants its paths in double quotes: added in PowerShell from $q, so the command line itself carries none.
    fun inArgs(path: Path) = "\$q + ${quoted(path)} + \$q"
    val ui = if (hidden) "/qn" else "/passive"
    val script = listOf(
        "\$ErrorActionPreference = 'SilentlyContinue'",
        "\$q = [char]34",
        "Wait-Process -Id ${waitForPids.joinToString(",")} -Timeout 60",
        "\$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ('/i ' + ${inArgs(msi)} + ' $ui /norestart REQUESTIFY_UPDATER=1 " +
            "/l*v ' + ${inArgs(msiLog)}) -Wait -PassThru",
        "\$c = \$p.ExitCode",
        "if (\$c -eq 3010 -or \$c -eq 1641) { \$c = 0 }",
        "Set-Content -LiteralPath ${quoted(exitCodeFile)} -Value \$c",
        "Remove-Item -LiteralPath ${quoted(installingMarker)}",
        "Start-Process -FilePath ${quoted(launcher)}" + if (hidden) " -ArgumentList '$AUTOSTART_ARG'" else "",
    ).joinToString("; ")
    return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
}

/**
 * The jpackage launcher that started this JVM, when [launcher] is it: the helper waits for it too, as Windows Installer
 * cannot replace a launcher that still runs.
 */
private fun launcherParent(launcher: Path): Long? = try {
    val parent = ProcessHandle.current().parent().orElse(null)
    val command = parent?.info()?.command()?.orElse(null)
    fun normal(p: Path) = p.toAbsolutePath().normalize().toString()
    parent?.pid()?.takeIf { command != null && normal(Path.of(command)).equals(normal(launcher), ignoreCase = true) }
} catch (_: Exception) {
    null
}

/** `installing.txt` in the updates folder: the helper's process id while it installs. */
private fun installingMarker(store: UpdateStore): Path = store.dir.resolve("installing.txt")

/**
 * Hands a pending update to the install helper, detached, and notes the helper's process id ([updateInProgress]);
 * Requestify then ends and the helper does the rest. [launch] is false when the helper could not be started; it never
 * throws (an error here must not reach the crash guard).
 */
class UpdateLauncher(private val start: (List<String>) -> Long? = { detachedProcess(it).start().pid() }) {
    fun launch(pending: PendingUpdate, store: UpdateStore, launcher: Path, hidden: Boolean): Boolean {
        // Checked once more right before installing: a file that changed since it was verified is fetched again later.
        if (sha256Of(store.dir.resolve(pending.msiFile)) != pending.sha256) {
            store.clearPending()
            return false
        }
        store.clearExitCode()
        val marker = installingMarker(store)
        val helper = try {
            start(
            updateHelperCommand(
                msi = store.dir.resolve(pending.msiFile),
                msiLog = store.dir.resolve("install.log"),
                exitCodeFile = store.exitCodeFile,
                installingMarker = marker,
                waitForPids = listOf(ProcessHandle.current().pid()) + listOfNotNull(launcherParent(launcher)),
                launcher = launcher,
                hidden = hidden,
            ),
            )
        } catch (_: Exception) {
            return false // the helper could not start: Requestify simply keeps running this version
        }
        if (helper != null) {
            try {
                Files.createDirectories(store.dir)
                Files.writeString(marker, helper.toString())
            } catch (_: Exception) {
                // without the marker a second start is merely not held back
            }
        }
        return true
    }
}

/**
 * An update is being installed right now: a helper noted in the marker still runs. A start then steps aside (the
 * helper starts Requestify itself when done); it would otherwise lock files being replaced, or install a second time.
 * A marker whose helper is gone is cleared.
 */
fun updateInProgress(
    store: UpdateStore,
    isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
): Boolean {
    val marker = installingMarker(store)
    return try {
        if (!Files.exists(marker)) return false
        val pid = Files.readString(marker).trim().toLongOrNull()
        if (pid != null && isAlive(pid)) {
            true
        } else {
            Files.deleteIfExists(marker)
            false
        }
    } catch (_: Exception) {
        false
    }
}
