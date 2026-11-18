package nl.diments.requestify.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A file attached to a release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** A release Requestify can update to: its MSI and the checksums that verify it. [notes] is the release text (Markdown). */
data class Release(val version: AppVersion, val notes: String, val pageUrl: String, val msi: ReleaseAsset, val checksums: ReleaseAsset)

sealed interface ReleaseCheck {
    data class Found(val release: Release) : ReleaseCheck

    /** No release yet, or the newest one cannot be used: an odd tag, or its MSI or `SHA256SUMS.txt` is missing. */
    data object NoneUsable : ReleaseCheck

    /** GitHub could not be asked (no network, rate limited, down, an odd answer); try again later. */
    data class Failed(val reason: String) : ReleaseCheck
}

/**
 * The newest Requestify release on GitHub. Only published releases count: `releases/latest` leaves out drafts and
 * pre-releases. [includePrereleases] (the developer's own testing) reads the last ten and takes the newest published
 * one, pre-releases included. Sends nothing about the streamer: no login, a generic user agent.
 */
class GitHubReleases(
    private val http: HttpClient,
    private val repo: String = "DIMENTS/Requestify",
    private val includePrereleases: Boolean = false,
) {
    suspend fun latest(): ReleaseCheck = try {
        if (includePrereleases) newestOf(get("releases?per_page=10") ?: return ReleaseCheck.NoneUsable)
        else get("releases/latest")?.let(::single) ?: ReleaseCheck.NoneUsable
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReleaseCheck.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    /** The answer's body; null when there is no release (404). Any other failure throws. */
    private suspend fun get(path: String): String? {
        val response = http.get("https://api.github.com/repos/$repo/$path") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "Requestify-updater")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "GitHub answered ${response.status.value}" }
        return response.bodyAsText()
    }

    private fun single(body: String): ReleaseCheck =
        Lenient.decodeFromString(GitHubRelease.serializer(), body).usable()?.let(ReleaseCheck::Found) ?: ReleaseCheck.NoneUsable

    private fun newestOf(body: String): ReleaseCheck =
        Lenient.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
            .filterNot { it.draft }
            .mapNotNull { it.usable() }
            .maxByOrNull { it.version }
            ?.let(ReleaseCheck::Found) ?: ReleaseCheck.NoneUsable

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    ) {
        /** This release as Requestify can use it: a version tag, its MSI and the checksums; else null. */
        fun usable(): Release? {
            val version = AppVersion.parse(tagName) ?: return null
            fun asset(name: String) = assets.firstOrNull { it.name == name }?.let { ReleaseAsset(it.name, it.url, it.size) }
            return Release(
                version = version,
                notes = body.orEmpty(),
                pageUrl = htmlUrl,
                msi = asset("Requestify-$version.msi") ?: return null,
                checksums = asset(CHECKSUMS_FILE) ?: return null,
            )
        }
    }

    @Serializable
    private data class GitHubAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    companion object {
        /** The release's checksum file: lines of `<sha-256 in lowercase hex>  <file name>`. */
        const val CHECKSUMS_FILE = "SHA256SUMS.txt"

        private val Lenient = Json { ignoreUnknownKeys = true }
    }
}
