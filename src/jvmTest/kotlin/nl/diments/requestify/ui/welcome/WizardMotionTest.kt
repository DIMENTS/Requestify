package nl.diments.requestify.ui.welcome

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.settings.WizardStep
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.LocalReducedMotion
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class WizardMotionTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val spotify = MutableStateFlow(false)
    private val twitch = MutableStateFlow(false)
    private val donations = MutableStateFlow(false)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-motion")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteTestDir()
        Locale.setDefault(originalLocale)
    }

    /** The wizard on its Spotify card, with the connections under the test's control. */
    private fun ComposeUiTest.wizardOnSpotify(reducedMotion: Boolean): WelcomeWizardViewModel {
        val graph = AppGraph(AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(), MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope)
        val vm = WelcomeWizardViewModel(graph.settings, spotify, twitch, donations, scope)
        vm.begin()
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                RequestifyTheme(BrandSeed, dark = true) { WelcomeWizard(vm, graph.spotifySetup, graph.twitchSetup, graph.donationsSetup) }
            }
        }
        mainClock.advanceTimeBy(1_000)
        return vm
    }

    private fun ComposeUiTest.exists(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNodeOrNull() != null

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodeOrNull() =
        runCatching { fetchSemanticsNode() }.getOrNull()

    @Test
    fun aConnectionStartsTheCelebration() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val vm = wizardOnSpotify(reducedMotion = false)
        assertEquals(true, exists(WIZARD_SHAPES_TAG), "the soft shapes move behind the card")
        spotify.value = true
        mainClock.advanceTimeBy(300)
        assertEquals(true, exists(WIZARD_CONFETTI_TAG), "confetti")
        assertEquals(true, exists(WIZARD_FLYER_TAG), "the logo on its way to the badges")
        assertEquals(WizardStep.Spotify, vm.state.value.step, "the card stays while it celebrates")
        mainClock.advanceTimeBy(2_500)
        assertEquals(false, exists(WIZARD_CONFETTI_TAG))
        assertEquals(false, exists(WIZARD_FLYER_TAG))
        assertEquals(WizardStep.Twitch, vm.state.value.step, "then the next card")
    }

    @Test
    fun reducedMotionSkipsTheCelebration() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val vm = wizardOnSpotify(reducedMotion = true)
        assertEquals(false, exists(WIZARD_SHAPES_TAG), "nothing moves by itself")
        spotify.value = true
        mainClock.advanceTimeBy(100)
        assertEquals(false, exists(WIZARD_CONFETTI_TAG))
        assertEquals(false, exists(WIZARD_FLYER_TAG))
        assertEquals(WizardStep.Twitch, vm.state.value.step, "straight on to the next card")
    }
}
