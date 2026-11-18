package nl.diments.requestify.core.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import nl.diments.requestify.core.storage.deleteTestDir

class UpdateDownloaderTest {
    private lateinit var dir: Path
    private val msiDownloads = mutableListOf<String>()

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-download") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(version: String) = Release(
        version = checkNotNull(AppVersion.parse(version)),
        notes = "what is new in $version",
        pageUrl = "https://github.com/DIMENTS/Requestify/releases/tag/v$version",
        msi = ReleaseAsset("Requestify-$version.msi", "https://dl.test/v$version/Requestify-$version.msi", 0),
        checksums = ReleaseAsset("SHA256SUMS.txt", "https://dl.test/v$version/SHA256SUMS.txt", 0),
    )

    private fun msiBytes(version: String) = "MSI for $version".encodeToByteArray()

    /** A release server: the MSI and the checksum file; [sums] overrides the checksum file's text. */
    private fun downloader(store: UpdateStore = UpdateStore(dir), sums: ((String) -> String)? = null) = UpdateDownloader(
        HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val version = path.substringAfter("/v").substringBefore("/")
            when {
                path.endsWith(".msi") -> {
                    msiDownloads += version
                    respond(msiBytes(version), HttpStatusCode.OK)
                }
                path.endsWith("SHA256SUMS.txt") -> respond(
                    sums?.invoke(version)
                        ?: "${sha(msiBytes(version))}  Requestify-$version.msi\n${sha("exe".encodeToByteArray())}  Requestify-Setup-$version.exe\n",
                    HttpStatusCode.OK,
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }),
        store,
    )

    @Test
    fun downloadsAndVerifies() = runBlocking<Unit> {
        val ready = assertIs<DownloadResult.Ready>(downloader().download(release("0.2.0"))).pending
        assertEquals("0.2.0", ready.version)
        assertEquals("Requestify-0.2.0.msi", ready.msiFile)
        assertEquals(sha(msiBytes("0.2.0")), ready.sha256)
        assertEquals("what is new in 0.2.0", ready.notes)
        assertEquals(0, ready.attempts)
        assertContentEquals(msiBytes("0.2.0"), dir.resolve("Requestify-0.2.0.msi").readBytes())
        assertEquals(ready, UpdateStore(dir).pending(), "remembered for the next start")
    }

    @Test
    fun aWrongChecksumDeletesTheFile() = runBlocking<Unit> {
        val result = downloader(sums = { v -> "${"0".repeat(64)}  Requestify-$v.msi" }).download(release("0.2.0"))
        assertIs<DownloadResult.Failed>(result)
        assertEquals(listOf<String>(), dir.listDirectoryEntries().map { it.name }.filter { it.contains(".msi") }, "nothing unverified stays")
        assertNull(UpdateStore(dir).pending())
    }

    @Test
    fun aMissingChecksumLineIsAFailure() = runBlocking<Unit> {
        val result = downloader(sums = { v -> "${sha("x".encodeToByteArray())}  Requestify-Setup-$v.exe" }).download(release("0.2.0"))
        assertIs<DownloadResult.Failed>(result)
        assertEquals(listOf<String>(), msiDownloads, "the MSI is not even fetched")
    }

    @Test
    fun aNewerReleaseReplacesThePendingOne() = runBlocking<Unit> {
        val downloader = downloader()
        downloader.download(release("0.2.0"))
        assertIs<DownloadResult.Ready>(downloader.download(release("0.2.1")))
        assertEquals(listOf("Requestify-0.2.1.msi"), dir.listDirectoryEntries("*.msi").map { it.name })
        assertEquals("0.2.1", UpdateStore(dir).pending()?.version)
    }

    @Test
    fun theSamePendingVersionIsNotDownloadedAgain() = runBlocking<Unit> {
        val downloader = downloader()
        downloader.download(release("0.2.0"))
        assertIs<DownloadResult.Ready>(downloader.download(release("0.2.0")))
        assertEquals(listOf("0.2.0"), msiDownloads)

        dir.resolve("Requestify-0.2.0.msi").writeText("tampered")
        assertIs<DownloadResult.Ready>(downloader.download(release("0.2.0")))
        assertEquals(listOf("0.2.0", "0.2.0"), msiDownloads, "a file that no longer matches is fetched again")
    }

    @Test
    fun aDiskErrorIsAFailureNotACrash() = runBlocking<Unit> {
        val notADir = dir.resolve("a-file")
        notADir.writeText("in the way")
        assertIs<DownloadResult.Failed>(downloader(store = UpdateStore(notADir)).download(release("0.2.0")))
    }
}
