package nl.diments.requestify.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
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

    private fun stateWith(queue: List<Request>) = HomeUiState(
        queue = queue,
        queueDurationMs = queue.sumOf { it.track.durationMs },
        history = emptyList(),
        nowPlaying = null,
        health = SpotifyHealth.Ok,
        results = emptyList(),
        addMessage = null,
    )

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
}
