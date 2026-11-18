package nl.diments.requestify.core.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.AppJson

/**
 * A downloaded, verified update waiting to be installed. [attempts]: installs tried so far; [from]: the version that
 * started the last attempt (so the next start can say "updated from … to …"). Names are stable.
 */
@Serializable
data class PendingUpdate(
    val version: String,
    val msiFile: String,
    val sha256: String,
    val notes: String,
    val pageUrl: String,
    val attempts: Int = 0,
    val from: String? = null,
)

/** Requestify was just updated: the "Bijgewerkt" card shows this until the streamer closes it. Names are stable. */
@Serializable
data class JustUpdated(val from: String, val to: String, val notes: String)

/**
 * The updater's notes in its own folder ([dir], `%LOCALAPPDATA%\Requestify-updates`): the pending update with its
 * MSI, the install helper's exit code (`install-exit-code.txt`) and the "just updated" note. Nothing here throws: a
 * file that cannot be read reads as nothing, and a failed write is the same as no note (the next check tries again).
 */
class UpdateStore(val dir: Path) {
    private val pendingFile get() = dir.resolve("pending.json")
    private val justUpdatedFile get() = dir.resolve("just-updated.json")

    /** Written by the install helper (`desktop/UpdateLauncher`) once `msiexec` ends. */
    val exitCodeFile: Path get() = dir.resolve("install-exit-code.txt")

    fun pending(): PendingUpdate? = read(pendingFile, PendingUpdate.serializer())

    fun savePending(p: PendingUpdate) = write(pendingFile, PendingUpdate.serializer(), p)

    /** Forgets the pending update and deletes every downloaded installer (whole or partial). */
    fun clearPending() {
        delete(pendingFile)
        try {
            Files.newDirectoryStream(dir).use { files ->
                files.filter { it.fileName.toString().let { n -> n.endsWith(".msi") || n.endsWith(".msi.part") } }.forEach(::delete)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Counts one more install attempt of the pending update, started by version [from], and returns it; null when
     * nothing is pending.
     */
    fun markAttempt(from: String? = null): PendingUpdate? =
        pending()?.let { it.copy(attempts = it.attempts + 1, from = from ?: it.from) }?.also(::savePending)

    fun lastExitCode(): Int? = try {
        if (Files.exists(exitCodeFile)) Files.readString(exitCodeFile).trim().toIntOrNull() else null
    } catch (_: Exception) {
        null
    }

    fun clearExitCode() = delete(exitCodeFile)

    fun justUpdated(): JustUpdated? = read(justUpdatedFile, JustUpdated.serializer())

    fun saveJustUpdated(j: JustUpdated) = write(justUpdatedFile, JustUpdated.serializer(), j)

    fun clearJustUpdated() = delete(justUpdatedFile)

    private fun <T> read(file: Path, serializer: KSerializer<T>): T? = try {
        if (Files.exists(file)) AppJson.instance.decodeFromString(serializer, Files.readString(file)) else null
    } catch (_: Exception) {
        null
    }

    private fun <T> write(file: Path, serializer: KSerializer<T>, value: T) {
        try {
            Files.createDirectories(dir)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, AppJson.instance.encodeToString(serializer, value))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
        }
    }

    private fun delete(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (_: Exception) {
        }
    }
}
