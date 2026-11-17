package nl.diments.requestify.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class QueuePaneTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    private val blindingLights = Track("t1", "spotify:track:t1", "Blinding Lights", listOf("The Weeknd"), 200_000, false, null)
    private val r1 = Request("r1", blindingLights, Requester("kaasboer"), RequestSource.DONOLINK, Payment.Money(500, "EUR"), createdAtEpochMs = 1L)

    private fun stateWith(queue: List<Request>, upcoming: List<Track> = emptyList()) = HomeUiState(
        queue = queue,
        queueDurationMs = queue.sumOf { it.track.durationMs },
        history = emptyList(),
        nowPlaying = null,
        health = SpotifyHealth.Ok,
        results = emptyList(),
        addMessage = null,
        upcoming = upcoming,
    )

    /** "Song n" (3:20) asked for in chat by "viewern". */
    private fun chatRequest(n: Int): Request {
        val track = Track("t$n", "spotify:track:t$n", "Song $n", listOf("Artist"), 200_000, false, null)
        return Request("r$n", track, Requester("viewer$n"), RequestSource.CHAT, null, createdAtEpochMs = n.toLong())
    }

    /** What Spotify itself plays after the requests: "Spotify n" by "Band n", 3:01, 3:02, … long. */
    private fun spotifyTracks(count: Int) = (1..count).map {
        Track("s$it", "spotify:track:s$it", "Spotify $it", listOf("Band $it"), 180_000L + it * 1_000L, false, null)
    }

    @Composable
    private fun Pane(state: HomeUiState, onMove: (String, Int) -> Unit = { _, _ -> }) {
        RequestifyTheme(BrandSeed, dark = true) {
            QueuePane(
                state = state,
                onAddInput = {},
                onAddSubmit = { false },
                onAddTrack = {},
                onRemove = {},
                onMove = onMove,
            )
        }
    }

    private fun ComposeUiTest.top(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    /** Presses at [start], moves to [end] in small steps like a real mouse, and lets go. */
    private fun ComposeUiTest.drag(start: Offset, end: Offset) {
        val step = (end - start) / 20f
        onRoot().performMouseInput {
            moveTo(start)
            press()
        }
        repeat(20) {
            onRoot().performMouseInput { moveBy(step) }
            mainClock.advanceTimeBy(16)
        }
        onRoot().performMouseInput { release() }
        waitForIdle()
    }

    @Test
    fun rowShowsTheRequestAndRemovesIt() = runComposeUiTest {
        var removed: String? = null
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                QueuePane(
                    state = stateWith(listOf(r1)),
                    onAddInput = {},
                    onAddSubmit = { false },
                    onAddTrack = {},
                    onRemove = { removed = it },
                    onMove = { _, _ -> },
                )
            }
        }
        onNodeWithText("Blinding Lights").assertIsDisplayed()
        onNodeWithText("The Weeknd").assertIsDisplayed()
        onNodeWithText("3:20").assertIsDisplayed()
        onNodeWithText("DonoLink €5").assertIsDisplayed()
        onNodeWithText("kaasboer").assertIsDisplayed()

        onNodeWithContentDescription("Remove Blinding Lights").performClick()
        assertEquals("r1", removed)
    }

    @Test
    fun spotifysStatusIsLeftToTheTitleBar() = runComposeUiTest {
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                QueuePane(
                    state = stateWith(listOf(r1)),
                    onAddInput = {},
                    onAddSubmit = { false },
                    onAddTrack = {},
                    onRemove = {},
                    onMove = { _, _ -> },
                )
            }
        }
        onNodeWithText("Queue").assertIsDisplayed()
        onNodeWithText("Spotify connected").assertDoesNotExist()
    }

    @Test
    fun aQueueThatCannotBeReadSaysSo() = runComposeUiTest {
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                QueuePane(
                    state = stateWith(emptyList()).copy(loadFailing = true),
                    onAddInput = {},
                    onAddSubmit = { false },
                    onAddTrack = {},
                    onRemove = {},
                    onMove = { _, _ -> },
                )
            }
        }
        onNodeWithText("Couldn’t read the saved queue — retrying").assertIsDisplayed()
    }

    @Test
    fun draggedRowIsMovedOnceWhenDropped() = runComposeUiTest {
        val queue = (1..3).map {
            val track = Track("t$it", "spotify:track:t$it", "Song $it", listOf("Artist"), 200_000, false, null)
            Request("r$it", track, Requester("viewer$it"), RequestSource.CHAT, payment = null, createdAtEpochMs = it.toLong())
        }
        val moves = mutableListOf<Pair<String, Int>>()
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                QueuePane(
                    state = stateWith(queue),
                    onAddInput = {},
                    onAddSubmit = { false },
                    onAddTrack = {},
                    onRemove = {},
                    onMove = { id, to -> moves += id to to },
                )
            }
        }
        // Drag the third row's handle up past the first row, in small steps like a real mouse.
        val handles = onAllNodesWithContentDescription("Drag to reorder")
        val start = handles[2].fetchSemanticsNode().boundsInRoot.center
        val firstRow = handles[0].fetchSemanticsNode().boundsInRoot.center
        val step = Offset(0f, (firstRow.y - start.y - 10f) / 20)

        onRoot().performMouseInput {
            moveTo(start)
            press()
        }
        repeat(20) {
            onRoot().performMouseInput { moveBy(step) }
            mainClock.advanceTimeBy(16)
        }
        assertTrue(moves.isEmpty(), "the move is committed on drop, not while dragging")
        onRoot().performMouseInput { release() }
        assertEquals(listOf("r3" to 0), moves)
    }

    @Test
    fun mergedListShowsRequestsThenSpotifyInOrder() = runDesktopComposeUiTest(width = 800, height = 900) {
        setContent { Pane(stateWith(listOf(chatRequest(1), chatRequest(2)), upcoming = spotifyTracks(3))) }

        val order = listOf("Song 1", "Song 2", "Then in Spotify", "Spotify 1", "Spotify 2", "Spotify 3")
        val tops = order.map { top(it) }
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b }, "top to bottom: $order, but the tops are $tops")

        onAllNodesWithText("Your music").assertCountEquals(3)
        onAllNodesWithContentDescription("Remove", substring = true).assertCountEquals(2)
        onNodeWithContentDescription("Remove Song 1").assertIsDisplayed()
        onNodeWithContentDescription("Remove Song 2").assertIsDisplayed()
        onAllNodesWithContentDescription("Drag to reorder").assertCountEquals(2)
        onNodeWithText("Queue · 2").assertIsDisplayed()
        onNodeWithText("Total 6:40").assertIsDisplayed() // the requests' total only
    }

    @Test
    fun spotifyRowsAlignWithRequestRows() = runDesktopComposeUiTest(width = 800, height = 900) {
        setContent { Pane(stateWith(listOf(chatRequest(1)), upcoming = spotifyTracks(1))) }

        val requestTitle = onNodeWithText("Song 1").getBoundsInRoot()
        val spotifyTitle = onNodeWithText("Spotify 1").getBoundsInRoot()
        assertEquals(requestTitle.left, spotifyTitle.left, "titles start at the same x")

        val requestDuration = onNodeWithText("3:20").getBoundsInRoot()
        val spotifyDuration = onNodeWithText("3:01").getBoundsInRoot()
        assertEquals(requestDuration.right, spotifyDuration.right, "durations end at the same x")

        val requestChip = onNodeWithText("!sr").getBoundsInRoot()
        val spotifyChip = onNodeWithText("Your music").getBoundsInRoot()
        assertEquals(requestChip.right, spotifyChip.right, "chips end at the same x")
    }

    @Test
    fun thenInSpotifyHiddenWhenEmpty() = runDesktopComposeUiTest(width = 800, height = 900) {
        var state by mutableStateOf(stateWith(listOf(chatRequest(1)), upcoming = spotifyTracks(2)))
        setContent { Pane(state) }
        onNodeWithText("Then in Spotify").assertIsDisplayed()

        state = stateWith(listOf(chatRequest(1))) // Spotify has nothing lined up
        waitForIdle()
        onNodeWithText("Then in Spotify").assertDoesNotExist()
        onNodeWithText("Spotify 1").assertDoesNotExist()
        onNodeWithText("Your music").assertDoesNotExist()
        onNodeWithText("Song 1").assertIsDisplayed()

        state = stateWith(emptyList())
        waitForIdle()
        onNodeWithText("The queue is empty. New requests show up here.").assertIsDisplayed()
    }

    @Test
    fun anEmptyQueueStillShowsWhatSpotifyPlaysNext() = runDesktopComposeUiTest(width = 800, height = 900) {
        setContent { Pane(stateWith(emptyList(), upcoming = spotifyTracks(2)).copy(handedOver = chatRequest(1))) }

        val order = listOf("Up next in Spotify", "Song 1", "Then in Spotify", "Spotify 1", "Spotify 2")
        val tops = order.map { top(it) }
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b }, "top to bottom: $order, but the tops are $tops")
        onNodeWithText("The queue is empty. New requests show up here.").assertDoesNotExist()
    }

    @Test
    fun upcomingRowsAreNotDraggable() = runDesktopComposeUiTest(width = 800, height = 900) {
        val moves = mutableListOf<Pair<String, Int>>()
        val state = stateWith(listOf(chatRequest(1), chatRequest(2)), upcoming = spotifyTracks(3))
        setContent { Pane(state) { id, to -> moves += id to to } }

        // Where a request's drag handle is, but on the last of Spotify's rows; dragged up past every request.
        val handleX = onAllNodesWithContentDescription("Drag to reorder")[0].fetchSemanticsNode().boundsInRoot.center.x
        val start = Offset(handleX, onNodeWithText("Spotify 3").fetchSemanticsNode().boundsInRoot.center.y)
        drag(start, Offset(handleX, top("Song 1") - 10f))

        assertTrue(moves.isEmpty(), "moved: $moves")
        val order = listOf("Song 1", "Song 2", "Then in Spotify", "Spotify 1", "Spotify 2", "Spotify 3")
        assertTrue(order.map { top(it) }.zipWithNext().all { (a, b) -> a < b }, "nothing moved on screen either")
    }

    @Test
    fun aRequestIsNeverDroppedAmongSpotifysTracks() = runDesktopComposeUiTest(width = 800, height = 900) {
        val moves = mutableListOf<Pair<String, Int>>()
        val state = stateWith(listOf(chatRequest(1), chatRequest(2)), upcoming = spotifyTracks(3))
        setContent { Pane(state) { id, to -> moves += id to to } }

        // The first request's handle, dragged down past every one of Spotify's rows.
        val handle = onAllNodesWithContentDescription("Drag to reorder")[0].fetchSemanticsNode().boundsInRoot.center
        val below = onNodeWithText("Spotify 3").fetchSemanticsNode().boundsInRoot.bottom + 10f
        drag(handle, Offset(handle.x, below))

        assertEquals(listOf("r1" to 1), moves, "the last place among the requests, never past them")
    }
}
