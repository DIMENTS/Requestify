package nl.diments.requestify.core.update

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The SHA-256 of [file] in lowercase hex, as `SHA256SUMS.txt` writes it; null when it cannot be read. */
fun sha256Of(file: Path): String? = try {
    if (!Files.exists(file)) {
        null
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
} catch (_: Exception) {
    null
}
