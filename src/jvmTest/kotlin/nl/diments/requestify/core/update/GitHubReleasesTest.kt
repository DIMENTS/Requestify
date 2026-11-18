package nl.diments.requestify.core.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class GitHubReleasesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/update/$name")) { "missing fixture $name" }.use { it.readBytes().decodeToString() }

    private fun releases(includePrereleases: Boolean = false, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubReleases(HttpClient(MockEngine(handler)), includePrereleases = includePrereleases)

    @Test
    fun findsTheMsiAndChecksumsOfTheLatestRelease() = runBlocking<Unit> {
        val release = assertIs<ReleaseCheck.Found>(releases { respond(fixture("release-latest.json"), HttpStatusCode.OK, json) }.latest()).release
        assertEquals(AppVersion(0, 2, 0), release.version)
        assertEquals("Requestify-0.2.0.msi", release.msi.name)
        assertEquals("https://github.com/DIMENTS/Requestify/releases/download/v0.2.0/Requestify-0.2.0.msi", release.msi.url)
        assertEquals(94_000_000, release.msi.size)
        assertEquals("https://github.com/DIMENTS/Requestify/releases/download/v0.2.0/SHA256SUMS.txt", release.checksums.url)
        assertEquals("## Nieuw\n- Een **welkomstwizard**\n- Updates van GitHub", release.notes)
        assertEquals("https://github.com/DIMENTS/Requestify/releases/tag/v0.2.0", release.pageUrl)
    }

    @Test
    fun sendsTheGitHubHeadersAndNothingPersonal() = runBlocking<Unit> {
        var seen: HttpRequestData? = null
        releases { req -> seen = req; respond(fixture("release-latest.json"), HttpStatusCode.OK, json) }.latest()
        val request = checkNotNull(seen)
        assertEquals("https://api.github.com/repos/DIMENTS/Requestify/releases/latest", request.url.toString())
        assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
        assertEquals("Requestify-updater", request.headers[HttpHeaders.UserAgent], "a generic name: nothing about the streamer")
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.Cookie])
    }

    @Test
    fun aReleaseWithoutTheMsiIsNotUsable() = runBlocking<Unit> {
        val noMsi = fixture("release-latest.json").replace("Requestify-0.2.0.msi", "Something-else.msi")
        assertEquals(ReleaseCheck.NoneUsable, releases { respond(noMsi, HttpStatusCode.OK, json) }.latest())
        val noSums = fixture("release-latest.json").replace("SHA256SUMS.txt", "checksums.txt")
        assertEquals(ReleaseCheck.NoneUsable, releases { respond(noSums, HttpStatusCode.OK, json) }.latest(), "an unverifiable MSI is never used")
    }

    @Test
    fun anOddTagIsNotUsable() = runBlocking<Unit> {
        for (tag in listOf("v1.2", "v1.2.3-beta", "latest", "v0.256.0")) {
            val odd = fixture("release-latest.json").replace("\"v0.2.0\"", "\"$tag\"")
            assertEquals(ReleaseCheck.NoneUsable, releases { respond(odd, HttpStatusCode.OK, json) }.latest(), tag)
        }
    }

    @Test
    fun noReleasesYetIsNotUsable() = runBlocking<Unit> {
        assertEquals(ReleaseCheck.NoneUsable, releases { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, json) }.latest())
    }

    @Test
    fun rateLimitedOrDownIsAFailureNotACrash() = runBlocking<Unit> {
        assertIs<ReleaseCheck.Failed>(releases { respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden, json) }.latest())
        assertIs<ReleaseCheck.Failed>(releases { respond("", HttpStatusCode.InternalServerError) }.latest())
        assertIs<ReleaseCheck.Failed>(releases { throw IOException("no network") }.latest())
        assertIs<ReleaseCheck.Failed>(releases { respond("<html>not json</html>", HttpStatusCode.OK) }.latest())
    }

    @Test
    fun prereleasesOnlyWhenAsked() = runBlocking<Unit> {
        var path: String? = null
        val withTests = releases(includePrereleases = true) { req ->
            path = req.url.encodedPath + "?" + req.url.encodedQuery
            respond(fixture("release-prerelease-list.json"), HttpStatusCode.OK, json)
        }.latest()
        assertEquals("/repos/DIMENTS/Requestify/releases?per_page=10", path)
        assertEquals(AppVersion(0, 2, 2), assertIs<ReleaseCheck.Found>(withTests).release.version, "the newest published one; never a draft")
    }
}
