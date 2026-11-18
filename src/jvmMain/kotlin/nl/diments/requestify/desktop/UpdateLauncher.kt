package nl.diments.requestify.desktop

import java.nio.file.Path
import java.util.Base64
import nl.diments.requestify.core.update.PendingUpdate
import nl.diments.requestify.core.update.UpdateStore

/**
 * The command for the install helper: a hidden PowerShell that waits for Requestify ([waitForPid]) to end, installs
 * [msi] per user without UI (`msiexec /qn`, logging to [msiLog], `REQUESTIFY_UPDATER=1` so the MSI does not start the
 * app itself), writes `msiexec`'s exit code to [exitCodeFile], and starts [launcher] again, tray-only when [hidden].
 * It starts the launcher whatever the result: after a failed install Windows Installer has put the old version back.
 * The script goes in encoded, so no path (spaces, quotes) can break the command line.
 */
fun updateHelperCommand(msi: Path, msiLog: Path, exitCodeFile: Path, waitForPid: Long, launcher: Path, hidden: Boolean): List<String> {
    fun quoted(path: Path) = "'" + path.toString().replace("'", "''") + "'"
    fun inArgs(path: Path) = "\"" + path.toString().replace("'", "''") + "\""
    val script = listOf(
        "\$ErrorActionPreference = 'SilentlyContinue'",
        "Wait-Process -Id $waitForPid -Timeout 60",
        "\$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i ${inArgs(msi)} /qn /norestart REQUESTIFY_UPDATER=1 /l*v ${inArgs(msiLog)}' -Wait -PassThru",
        "Set-Content -LiteralPath ${quoted(exitCodeFile)} -Value \$p.ExitCode",
        "Start-Process -FilePath ${quoted(launcher)}" + if (hidden) " -ArgumentList '$AUTOSTART_ARG'" else "",
    ).joinToString("\n")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
}

/** Hands a pending update to the install helper, detached; Requestify then ends and the helper does the rest. */
class UpdateLauncher(private val start: (List<String>) -> Unit = { detachedProcess(it).start() }) {
    fun launch(pending: PendingUpdate, store: UpdateStore, launcher: Path, hidden: Boolean) {
        store.clearExitCode()
        start(
            updateHelperCommand(
                msi = store.dir.resolve(pending.msiFile),
                msiLog = store.dir.resolve("install.log"),
                exitCodeFile = store.exitCodeFile,
                waitForPid = ProcessHandle.current().pid(),
                launcher = launcher,
                hidden = hidden,
            ),
        )
    }
}
