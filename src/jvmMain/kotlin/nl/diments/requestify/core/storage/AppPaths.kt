package nl.diments.requestify.core.storage

import java.nio.file.Path
import kotlin.io.path.createDirectories

class AppPaths(val dataDir: Path) {
    val settingsFile: Path get() = dataDir.resolve("settings.json")
    val queueFile: Path get() = dataDir.resolve("queue.json")
    val secretsFile: Path get() = dataDir.resolve("secrets.json")
    val blacklistFile: Path get() = dataDir.resolve("blacklist.json")
    val rejectedFile: Path get() = dataDir.resolve("rejected.json")
    val pendingFile: Path get() = dataDir.resolve("pending.json")

    companion object {
        /** `%APPDATA%\Requestify`, created if missing. */
        fun default(): AppPaths {
            val roaming = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming")
            return AppPaths(roaming.resolve("Requestify").createDirectories())
        }
    }
}
