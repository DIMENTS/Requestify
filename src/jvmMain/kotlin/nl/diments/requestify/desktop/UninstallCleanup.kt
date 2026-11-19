package nl.diments.requestify.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.AppJson

/** How the uninstaller found Requestify's autostart entry before it removed it. Names are stable. */
@Serializable
enum class RemovedAutostart {
    @SerialName("on") On,
    @SerialName("disabled_in_task_manager") DisabledInTaskManager,
}

/** `autostart-removed.json` in the data folder: the uninstaller removed this entry, so a reinstall may put it back. */
@Serializable
data class AutostartRemovedMarker(val state: RemovedAutostart)

private const val MARKER_FILE = "autostart-removed.json"

/**
 * Run by the uninstaller (`Requestify.exe --uninstall-cleanup`, never during an upgrade): removes the autostart entry
 * with Task Manager's switch, and leaves a note of how it was in [dataDir], which the uninstaller keeps. A reinstall
 * puts it back from that note ([restoreAutostartAfterReinstall]). Without an entry nothing is noted. The downloaded
 * updates ([updatesDir], up to ~90 MB) go too. It never throws: an uninstall must not fail over this.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
fun cleanUpForUninstall(registry: RegistryPort, dataDir: Path, updatesDir: Path? = null) {
    if (updatesDir != null) {
        try {
            updatesDir.deleteRecursively()
        } catch (_: Throwable) {
            // best effort: what stays is only disk space
        }
    }
    val state = try {
        registry.getString(RUN_KEY, AUTOSTART_VALUE_NAME) ?: return
        if (isDisabledInTaskManager(registry.getBinary(APPROVED_KEY, AUTOSTART_VALUE_NAME))) {
            RemovedAutostart.DisabledInTaskManager
        } else {
            RemovedAutostart.On
        }
    } catch (_: Throwable) {
        return
    }
    try {
        Files.createDirectories(dataDir)
        val tmp = dataDir.resolve("$MARKER_FILE.tmp")
        Files.writeString(tmp, AppJson.instance.encodeToString(AutostartRemovedMarker.serializer(), AutostartRemovedMarker(state)))
        Files.move(tmp, dataDir.resolve(MARKER_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Throwable) {
        // the entry still goes: a Run value that points at a removed program is worse than a lost note
    }
    for (key in listOf(RUN_KEY, APPROVED_KEY)) {
        try {
            registry.delete(key, AUTOSTART_VALUE_NAME)
        } catch (_: Throwable) {
        }
    }
}

/**
 * At start, before the autostart choice is applied: an entry the uninstaller removed ([cleanUpForUninstall]) comes back
 * exactly as it was, once. Only an installed build restores (under `gradlew run` the note waits). A note that cannot
 * be read is dropped; one whose restore Windows refused is kept for the next start. It never throws.
 */
fun restoreAutostartAfterReinstall(dataDir: Path, autostart: Autostart) {
    val file = dataDir.resolve(MARKER_FILE)
    try {
        if (!Files.exists(file) || autostart.state() == AutostartState.Unavailable) return
        val marker = try {
            AppJson.instance.decodeFromString(AutostartRemovedMarker.serializer(), Files.readString(file))
        } catch (_: Exception) {
            null
        }
        if (marker != null && !autostart.restore(disabledInTaskManager = marker.state == RemovedAutostart.DisabledInTaskManager)) return
        Files.deleteIfExists(file)
    } catch (_: Throwable) {
    }
}
