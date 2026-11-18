package nl.diments.requestify.ui.setup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.donations.DonationCursor
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationCursors
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.donolink.DonoLinkFeed
import nl.diments.requestify.core.donations.donolink.DonoLinkMe
import nl.diments.requestify.core.donations.donolink.DonoLinkPage
import nl.diments.requestify.core.donations.donolink.DonoLinkResult
import nl.diments.requestify.core.donations.streamelements.StreamElementsApi
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.chat.awaitUntil

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class DonationsSetupViewModelTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-donations-setup") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val now = Instant.parse("2026-11-18T20:00:00Z").toEpochMilli()
    private val expiry = Instant.parse("2026-12-06T12:00:00Z").toEpochMilli()

    private val secrets = InMemorySecretStore()
    private var restarts = 0
    private val donoLink = object : DonoLinkFeed {
        var answer: DonoLinkResult<DonoLinkMe> = DonoLinkResult.Ok(DonoLinkMe("DIMENTS", expiry))
        var calls = 0
        override suspend fun me(): DonoLinkResult<DonoLinkMe> = answer.also { calls++ }
        override suspend fun activity(since: String, cursor: String?): DonoLinkResult<DonoLinkPage> = error("not used")
    }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val streamElements = StreamElementsApi(
        HttpClient(MockEngine { respond("""{"_id":"ch1","username":"diments","displayName":"DIMENTS"}""", HttpStatusCode.OK, json) }),
        jwt = { secrets.get(SecretKeys.STREAMELEMENTS_JWT) },
    )

    private fun settings() = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
    private fun cursors() = DonationCursorStore(JsonFileStore(dir.resolve("donations.json"), DonationCursors.serializer()) { DonationCursors() })

    private fun TestScope.vm(settings: SettingsStore = settings(), cursors: DonationCursorStore = cursors()) = DonationsSetupViewModel(
        settings = settings,
        secrets = secrets,
        cursors = cursors,
        donoLink = donoLink,
        streamElements = streamElements,
        health = MutableStateFlow(DonationHealth()),
        scope = backgroundScope,
        clock = { now },
        openBrowser = { true },
        restartSource = { restarts++ },
    )

    @Test
    fun switchingOnStartsAtNow() = runTest {
        val cursors = cursors()
        cursors.save(DonationSourceKind.DonoLink, DonationCursor("2026-11-01T00:00:00Z", setOf("old")))
        val settings = settings()
        val vm = vm(settings, cursors)

        vm.choose(DonationSourceKind.DonoLink)

        assertEquals(DonationSourceKind.DonoLink, settings.settings.value.donations.source)
        assertEquals(DonationCursor(Instant.ofEpochMilli(now).toString()), cursors.get(DonationSourceKind.DonoLink))
        vm.choose(DonationSourceKind.DonoLink) // the same again: no new start
        assertEquals(DonationCursor(Instant.ofEpochMilli(now).toString()), cursors.get(DonationSourceKind.DonoLink))
    }

    @Test
    fun aPastedKeyIsSavedAndTested() = runTest {
        val vm = vm()
        vm.choose(DonationSourceKind.DonoLink)
        vm.pasteKey("  dl_live_abc  ")
        awaitUntil("the test") { vm.state.value.test is DonationTest.Ok }

        assertEquals("dl_live_abc", secrets.get(SecretKeys.DONOLINK_KEY))
        assertEquals(DonationTest.Ok("DIMENTS", expiry), vm.state.value.test)
        assertTrue(vm.state.value.hasKey)

        donoLink.answer = DonoLinkResult.KeyRefused(401)
        vm.pasteKey("dl_live_wrong")
        awaitUntil("the refusal") { vm.state.value.test == DonationTest.Refused }
    }

    @Test
    fun aPastedJwtIsSavedAndTested() = runTest {
        val vm = vm()
        vm.choose(DonationSourceKind.StreamElements)
        vm.pasteKey("eyJ.jwt.sig")
        awaitUntil("the test") { vm.state.value.test is DonationTest.Ok }

        assertEquals("eyJ.jwt.sig", secrets.get(SecretKeys.STREAMELEMENTS_JWT))
        assertEquals(DonationTest.Ok("DIMENTS", null), vm.state.value.test)
    }

    @Test
    fun theOtherTokenStaysWhenSwitching() = runTest {
        secrets.put(SecretKeys.STREAMELEMENTS_JWT, "se-token")
        val vm = vm()
        vm.choose(DonationSourceKind.StreamElements)
        vm.choose(DonationSourceKind.DonoLink)
        vm.choose(DonationSourceKind.None)

        assertEquals("se-token", secrets.get(SecretKeys.STREAMELEMENTS_JWT))
    }

    @Test
    fun aMinimumBelowOneCentIsRefused() = runTest {
        val settings = settings()
        val vm = vm(settings)
        vm.choose(DonationSourceKind.DonoLink)

        assertTrue(vm.setMinimum(250))
        assertEquals(250, settings.settings.value.donations.donoLinkMinimumCents)
        assertFalse(vm.setMinimum(0))
        assertEquals(250, settings.settings.value.donations.donoLinkMinimumCents)

        vm.choose(DonationSourceKind.StreamElements)
        assertTrue(vm.setMinimum(500))
        assertEquals(500, settings.settings.value.donations.streamElementsMinimumCents)
        assertEquals(250, settings.settings.value.donations.donoLinkMinimumCents, "each source keeps its own")
    }

    @Test
    fun pastingAKeyRestartsTheSource() = runTest {
        val vm = vm()
        vm.choose(DonationSourceKind.DonoLink)
        val before = restarts
        vm.pasteKey("dl_live_new")
        assertEquals(before + 1, restarts, "a new key takes effect at once, not at the next app start")
    }
}
