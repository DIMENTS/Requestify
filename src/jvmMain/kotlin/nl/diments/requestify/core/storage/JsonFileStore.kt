package nl.diments.requestify.core.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * The file exists but could not be read, even after a few tries (e.g. another program holds it, or access is
 * denied). Unlike a corrupt file it is left where it is, and nothing may be written over it until a read succeeds.
 */
class StoreReadException(path: Path, cause: IOException) : IOException("Couldn't read $path: $cause", cause)

/**
 * JSON file store that writes atomically and durably, and never loses an unreadable file. A read that fails for
 * another reason than a missing or corrupt file is tried again after each of [retryDelaysMs] ([sleep] waits).
 */
class JsonFileStore<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val retryDelaysMs: List<Long> = listOf(50, 100, 200),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val default: () -> T,
) {
    /** The file's name, for the "can't save" banner and the Log. */
    val fileName: String get() = path.fileName.toString()

    /**
     * The saved value, or the default when there is no file yet. A corrupt file (not UTF-8, or not the expected
     * JSON) is moved aside and the default is returned. Throws [StoreReadException] when the file cannot be read
     * for now: callers must then not write, or they would replace a good file.
     */
    fun read(): T {
        val text = try {
            readWithRetries()
        } catch (_: NoSuchFileException) {
            return default()
        } catch (_: CharacterCodingException) {
            return quarantined()
        }
        return try {
            AppJson.instance.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            quarantined()
        } catch (_: IllegalArgumentException) {
            quarantined()
        }
    }

    /** Strict UTF-8: invalid bytes (e.g. truncation mid character) throw [CharacterCodingException]. */
    private fun readWithRetries(): String {
        var attempt = 0
        while (true) {
            try {
                return Files.readString(path)
            } catch (e: IOException) {
                if (e is NoSuchFileException || e is CharacterCodingException) throw e
                val delay = retryDelaysMs.getOrNull(attempt++) ?: throw StoreReadException(path, e)
                sleep(delay)
            }
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

    /**
     * Keeps a corrupt file as `<name>.corrupt-<time>` and returns the default. When it cannot be moved (e.g. held
     * open by another program) it is copied instead, unless an identical copy is kept already (the file stays in
     * place, so every read would otherwise add one); when copying fails too, the read fails, so nothing replaces it.
     */
    private fun quarantined(): T {
        val target = path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")
        try {
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (moveFailed: IOException) {
            try {
                if (!copyKeptAlready()) Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (copyFailed: IOException) {
                moveFailed.addSuppressed(copyFailed)
                throw StoreReadException(path, moveFailed)
            }
        }
        return default()
    }

    /** True when a `<name>.corrupt-*` sibling holds exactly the bytes of the file. */
    private fun copyKeptAlready(): Boolean {
        val bytes = Files.readAllBytes(path)
        val parent = path.parent ?: return false
        return Files.newDirectoryStream(parent, "${path.fileName}.corrupt-*").use { copies ->
            copies.any { copy -> Files.size(copy) == bytes.size.toLong() && Files.readAllBytes(copy).contentEquals(bytes) }
        }
    }
}
