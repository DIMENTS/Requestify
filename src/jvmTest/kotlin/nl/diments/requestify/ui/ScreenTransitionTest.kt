package nl.diments.requestify.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.diments.requestify.core.secrets.InMemorySecretStore
import nl.diments.requestify.core.storage.AppPaths
import nl.diments.requestify.ui.theme.LocalReducedMotion

/** Moving between the rail's screens: a fade through the background, and with animation effects off no zoom. */
@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class ScreenTransitionTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-screen-transition")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    private fun graph() = nl.diments.requestify.AppGraph(
        AppPaths(dir, updatesDir = dir.resolve("updates")), InMemorySecretStore(),
        MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }, scope,
    )

    @Test
    fun withAnimationEffectsOffANewScreenIsInPlaceAtOnce() = runDesktopComposeUiTest(width = 1180, height = 760) {
        var open by mutableStateOf<Destination?>(Destination.Rules)
        setContent { CompositionLocalProvider(LocalReducedMotion provides true) { AppScaffold(graph(), openOn = open) } }
        waitForIdle()
        mainClock.autoAdvance = false
        open = Destination.Log
        mainClock.advanceTimeBy(100)
        val subtitle = "What went right or wrong, and why. Kept for 30 days."
        val early = onNodeWithText(subtitle).getBoundsInRoot()
        mainClock.advanceTimeBy(1_000)
        assertEquals(onNodeWithText(subtitle).getBoundsInRoot(), early)
    }

    @Test
    fun withAnimationEffectsOnANewScreenGrowsIntoPlace() = runDesktopComposeUiTest(width = 1180, height = 760) {
        var open by mutableStateOf<Destination?>(Destination.Rules)
        setContent { AppScaffold(graph(), openOn = open) }
        waitForIdle()
        mainClock.autoAdvance = false
        open = Destination.Log
        mainClock.advanceTimeBy(100)
        val subtitle = "What went right or wrong, and why. Kept for 30 days."
        val early = onNodeWithText(subtitle).getBoundsInRoot()
        mainClock.advanceTimeBy(1_000)
        assertTrue(early != onNodeWithText(subtitle).getBoundsInRoot(), "the screen scales in from 96 %")
    }
}
