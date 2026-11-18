package nl.diments.requestify.ui.setup

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationCursors
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.donations.donolink.DonoLinkFeed
import nl.diments.requestify.core.donations.donolink.DonoLinkMe
import nl.diments.requestify.core.donations.donolink.DonoLinkPage
import nl.diments.requestify.core.donations.donolink.DonoLinkResult
import nl.diments.requestify.core.donations.streamelements.StreamElementsApi
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class DonationsCardTest {
    private lateinit var dir: Path
    private val originalLocale = Locale.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("requestify-donations-card")
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        scope.cancel()
        dir.deleteRecursively()
    }

    private val now = Instant.parse("2026-11-18T20:00:00Z").toEpochMilli()
    private val day = 24 * 3_600_000L

    private fun vm(expiry: Long?, health: DonationHealth = DonationHealth()) = DonationsSetupViewModel(
        settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext()),
        secrets = InMemorySecretStore(),
        cursors = DonationCursorStore(JsonFileStore(dir.resolve("donations.json"), DonationCursors.serializer()) { DonationCursors() }, inCallerSaveContext()),
        donoLink = object : DonoLinkFeed {
            override suspend fun me(): DonoLinkResult<DonoLinkMe> = DonoLinkResult.Ok(DonoLinkMe("DIMENTS", expiry))
            override suspend fun activity(since: String, cursor: String?): DonoLinkResult<DonoLinkPage> = error("not used")
        },
        streamElements = StreamElementsApi(HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }), jwt = { "t" }),
        health = MutableStateFlow(health),
        scope = scope,
        clock = { now },
        openBrowser = { true },
    )

    @Test
    fun showsOwnerAndExpiry() = runComposeUiTest {
        val vm = vm(expiry = now + 30 * day)
        vm.choose(DonationSourceKind.DonoLink)
        vm.pasteKey("dl_live_abc")
        setContent { RequestifyTheme(BrandSeed, dark = true) { DonationsCard(vm) } }

        onNodeWithText("Linked to DIMENTS", substring = true).assertIsDisplayed()
        onNodeWithText("Key valid until", substring = true).assertIsDisplayed()
    }

    @Test
    fun expiringSoonSaysHowLong() = runComposeUiTest {
        val vm = vm(expiry = now + 6 * day + 1)
        vm.choose(DonationSourceKind.DonoLink)
        vm.pasteKey("dl_live_abc")
        setContent { RequestifyTheme(BrandSeed, dark = true) { DonationsCard(vm) } }

        onNodeWithText("Key expires in 6 days", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRefusedKeySaysANewOneIsNeeded() = runComposeUiTest {
        val vm = vm(expiry = null, health = DonationHealth(DonationSourceKind.DonoLink, DonationStatus.KeyRefused))
        vm.choose(DonationSourceKind.DonoLink)
        setContent { RequestifyTheme(BrandSeed, dark = true) { DonationsCard(vm) } }

        onNodeWithText("DonoLink refused this key", substring = true).assertIsDisplayed()
    }

    @Test
    fun streamElementsWithoutCatchUpSaysSo() = runComposeUiTest {
        val vm = vm(expiry = null)
        setContent { RequestifyTheme(BrandSeed, dark = true) { DonationsCard(vm) } }
        onNodeWithText("StreamElements").performClick()
        waitForIdle()

        onNodeWithText("Tips made while Requestify is off are not picked up.").assertIsDisplayed()
    }
}
