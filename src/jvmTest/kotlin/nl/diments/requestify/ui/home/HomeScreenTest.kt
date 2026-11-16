package nl.diments.requestify.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class HomeScreenTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-home-screen")
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
        Locale.setDefault(originalLocale)
    }

    private object NoCatalog : SpotifyCatalog {
        override suspend fun searchTracks(query: String, limit: Int) = SpotifyResult.Ok(emptyList<Track>())
        override suspend fun track(id: String) = SpotifyResult.Failed("unused")
        override suspend fun isPremium() = SpotifyResult.Ok(true)
    }

    /** A view model over a controller that is never started, so the screen shows a still state. */
    private fun viewModel(): HomeViewModel {
        val controller = PlaybackController(
            player = FakeSpotifyPlayer { 0L },
            store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
            requestsOpen = MutableStateFlow(true),
            paidPriority = { true },
            scope = scope,
            clock = { 0L },
        )
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
        return HomeViewModel(controller, NoCatalog, settings, scope, clock = { 0L })
    }

    private fun androidx.compose.ui.test.ComposeUiTest.nowPlayingIsAboveTheQueue(): Boolean {
        val nowPlaying = onNodeWithText("NOW PLAYING").fetchSemanticsNode().boundsInRoot
        val queue = onNodeWithText("Queue").fetchSemanticsNode().boundsInRoot
        return nowPlaying.bottom <= queue.top
    }

    @Test
    fun layoutFollowsTheSpaceHomeGetsNotTheWindow() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val vm = viewModel()
        var railWidth by mutableStateOf(0.dp)
        setContent {
            Row {
                Box(Modifier.width(railWidth).fillMaxHeight()) // e.g. the navigation rail
                HomeScreen(vm, albumSeed = null)
            }
        }
        assertTrue(!nowPlayingIsAboveTheQueue(), "1180 dp for Home: side by side")

        railWidth = 300.dp // Home gets 880 dp of the 1180 dp window
        waitForIdle()
        assertTrue(nowPlayingIsAboveTheQueue(), "880 dp for Home: Now Playing above the queue")
    }

    @Test
    fun typedTextSurvivesTheLayoutSwitch() = runDesktopComposeUiTest(width = 1180, height = 760) {
        val vm = viewModel()
        var railWidth by mutableStateOf(0.dp)
        setContent {
            Row {
                Box(Modifier.width(railWidth).fillMaxHeight())
                HomeScreen(vm, albumSeed = null)
            }
        }
        onNode(hasSetTextAction()).performTextInput("daft punk")
        railWidth = 300.dp
        waitForIdle()
        assertTrue(nowPlayingIsAboveTheQueue())
        onNodeWithText("daft punk").assertExists()
    }
}
