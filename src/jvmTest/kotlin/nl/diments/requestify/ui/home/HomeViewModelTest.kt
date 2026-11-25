package nl.diments.requestify.ui.home

import com.sun.nio.file.ExtendedOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
import nl.diments.requestify.core.storage.SaveHealth
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.intake.FakeShortLinks
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.FakeSpotifyPlayer
import nl.diments.requestify.core.playback.PlaybackConfig
import nl.diments.requestify.core.playback.PlaybackController
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.spotify.ArtistRef
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
        var trackFailure: SpotifyResult<Nothing>? = null

        override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> {
            searches += query to limit
            return searchResult
        }

        override suspend fun searchArtists(query: String, limit: Int): SpotifyResult<List<ArtistRef>> = SpotifyResult.Ok(emptyList())

        override suspend fun artist(id: String): SpotifyResult<ArtistRef> = SpotifyResult.NotFound

        override suspend fun track(id: String): SpotifyResult<Track> {
            lookups += id
            trackFailure?.let { return it }
            return SpotifyResult.Ok(Track(id, "spotify:track:$id", "Linked", listOf("Artist"), 180_000, false, null))
        }

        override suspend fun isPremium(): SpotifyResult<Boolean?> = SpotifyResult.Ok(true)
    }

    private class Home(
        val vm: HomeViewModel,
        val controller: PlaybackController,
        val catalog: FakeCatalog,
        val player: FakeSpotifyPlayer,
        val parts: IntakeParts,
    )

    private fun TestScope.home(catalog: FakeCatalog = FakeCatalog(), shortLinks: FakeShortLinks = FakeShortLinks()): Home {
        val player = FakeSpotifyPlayer { testScheduler.currentTime }
        val controller = PlaybackController(
            player = player,
            store = QueueStore(
                JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer(), retryDelaysMs = emptyList()) {
                    PersistedPlayback()
                },
            ),
            requestsOpen = MutableStateFlow(true),
            paidPriority = { true },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())
        val parts = intakeParts(dir, controller, catalog, settings, backgroundScope, shortLinks) { testScheduler.currentTime }
        var ids = 0
        val vm = HomeViewModel(
            controller = controller,
            catalog = catalog,
            settings = settings,
            intake = parts.intake,
            rejected = parts.rejected,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            newId = { "new-${++ids}" },
        )
        return Home(vm, controller, catalog, player, parts)
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
    fun aShortLinkToAnAlbumSaysNotATrack() = runTest {
        val shortLinks = FakeShortLinks()
        shortLinks.leadsTo("https://spotify.link/album", "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy")
        val home = home(shortLinks = shortLinks)
        home.vm.onAddInput("https://spotify.link/album")
        settle()
        assertEquals(AddMessage.NotATrack("album"), home.vm.state.value.addMessage)
    }

    @Test
    fun shortLinkIsFollowedToItsTrack() = runTest {
        val shortLinks = FakeShortLinks()
        shortLinks.leadsTo("https://spotify.link/abc", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=x")
        val home = home(shortLinks = shortLinks)
        home.vm.onAddInput("https://spotify.link/abc")
        settle()
        assertEquals(listOf("4cOdK2wGLETKBW3PvgPWqT"), home.vm.state.value.results.map { it.id })
        assertTrue(home.catalog.searches.isEmpty())

        home.vm.onAddInput("https://spotify.link/nowhere")
        settle()
        assertTrue(home.vm.state.value.results.isEmpty())
        assertEquals(AddMessage.NoResults, home.vm.state.value.addMessage)
        assertTrue(home.catalog.searches.isEmpty())

        home.catalog.trackFailure = SpotifyResult.Failed("down")
        home.vm.onAddInput("https://spotify.link/abc ")
        settle()
        assertEquals(AddMessage.SpotifyUnavailable, home.vm.state.value.addMessage, "the track behind it could not be looked up")
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
    fun newInputClearsTheOldMessageAndMarksResultsAsPending() = runTest {
        val home = home()
        home.vm.onAddInput("zzzzzz")
        settle()
        assertEquals(AddMessage.NoResults, home.vm.state.value.addMessage)

        home.vm.onAddInput("zzzzzzz")
        runCurrent()
        assertNull(home.vm.state.value.addMessage)
        assertTrue(home.vm.state.value.lookingUp)
        settle()
        assertEquals(false, home.vm.state.value.lookingUp)
    }

    @Test
    fun submitAddsTheFirstResultForTheSubmittedText() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("daft"), track("punk")))
        home.vm.onAddInput("daft")
        settle()
        assertTrue(home.vm.onAddSubmit("daft"))
        settle()
        assertEquals(listOf(track("daft")), home.controller.queue.value.items.map { it.track })
        assertTrue(home.vm.state.value.results.isEmpty())
    }

    @Test
    fun submitWhileTheNextSearchIsPendingAddsNothing() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("a")))
        home.vm.onAddInput("a")
        settle()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("ab")))
        home.vm.onAddInput("ab")
        advanceTimeBy(100) // still within the debounce
        assertEquals(false, home.vm.onAddSubmit("ab"))
        settle()
        assertTrue(home.controller.queue.value.items.isEmpty())

        assertTrue(home.vm.onAddSubmit("ab")) // the fresh results for "ab" do count
        settle()
        assertEquals(listOf(track("ab")), home.controller.queue.value.items.map { it.track })
    }

    @Test
    fun pastedLinkSubmittedAtOnceNeverAddsAnEarlierResult() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("daft")))
        home.vm.onAddInput("daft")
        settle()
        val link = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        home.vm.onAddInput(link)
        assertEquals(false, home.vm.onAddSubmit(link))
        settle()
        assertTrue(home.controller.queue.value.items.isEmpty())

        assertTrue(home.vm.onAddSubmit(link))
        settle()
        assertEquals(listOf("4cOdK2wGLETKBW3PvgPWqT"), home.controller.queue.value.items.map { it.track.id })
    }

    @Test
    fun submitIgnoresResultsForOtherText() = runTest {
        val home = home()
        home.catalog.searchResult = SpotifyResult.Ok(listOf(track("daft")))
        home.vm.onAddInput("daft")
        settle()
        assertEquals(false, home.vm.onAddSubmit("")) // e.g. a field that was rebuilt empty
        assertEquals(false, home.vm.onAddSubmit("daft punk"))
        settle()
        assertTrue(home.controller.queue.value.items.isEmpty())
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
    fun previousDelegatesToTheController() = runTest {
        val home = home()
        home.vm.onPrevious()
        settle()
        assertEquals(1, home.player.callsTo("skipPrevious").size)
    }

    @Test
    fun upcomingFlowsIntoState() = runTest {
        val home = home()
        home.player.plays(track("now"))
        home.player.queueTracks = SpotifyResult.Ok(listOf(track("next-1"), track("next-2")))
        home.controller.start()
        settle()
        assertEquals(listOf(track("next-1"), track("next-2")), home.vm.state.value.upcoming)

        home.player.currentlyPlayingResult = SpotifyResult.Ok(null) // Spotify stopped
        advanceTimeBy(PlaybackConfig().playingPollMs)
        settle()
        assertEquals(emptyList(), home.vm.state.value.upcoming)
    }

    @Test
    fun aFailingQueueSaveReachesTheSaveHealth() = runTest {
        val home = home()
        val health = SaveHealth(emptyList(), home.controller.persistError, backgroundScope, clock = { testScheduler.currentTime })
        settle()
        assertEquals(emptyList(), health.problems.value)
        dir.resolve("queue.json.tmp").createDirectory() // the store cannot write its temp file
        home.controller.enqueue(request("r1"))
        settle()
        assertEquals(listOf("queue.json"), health.problems.value.map { it.file }, "the app-wide save banner shows it")
    }

    @Test
    fun unreadableQueueIsFlaggedUntilItLoads() = runTest {
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })
            .save(PersistedPlayback(RequestQueue(listOf(request("saved")))))
        val home = home()
        // Another program holds queue.json and denies reading it (a Windows sharing violation).
        Files.newByteChannel(dir.resolve("queue.json"), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ).use {
            home.controller.enqueue(request("r1"))
            settle()
            assertEquals(true, home.vm.state.value.loadFailing)
            assertEquals(emptyList(), home.vm.state.value.queue)
        }
        home.controller.enqueue(request("r2"))
        settle()
        assertEquals(false, home.vm.state.value.loadFailing)
        assertEquals(listOf("saved", "r1", "r2"), home.vm.state.value.queue.map { it.id })
    }

    private fun viewerRequest(id: String) = Request(
        id, track("t-$id").copy(artistIds = listOf("art-$id")), Requester("Viewer $id", "viewer$id", "u-$id"),
        RequestSource.CHAT, null, createdAtEpochMs = 1L,
    )

    @Test
    fun blockSongFromNowPlayingBlocksAndSkips() = runTest {
        val home = home()
        home.player.plays(track("now"))
        home.controller.start()
        settle()

        home.vm.onBlockCurrentAndSkip(BlockTarget.Song(track("now")))
        settle()

        assertEquals(listOf("now"), home.parts.blacklist.blacklist.value.tracks.map { it.track.id })
        assertEquals(1, home.player.callsTo("skipNext").size)
    }

    @Test
    fun blockArtistFromNowPlayingBlocksTheArtistAndSkips() = runTest {
        val home = home()
        val now = track("now").copy(artists = listOf("Daft Punk"), artistIds = listOf("dp"))
        home.player.plays(now)
        home.controller.start()
        settle()

        home.vm.onBlockCurrentAndSkip(BlockTarget.Artist(now))
        settle()

        assertEquals(listOf("dp" to "Daft Punk"), home.parts.blacklist.blacklist.value.artists.map { it.id to it.name })
        assertEquals(1, home.player.callsTo("skipNext").size)
    }

    @Test
    fun blockViewerFromNowPlayingDoesNotSkip() = runTest {
        val home = home()
        home.player.plays(track("now"))
        home.controller.start()
        settle()

        home.vm.onBlockCurrentAndSkip(BlockTarget.Viewer(Requester("Fan", "fan", "42")))
        settle()

        assertEquals(listOf("42"), home.parts.blacklist.blacklist.value.viewers.map { it.twitchUserId })
        assertEquals(0, home.player.callsTo("skipNext").size)
    }

    @Test
    fun blockingFromTheQueueSweepsTheQueueAndNeverSkips() = runTest {
        val home = home()
        val keep = viewerRequest("keep")
        val gone = viewerRequest("gone")
        home.controller.enqueue(keep)
        home.controller.enqueue(gone)
        settle()

        home.vm.onBlock(BlockTarget.Song(gone.track))
        settle()

        assertEquals(listOf("keep"), home.vm.state.value.queue.map { it.id })
        assertEquals(0, home.player.callsTo("skipNext").size)
    }

    @Test
    fun blockFromNowPlayingNeverSkipsTheNextSong() = runTest {
        val home = home()
        home.player.plays(track("next"))
        home.controller.start()
        settle() // Spotify moved on to the next song; the menu was opened on the one before

        home.vm.onBlockCurrentAndSkip(BlockTarget.Song(track("now")))
        settle()

        assertEquals(listOf("now"), home.parts.blacklist.blacklist.value.tracks.map { it.track.id })
        assertEquals(0, home.player.callsTo("skipNext").size, "the song that plays now was not blocked")
    }

    @Test
    fun blockingFromARowAlsoTakesThatRowOut() = runTest {
        val home = home()
        val own = Request("own", track("own"), Requester(""), RequestSource.MANUAL, null, createdAtEpochMs = 1L)
        val freeAnyway = viewerRequest("free").copy(rulesWaived = true)
        val paidAnyway = viewerRequest("paid").copy(
            source = RequestSource.BITS,
            payment = nl.diments.requestify.core.model.Payment.Bits(500),
            rulesWaived = true,
        )
        val other = viewerRequest("other")
        listOf(own, freeAnyway, paidAnyway, other).forEach(home.controller::enqueue)
        settle()

        home.vm.onBlockFromRow(own, BlockTarget.Song(own.track)) // the streamer's own add: the sweep leaves those
        home.vm.onBlockFromRow(freeAnyway, BlockTarget.Artist(freeAnyway.track)) // let in anyway: left by the sweep too
        home.vm.onBlockFromRow(paidAnyway, BlockTarget.Viewer(paidAnyway.requester))
        settle()

        assertEquals(listOf("other"), home.vm.state.value.queue.map { it.id })
        assertEquals(listOf("own"), home.parts.blacklist.blacklist.value.tracks.map { it.track.id })
        val paid = home.vm.state.value.rejected.single { it.id == "paid" }
        assertEquals(nl.diments.requestify.core.model.RejectReason.ViewerBlocked, paid.reason)
        assertEquals("paid", paid.request?.id, "a paid request is kept in the Rejected list, never just dropped")
        assertTrue(home.vm.state.value.rejected.none { it.id == "own" }, "the streamer's own add is simply removed")
    }

    @Test
    fun rejectedEntriesAndThePaidBadgeFlowIntoState() = runTest {
        val home = home()
        settle()
        assertEquals(emptyList(), home.vm.state.value.rejected)
        assertEquals(0, home.vm.state.value.rejectedBadge)

        val free = viewerRequest("free")
        val paid = viewerRequest("paid").copy(source = RequestSource.BITS, payment = nl.diments.requestify.core.model.Payment.Bits(500))
        listOf(free, paid).forEachIndexed { i, r ->
            home.parts.rejected.add(
                nl.diments.requestify.core.intake.RejectedEntry(
                    r.id, r, "input", r.requester, r.source, r.payment,
                    nl.diments.requestify.core.model.RejectReason.TooLong(420_000), atEpochMs = i.toLong(), refunded = false,
                ),
            )
        }
        settle()

        assertEquals(listOf("paid", "free"), home.vm.state.value.rejected.map { it.id })
        assertEquals(1, home.vm.state.value.rejectedBadge)
    }

    @Test
    fun addAnywayQueuesTheRejectedRequestAndDismissDropsIt() = runTest {
        val home = home()
        val a = viewerRequest("a")
        val b = viewerRequest("b")
        listOf(a, b).forEach { r ->
            home.parts.rejected.add(
                nl.diments.requestify.core.intake.RejectedEntry(
                    r.id, r, "input", r.requester, r.source, r.payment,
                    nl.diments.requestify.core.model.RejectReason.TooLong(420_000), atEpochMs = 1L, refunded = false,
                ),
            )
        }
        settle()

        home.vm.onAddAnyway("a")
        home.vm.onDismiss("b")
        settle()

        assertEquals(listOf("a"), home.vm.state.value.queue.map { it.id })
        assertEquals(emptyList(), home.vm.state.value.rejected)
    }

    private fun blacklistBlocked(home: Home) = java.nio.file.Files.createDirectory(dir.resolve("blacklist.json.tmp"))

    @Test
    fun aBlockWhoseFileCannotBeWrittenYetStillBlocksAndSkips() = runTest {
        val home = home()
        home.player.plays(track("now"))
        home.controller.start()
        settle()
        blacklistBlocked(home) // the blacklist cannot write its temp file yet

        home.vm.onBlockCurrentAndSkip(BlockTarget.Song(track("now")))
        settle()

        assertEquals(1, home.player.callsTo("skipNext").size)
        assertEquals(listOf("now"), home.parts.blacklist.blacklist.value.tracks.map { it.track.id }, "applied at once, saving retried")
        assertEquals(false, home.vm.state.value.blockFailing)
    }

    @Test
    fun blockingWhatIsBlockedAlreadyStillSkips() = runTest {
        val home = home()
        home.player.plays(track("now"))
        home.controller.start()
        settle()
        home.vm.onBlock(BlockTarget.Song(track("now")))
        settle()

        home.vm.onBlockCurrentAndSkip(BlockTarget.Song(track("now")))
        settle()

        assertEquals(1, home.player.callsTo("skipNext").size)
        assertEquals(false, home.vm.state.value.blockFailing)
    }
}
