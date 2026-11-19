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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.MinimumWindowSize
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.ArtistRef
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.ui.CollapsedRailWidth
import nl.diments.requestify.ui.window.TitleBarHeight

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
        override suspend fun searchArtists(query: String, limit: Int) = SpotifyResult.Ok(emptyList<ArtistRef>())
        override suspend fun artist(id: String) = SpotifyResult.Failed("unused")
        override suspend fun isPremium() = SpotifyResult.Ok(true)
    }

    /** A view model over a controller that is never started, so the screen shows a still state. */
    private fun viewModel(): HomeViewModel = viewModelWith(controller(FakeSpotifyPlayer { 0L }))

    private fun controller(player: FakeSpotifyPlayer) = PlaybackController(
        player = player,
        store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
        requestsOpen = MutableStateFlow(true),
        paidPriority = { true },
        scope = scope,
        clock = { 0L },
    )

    private fun viewModelWith(controller: PlaybackController): HomeViewModel {
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
        val parts = intakeParts(dir, controller, NoCatalog, settings, scope, clock = { 0L })
        return HomeViewModel(controller, NoCatalog, settings, parts.intake, parts.rejected, scope, clock = { 0L })
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

    // Home at a 1180×760 dp window: the window minus the 96 dp rail and the 40 dp title bar.
    private val wideWidth = 1084.dp
    private val wideHeight = 720.dp

    @Test
    fun queueAndNowPlayingStartOnOneLineInsideThePagePadding() =
        runDesktopComposeUiTest(width = wideWidth.value.toInt(), height = wideHeight.value.toInt()) {
            setContent { HomeScreen(viewModel(), albumSeed = null) }
            val headline = onNodeWithText("Queue").getBoundsInRoot()
            val card = onNodeWithTag(NOW_PLAYING_PANE_TAG).getBoundsInRoot()
            val addField = onNode(hasSetTextAction()).getBoundsInRoot()

            assertEquals(24.dp, headline.left, "from the rail")
            assertEquals(24.dp, headline.top, "from the title bar")
            assertEquals(headline.top, card.top, "the queue headline and Now Playing start on one line")
            assertEquals(wideWidth - 24.dp, card.right, "from the window's right edge")
            assertEquals(wideHeight - 24.dp, card.bottom, "from the window's bottom edge")
            assertEquals(24.dp, card.left - addField.right, "between the panes")
        }

    @Test
    fun narrowHomeKeepsThePagePaddingWithNowPlayingOnTop() = runDesktopComposeUiTest(width = 664, height = 720) {
        setContent { HomeScreen(viewModel(), albumSeed = null) } // a 760 dp window minus the rail
        val headline = onNodeWithText("Queue").getBoundsInRoot()
        val card = onNodeWithTag(NOW_PLAYING_PANE_TAG).getBoundsInRoot()

        assertEquals(24.dp, card.left)
        assertEquals(24.dp, card.top)
        assertEquals(664.dp - 24.dp, card.right)
        assertEquals(24.dp, headline.left)
        assertEquals(card.bottom + 24.dp, headline.top, "between the panes")
    }

    @Test
    fun nowPlayingLeavesNoEmptyBandUnderItsControls() =
        runDesktopComposeUiTest(width = wideWidth.value.toInt(), height = wideHeight.value.toInt()) {
            val player = FakeSpotifyPlayer { 0L }
            player.plays(Track("np", "spotify:track:np", "Midnight City", listOf("M83"), 243_000, false, null), 141_000)
            val controller = controller(player)
            val vm = viewModelWith(controller)
            controller.start()
            setContent { HomeScreen(vm, albumSeed = null) }
            waitUntil(timeoutMillis = 5_000) { vm.state.value.nowPlaying != null }
            waitForIdle()
            val card = onNodeWithTag(NOW_PLAYING_PANE_TAG).getBoundsInRoot()
            val label = onNodeWithText("NOW PLAYING").getBoundsInRoot()
            val title = onNodeWithText("Midnight City").getBoundsInRoot()
            val firstButton = onNodeWithContentDescription("Block").getBoundsInRoot() // the group starts with it
            val skip = onNodeWithContentDescription("Skip").getBoundsInRoot()

            assertTrue(card.bottom - skip.bottom <= 48.dp, "empty band under the controls: ${card.bottom - skip.bottom}")
            assertEquals(label.left, title.left, "the label and the title share one start edge")
            assertEquals(title.left, firstButton.left, "the controls start on that edge too, not centred")
        }

    @Test
    fun previousButtonGoesBackInSpotify() =
        runDesktopComposeUiTest(width = wideWidth.value.toInt(), height = wideHeight.value.toInt()) {
            val player = FakeSpotifyPlayer { 0L }
            player.plays(Track("np", "spotify:track:np", "Midnight City", listOf("M83"), 243_000, false, null), 141_000)
            val controller = controller(player)
            val vm = viewModelWith(controller)
            controller.start()
            setContent { HomeScreen(vm, albumSeed = null) }
            waitUntil(timeoutMillis = 5_000) { vm.state.value.nowPlaying != null }
            controller.stop() // no more polls: the player's calls from here on are the button's alone

            onNodeWithContentDescription("Previous").performClick()
            waitUntil(timeoutMillis = 5_000) { player.callsTo("skipPrevious").size == 1 }
        }

    /** "Song n" asked for in chat by "viewern". */
    private fun chatRequest(n: Int) = Request(
        "r$n",
        Track("t$n", "spotify:track:t$n", "Song $n", listOf("Artist"), 200_000, false, null),
        Requester("viewer$n"),
        RequestSource.CHAT,
        payment = null,
        createdAtEpochMs = n.toLong(),
    )

    // Home at the smallest window: the window minus the rail and the title bar.
    private val smallestWidth = MinimumWindowSize.width - CollapsedRailWidth
    private val smallestHeight = MinimumWindowSize.height - TitleBarHeight

    @Test
    fun atTheSmallestWindowTheQueueUnderNowPlayingShowsAWholeRow() =
        runDesktopComposeUiTest(width = smallestWidth.value.toInt(), height = smallestHeight.value.toInt()) {
            val controller = controller(FakeSpotifyPlayer { 0L })
            val vm = viewModelWith(controller)
            controller.enqueue(chatRequest(1))
            controller.enqueue(chatRequest(2))
            setContent { HomeScreen(vm, albumSeed = null) }
            waitUntil(timeoutMillis = 5_000) { vm.state.value.queue.size == 2 }
            waitForIdle()
            assertTrue(nowPlayingIsAboveTheQueue(), "the narrow layout")

            // The list clips its rows, so a row's bounds are the part of it that shows.
            val firstRow = onAllNodesWithTag(QUEUE_ROW_TAG)[0]
            firstRow.assert(hasAnyDescendant(hasText("Song 1")))
            val row = firstRow.fetchSemanticsNode()
            assertEquals(row.size.height.toFloat(), row.boundsInRoot.height, "the first row is cut off")
        }

    // Home in a maximized Full HD window: the window minus the rail and the title bar.
    private val fullHdWidth = 1920.dp - CollapsedRailWidth
    private val fullHdHeight = 1080.dp - TitleBarHeight

    @Test
    fun onAMaximizedWindowTheQueueStaysReadableWithNowPlayingBesideIt() =
        runDesktopComposeUiTest(width = fullHdWidth.value.toInt(), height = fullHdHeight.value.toInt()) {
            setContent { HomeScreen(viewModel(), albumSeed = null) }
            val headline = onNodeWithText("Queue").getBoundsInRoot()
            val addField = onNode(hasSetTextAction()).getBoundsInRoot()
            val card = onNodeWithTag(NOW_PLAYING_PANE_TAG).getBoundsInRoot()

            assertEquals(24.dp, headline.left, "Home still starts where the pages start")
            assertTrue(addField.right - addField.left <= QueueMaxWidth, "the queue is ${addField.right - addField.left} wide")
            assertEquals(24.dp, card.left - addField.right, "Now Playing sits right beside the queue")
            assertTrue(card.right - card.left > 400.dp, "on a tall window Now Playing grows with it: ${card.right - card.left}")
        }

    @Test
    fun aLongRequesterNameLeavesTheTitleItsRoom() = runDesktopComposeUiTest(width = 904, height = 640) {
        val controller = controller(FakeSpotifyPlayer { 0L })
        val vm = viewModelWith(controller)
        controller.enqueue(chatRequest(1).copy(requester = Requester("Supercalifragilistic_2025")))
        setContent { HomeScreen(vm, albumSeed = null) }
        waitUntil(timeoutMillis = 5_000) { vm.state.value.queue.size == 1 }
        waitForIdle()
        val name = onNodeWithText("Supercalifragilistic_2025", substring = true).getBoundsInRoot()
        assertTrue(name.right - name.left <= RequesterMaxWidth, "the requester takes ${name.right - name.left}")
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
