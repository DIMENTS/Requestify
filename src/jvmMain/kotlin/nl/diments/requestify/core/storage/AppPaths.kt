package nl.diments.requestify.core.storage

import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Where Requestify keeps its files: [dataDir] (`%APPDATA%\Requestify`, roams with the Windows profile) and
 * [updatesDir] (`%LOCALAPPDATA%\Requestify-updates`: a downloaded installer never roams, and it is not inside the
 * install folder, which the installer replaces).
 */
class AppPaths(val dataDir: Path, val updatesDir: Path = dataDir.resolveSibling("${dataDir.fileName}-updates")) {
    val settingsFile: Path get() = dataDir.resolve("settings.json")
    val queueFile: Path get() = dataDir.resolve("queue.json")
    val secretsFile: Path get() = dataDir.resolve("secrets.json")
    val blacklistFile: Path get() = dataDir.resolve("blacklist.json")
    val rejectedFile: Path get() = dataDir.resolve("rejected.json")
    val pendingFile: Path get() = dataDir.resolve("pending.json")
    val streamFile: Path get() = dataDir.resolve("stream.json")
    val redemptionsFile: Path get() = dataDir.resolve("redemptions.json")
    val donationsFile: Path get() = dataDir.resolve("donations.json")

    /** The Log's day files (`yyyy-MM-dd.jsonl`). */
    val logDir: Path get() = dataDir.resolve("log")

    companion object {
        /** `%APPDATA%\Requestify` (created if missing) and `%LOCALAPPDATA%\Requestify-updates` (created when used). */
        fun default(): AppPaths = default(System::getenv)

        fun default(env: (String) -> String?, userHome: String = System.getProperty("user.home")): AppPaths {
            fun folder(variable: String, fallback: String): Path =
                env(variable)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(userHome, "AppData", fallback)
            return AppPaths(
                dataDir = folder("APPDATA", "Roaming").resolve("Requestify").createDirectories(),
                updatesDir = folder("LOCALAPPDATA", "Local").resolve("Requestify-updates"),
            )
        }
    }
}
