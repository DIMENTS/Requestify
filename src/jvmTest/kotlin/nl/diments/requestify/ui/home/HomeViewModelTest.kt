package nl.diments.requestify.ui.home

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import nl.diments.requestify.core.spotify.SpotifyCatalog
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class HomeViewModelTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-home") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun track(id: String) = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null)

    private fun request(id: String) = Request(id, track("t-$id"), Requester("Viewer $id"), RequestSource.CHAT, null, createdAtEpochMs = 1L)

    private class FakeCatalog : SpotifyCatalog {
        val searches = mutableListOf<Pair<String, Int>>()
        val lookups = mutableListOf<String>()
        var searchResult: SpotifyResult<List<Track>> = SpotifyResult.Ok(emptyList())

        override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> {
            searches += query to limit
            return searchResult
        }

        override suspend fun track(id: String): SpotifyResult<Track> {
            lookups += id
            return SpotifyResult.Ok(Track(id, "spotify:track:$id", "Linked", listOf("Artist"), 180_000, false, null))
        }

        override suspend fun isPremium(): SpotifyResult<Boolean?> = SpotifyResult.Ok(true)
    }

    private class Home(val vm: HomeViewModel, val controller: PlaybackController, val catalog: FakeCatalog)

    private fun TestScope.home(catalog: FakeCatalog = FakeCatalog()): Home {
        val controller = PlaybackController(
            player = FakeSpotifyPlayer { testScheduler.currentTime },
            store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
            requestsOpen = MutableStateFlow(true),
            paidPriority = { true },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
        var ids = 0
        val vm = HomeViewModel(
            controller = controller,
            catalog = catalog,
            settings = settings,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            newId = { "new-${++ids}" },
        )
        return Home(vm, controller, catalog)
    }

    /** Lets any search debounce pass and runs everything due, background work included (unlike advanceUntilIdle). */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun linkResolvesTrackWithoutSearch() = runTest {
        val home = home()
        home.vm.onAddInput("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc")
        settle()
        assertEquals(listOf("4cOdK2wGLETKBW3PvgPWqT"), home.catalog.lookups)
        assertTrue(home.catalog.searches.isEmpty())
        assertEquals(listOf("4cOdK2wGLETKBW3PvgPWqT"), home.vm.state.value.results.map { it.id })
        assertNull(home.vm.state.value.addMessage)
    }

    @Test
    fun textSearchIsDebounced() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("daft")))
        home.vm.onAddInput("d")
        advanceTimeBy(100)
        home.vm.onAddInput("da")
        advanceTimeBy(100)
        home.vm.onAddInput("daft")
        advanceTimeBy(299)
        runCurrent()
        assertTrue(home.catalog.searches.isEmpty())

        settle()
        assertEquals(listOf("daft" to 5), home.catalog.searches)
        assertEquals(listOf(track("daft")), home.vm.state.value.results)
    }

    @Test
    fun notATrackShowsMessage() = runTest {
        val home = home()
        home.vm.onAddInput("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        settle()
        assertEquals(AddMessage.NotATrack("playlist"), home.vm.state.value.addMessage)
        assertTrue(home.vm.state.value.results.isEmpty())
        assertTrue(home.catalog.lookups.isEmpty() && home.catalog.searches.isEmpty())
    }

    @Test
    fun noResultsShowsMessage() = runTest {
        val home = home()
        home.vm.onAddInput("zzzzzz")
        settle()
        assertEquals(AddMessage.NoResults, home.vm.state.value.addMessage)
        assertTrue(home.vm.state.value.results.isEmpty())
    }

    @Test
    fun failedSearchShowsSpotifyUnavailable() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Failed("HTTP 500")
        home.vm.onAddInput("daft punk")
        settle()
        assertEquals(AddMessage.SpotifyUnavailable, home.vm.state.value.addMessage)
    }

    @Test
    fun clearingTheInputClearsResults() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("daft")))
        home.vm.onAddInput("daft")
        settle()
        home.vm.onAddInput("")
        settle()
        assertTrue(home.vm.state.value.results.isEmpty())
        assertNull(home.vm.state.value.addMessage)
    }

    @Test
    fun addTrackEnqueuesManualFreeRequest() = runTest {
        val home = home()
        home.controller.enqueue(request("r1"))
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("daft")))
        home.vm.onAddInput("daft")
        advanceTimeBy(5_000)
        home.vm.onAddTrack(track("daft"))
        settle()

        val added = home.controller.queue.value.items.last()
        assertEquals(
            Request("new-1", track("daft"), Requester(""), RequestSource.MANUAL, payment = null, createdAtEpochMs = 5_000),
            added,
        )
        assertEquals(listOf("r1", "new-1"), home.vm.state.value.queue.map { it.id })
        assertTrue(home.vm.state.value.results.isEmpty())
        assertEquals(400_000, home.vm.state.value.queueDurationMs)
    }

    @Test
    fun removeAndMoveDelegate() = runTest {
        val home = home()
        listOf("a", "b", "c").forEach { home.controller.enqueue(request(it)) }
        home.vm.onMove("c", 0)
        settle()
        assertEquals(listOf("c", "a", "b"), home.vm.state.value.queue.map { it.id })
        home.vm.onRemove("a")
        settle()
        assertEquals(listOf("c", "b"), home.vm.state.value.queue.map { it.id })
    }

    @Test
    fun failingSaveIsFlagged() = runTest {
        val home = home()
        settle()
        assertEquals(false, home.vm.state.value.saveFailing)
        dir.resolve("queue.json.tmp").createDirectory() // the store cannot write its temp file
        home.controller.enqueue(request("r1"))
        settle()
        assertEquals(true, home.vm.state.value.saveFailing)
    }
}
