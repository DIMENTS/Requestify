package nl.diments.requestify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.ui.rules.Stepper
import nl.diments.requestify.ui.setup.DonationsCard
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.LocalReducedMotion
import nl.diments.requestify.ui.theme.RequestifyTheme

/**
 * The polished controls: a stepper's number rolls to the next one, and the donations setup grows open when a platform
 * is chosen; with Windows' animation effects off both just change, in place and at their full size.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class PolishMotionTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-polish")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    /** Where [text] is a moment after [change], and once it has settled. */
    private fun ComposeUiTest.duringAndAfter(change: () -> Unit, measure: ComposeUiTest.() -> DpRect): Pair<DpRect, DpRect> {
        waitForIdle()
        mainClock.autoAdvance = false
        change()
        mainClock.advanceTimeBy(64)
        val during = measure()
        mainClock.advanceTimeBy(1_000)
        return during to measure()
    }

    private fun stepperNumber(reduced: Boolean): Pair<DpRect, DpRect> {
        lateinit var result: Pair<DpRect, DpRect>
        runDesktopComposeUiTest(width = 300, height = 100) {
            var value by mutableStateOf(25)
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    RequestifyTheme(BrandSeed, dark = true) {
                        Stepper("$value", true, true, onDecrease = { value-- }, onIncrease = { value++ }, "Decrease", "Increase")
                    }
                }
            }
            result = duringAndAfter({ value = 26 }) { onNodeWithText("26").getBoundsInRoot() }
        }
        return result
    }

    @Test
    fun aStepperRollsToItsNextNumber() {
        val (during, after) = stepperNumber(reduced = false)
        assertNotEquals(after, during, "the new number slides in")
    }

    @Test
    fun withAnimationEffectsOffAStepperJustShowsItsNextNumber() {
        val (during, after) = stepperNumber(reduced = true)
        assertEquals(after, during)
    }

    private fun donationsCard(reduced: Boolean): Pair<DpRect, DpRect> {
        lateinit var result: Pair<DpRect, DpRect>
        runDesktopComposeUiTest(width = 760, height = 900) {
            val graph = AppGraph(
                AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
                MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope,
            )
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    RequestifyTheme(BrandSeed, dark = true) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Box(Modifier.testTag("card")) { DonationsCard(graph.donationsSetup) }
                        }
                    }
                }
            }
            result = duringAndAfter({ graph.donationsSetup.choose(DonationSourceKind.DonoLink) }) { onNodeWithTag("card").getBoundsInRoot() }
        }
        return result
    }

    @Test
    fun choosingADonationPlatformGrowsItsSetupOpen() {
        val (during, after) = donationsCard(reduced = false)
        assertNotEquals(after, during, "the card grows instead of jumping to its new height")
    }

    @Test
    fun withAnimationEffectsOffTheDonationSetupIsThereAtOnce() {
        val (during, after) = donationsCard(reduced = true)
        assertEquals(after, during)
    }
}
