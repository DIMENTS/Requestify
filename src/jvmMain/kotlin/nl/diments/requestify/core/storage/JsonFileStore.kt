package nl.diments.requestify.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** JSON file store that writes atomically and never loses an unreadable file. */
class JsonFileStore<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    fun read(): T {
        if (!Files.exists(path)) return default()
        return try {
            AppJson.instance.decodeFromString(serializer, Files.readString(path))
        } catch (_: SerializationException) {
            quarantine()
            default()
        } catch (_: IllegalArgumentException) {
            quarantine()
            default()
        }
    }

    fun write(value: T) {
        val text = AppJson.instance.encodeToString(serializer, value)
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun quarantine() {
        val target = path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")
        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
