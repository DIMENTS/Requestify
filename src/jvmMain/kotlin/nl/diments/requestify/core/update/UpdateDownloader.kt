package nl.diments.requestify.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DownloadResult {
    data class Ready(val pending: PendingUpdate) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/**
 * Fetches a release's MSI into the updater's folder and keeps it only when its SHA-256 matches the release's
 * `SHA256SUMS.txt`. One update waits at a time: a newer release replaces an older pending one, and the same version is
 * not fetched again while its file still matches. Nothing here throws (cancellation aside).
 */
class UpdateDownloader(private val http: HttpClient, private val store: UpdateStore) {
    suspend fun download(release: Release): DownloadResult {
        val version = release.version.toString()
        store.pending()?.takeIf { it.version == version }?.let { pending ->
            if (withContext(Dispatchers.IO) { sha256Of(store.dir.resolve(pending.msiFile)) } == pending.sha256) {
                return DownloadResult.Ready(pending)
            }
        }
        val part = store.dir.resolve("${release.msi.name}.part")
        return try {
            val expected = expectedSha256(release) ?: return DownloadResult.Failed("no checksum for ${release.msi.name}")
            val actual = withContext(Dispatchers.IO) {
                Files.createDirectories(store.dir)
                fetch(release.msi.url, part)
            }
            if (actual != expected) {
                withContext(Dispatchers.IO) { Files.deleteIfExists(part) }
                return DownloadResult.Failed("checksum mismatch for ${release.msi.name}")
            }
            val pending = PendingUpdate(version, release.msi.name, actual, release.notes, release.pageUrl)
            withContext(Dispatchers.IO) {
                // The new one replaces whatever waited before it (its file included).
                val kept = Files.move(part, store.dir.resolve("${release.msi.name}.verified"), StandardCopyOption.REPLACE_EXISTING)
                store.clearPending()
                Files.move(kept, store.dir.resolve(release.msi.name), StandardCopyOption.REPLACE_EXISTING)
                store.savePending(pending)
            }
            if (store.pending() == pending) DownloadResult.Ready(pending) else DownloadResult.Failed("could not save the pending update")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(part)
            } catch (_: Exception) {
            }
            DownloadResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** The MSI's checksum from the release's checksum file (`<hex>  <name>`, an optional `*` before the name). */
    private suspend fun expectedSha256(release: Release): String? {
        val response = http.get(release.checksums.url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        check(response.status.isSuccess()) { "checksums: HTTP ${response.status.value}" }
        return response.bodyAsText().lineSequence()
            .mapNotNull { ChecksumLine.matchEntire(it.trim()) }
            .firstOrNull { it.groupValues[2] == release.msi.name }
            ?.groupValues?.get(1)?.lowercase()
    }

    /** Streams [url] to [target] and returns the SHA-256 of what was written. */
    private suspend fun fetch(url: String, target: Path): String = http.prepareGet(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
        .execute { response ->
            check(response.status.isSuccess()) { "download: HTTP ${response.status.value}" }
            val digest = MessageDigest.getInstance("SHA-256")
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            Files.newOutputStream(target).use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            digest.digest().toHex()
        }

    private fun sha256Of(file: Path): String? = try {
        if (!Files.exists(file)) null
        else MessageDigest.getInstance("SHA-256").let { digest ->
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        }
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val USER_AGENT = "Requestify-updater"
        val ChecksumLine = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")
    }
}
