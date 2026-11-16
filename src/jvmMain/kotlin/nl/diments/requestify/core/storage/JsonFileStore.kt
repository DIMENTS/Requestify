package nl.diments.requestify.core.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** JSON file store that writes atomically and durably, and never loses an unreadable file. */
class JsonFileStore<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    fun read(): T {
        val text = try {
            // Strict UTF-8 decode: invalid bytes (e.g. truncation mid character) throw.
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            return default()
        } catch (_: IOException) {
            quarantine()
            return default()
        }
        return try {
            AppJson.instance.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            quarantine()
            default()
        } catch (_: IllegalArgumentException) {
            quarantine()
            default()
        }
    }

    fun write(value: T) {
        val bytes = AppJson.instance.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true) // data must be on disk before the rename can be committed
        }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Best effort: if the file cannot be moved aside (e.g. locked), still fall back to the default. */
    private fun quarantine() {
        val target = path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")
        try {
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
        }
    }
}
