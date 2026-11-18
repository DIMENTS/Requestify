package nl.diments.requestify.ui.blacklist

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.intake.FakeShortLinks
import nl.diments.requestify.core.storage.inCallerSaveContext
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
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.TwitchUserRef
import nl.diments.requestify.ui.home.IntakeParts
import nl.diments.requestify.ui.home.intakeParts

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class BlacklistViewModelTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-blacklist") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val artistId = "A".repeat(22)
    private val trackId = "T".repeat(22)

    private fun track(id: String) = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 200_000, false, null, artistIds = listOf("art-1"))

    private class FakeCatalog : SpotifyCatalog {
        val trackSearches = mutableListOf<String>()
        val artistSearches = mutableListOf<String>()
        var tracks: List<Track> = emptyList()
        var artists: List<ArtistRef> = emptyList()
        var linkedArtist: SpotifyResult<ArtistRef> = SpotifyResult.NotFound
        var linkedTrack: SpotifyResult<Track> = SpotifyResult.NotFound
        var searchFailure: SpotifyResult<List<Track>>? = null
        val byQuery = mutableMapOf<String, List<Track>>()

        /** A track search for a query with a gate here waits for it, even when cancelled (a request already on the wire). */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> {
            trackSearches += query
            gates[query]?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return searchFailure ?: SpotifyResult.Ok(byQuery[query] ?: tracks)
        }

        override suspend fun searchArtists(query: String, limit: Int): SpotifyResult<List<ArtistRef>> {
            artistSearches += query
            return SpotifyResult.Ok(artists)
        }

        override suspend fun artist(id: String): SpotifyResult<ArtistRef> = linkedArtist

        override suspend fun track(id: String): SpotifyResult<Track> = linkedTrack

        override suspend fun isPremium(): SpotifyResult<Boolean?> = SpotifyResult.Ok(true)
    }

    private class Setup(
        val vm: BlacklistViewModel,
        val controller: PlaybackController,
        val catalog: FakeCatalog,
        val parts: IntakeParts,
        val connected: MutableStateFlow<Boolean>,
        val viewerLookups: MutableList<String>,
        var viewerAnswer: TwitchResult<TwitchUserRef?>,
    )

    private fun TestScope.setup(twitch: Boolean = true, shortLinks: FakeShortLinks = FakeShortLinks()): Setup {
        val catalog = FakeCatalog()
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
        val connected = MutableStateFlow(twitch)
        val lookups = mutableListOf<String>()
        lateinit var setup: Setup
        val vm = BlacklistViewModel(
            store = parts.blacklist,
            intake = parts.intake,
            catalog = catalog,
            lookupViewer = { login ->
                lookups += login
                setup.viewerAnswer
            },
            twitchConnected = connected,
            scope = backgroundScope,
        )
        setup = Setup(vm, controller, catalog, parts, connected, lookups, TwitchResult.Ok(null))
        return setup
    }

    @Test
    fun aShortLinkToAnAlbumSaysNotATrack() = runTest {
        val shortLinks = FakeShortLinks()
        shortLinks.leadsTo("https://spotify.link/album", "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy")
        val s = setup(shortLinks = shortLinks)
        s.vm.onInput("https://spotify.link/album")
        runCurrent()
        assertEquals(BlacklistMessage.WrongLink("album"), s.vm.state.value.message)
    }

    @Test
    fun songSearchAddsAndSweeps() = runTest {
        val s = setup()
        val blocked = track("t1")
        s.controller.enqueue(Request("r1", blocked, Requester("Viewer", "viewer", "9"), RequestSource.CHAT, null, createdAtEpochMs = 1L))
        runCurrent()
        assertEquals(1, s.controller.queue.value.items.size)
        s.catalog.tracks = listOf(blocked)

        s.vm.onInput("song")
        advanceTimeBy(299)
        runCurrent()
        assertTrue(s.catalog.trackSearches.isEmpty(), "waits for the pause in typing")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(BlockCandidate.Song(blocked)), s.vm.state.value.results)

        s.vm.onAdd(s.vm.state.value.results.first())
        runCurrent()

        val entry = s.parts.blacklist.blacklist.value.tracks.single()
        assertEquals("t1", entry.track.id)
        assertNull(entry.by)
        assertTrue(s.controller.queue.value.items.isEmpty(), "the queue is swept: it went through the intake")
        assertEquals(emptyList(), s.vm.state.value.results)
        assertEquals(1, s.vm.state.value.blacklist.tracks.size)
    }

    @Test
    fun artistLinkResolvesArtist() = runTest {
        val s = setup()
        s.catalog.linkedArtist = SpotifyResult.Ok(ArtistRef(artistId, "Pinkfong", null))
        s.vm.onTab(BlacklistTab.Artists)

        s.vm.onInput("https://open.spotify.com/artist/$artistId")
        runCurrent()
        assertEquals(listOf(BlockCandidate.Artist(ArtistRef(artistId, "Pinkfong", null))), s.vm.state.value.results)
        assertTrue(s.catalog.artistSearches.isEmpty(), "a link is not searched for")

        assertTrue(s.vm.onSubmit("https://open.spotify.com/artist/$artistId"))
        runCurrent()
        val blocked = s.parts.blacklist.blacklist.value.artists.single()
        assertEquals(artistId, blocked.id)
        assertEquals("Pinkfong", blocked.name)
    }

    @Test
    fun trackLinkOnArtistsTabIsRefused() = runTest {
        val s = setup()
        s.vm.onTab(BlacklistTab.Artists)
        s.vm.onInput("https://open.spotify.com/track/$trackId")
        runCurrent()
        assertEquals(BlacklistMessage.WrongLink("track"), s.vm.state.value.message)
        assertEquals(emptyList(), s.vm.state.value.results)
    }

    @Test
    fun viewerNameResolvesToUserId() = runTest {
        val s = setup()
        s.viewerAnswer = TwitchResult.Ok(TwitchUserRef("42", "troll", "Troll"))
        s.vm.onTab(BlacklistTab.Viewers)

        s.vm.onInput("@Troll")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("troll"), s.viewerLookups)
        assertEquals(listOf(BlockCandidate.Viewer(TwitchUserRef("42", "troll", "Troll"))), s.vm.state.value.results)

        s.vm.onAdd(s.vm.state.value.results.first())
        runCurrent()
        val blocked = s.parts.blacklist.blacklist.value.viewers.single()
        assertEquals("42", blocked.twitchUserId)
        assertEquals("troll", blocked.login)
        assertNull(blocked.by)
    }

    @Test
    fun unknownViewerShowsNotFound() = runTest {
        val s = setup()
        s.viewerAnswer = TwitchResult.Ok(null)
        s.vm.onTab(BlacklistTab.Viewers)
        s.vm.onInput("nobody")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(BlacklistMessage.ViewerNotFound, s.vm.state.value.message)
        assertEquals(emptyList(), s.vm.state.value.results)
    }

    @Test
    fun viewerLookupFailureIsNotNotFound() = runTest {
        val s = setup()
        s.viewerAnswer = TwitchResult.Failed("boom")
        s.vm.onTab(BlacklistTab.Viewers)
        s.vm.onInput("someone")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(BlacklistMessage.TwitchUnavailable, s.vm.state.value.message)
    }

    @Test
    fun viewerTabDisabledWithoutTwitch() = runTest {
        val s = setup(twitch = false)
        s.vm.onTab(BlacklistTab.Viewers)
        assertEquals(false, s.vm.state.value.twitchConnected)

        s.vm.onInput("troll")
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(s.viewerLookups.isEmpty(), "no lookup without Twitch")
        assertEquals(emptyList(), s.vm.state.value.results)

        s.connected.value = true
        runCurrent()
        assertEquals(true, s.vm.state.value.twitchConnected)
    }

    @Test
    fun removeUnblocksOnTheCurrentTab() = runTest {
        val s = setup()
        assertTrue(s.parts.intake.blockTrack(track("t1"), by = "modjan"))
        assertTrue(s.parts.intake.blockArtist(null, "Darude", by = null))
        runCurrent()
        assertEquals(1, s.vm.state.value.blacklist.tracks.size)

        s.vm.onRemove("t1")
        runCurrent()
        assertTrue(s.vm.state.value.blacklist.tracks.isEmpty())
        assertEquals(1, s.vm.state.value.blacklist.artists.size, "the other tabs are left alone")

        s.vm.onTab(BlacklistTab.Artists)
        s.vm.onRemove("Darude")
        runCurrent()
        assertTrue(s.vm.state.value.blacklist.artists.isEmpty())
    }

    @Test
    fun changingTabClearsTheField() = runTest {
        val s = setup()
        s.catalog.tracks = listOf(track("t1"))
        s.vm.onInput("song")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, s.vm.state.value.results.size)

        s.vm.onTab(BlacklistTab.Artists)
        runCurrent()
        assertEquals("", s.vm.state.value.input)
        assertEquals(emptyList(), s.vm.state.value.results)
    }

    @Test
    fun aSlowLookupNeverLandsOverNewerInput() = runTest {
        val s = setup()
        val old = track("old")
        val new = track("new")
        s.catalog.byQuery["first"] = listOf(old)
        s.catalog.byQuery["second"] = listOf(new)
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        s.catalog.gates["first"] = firstGate
        s.catalog.gates["second"] = secondGate

        s.vm.onInput("first")
        advanceTimeBy(301) // the first search is on the wire
        runCurrent()
        s.vm.onInput("second") // the first answer is no longer wanted
        advanceTimeBy(301)
        runCurrent()

        secondGate.complete(Unit) // the newer answer comes first
        runCurrent()
        assertEquals(listOf(BlockCandidate.Song(new)), s.vm.state.value.results)

        firstGate.complete(Unit) // then the old one arrives late: it must not land
        runCurrent()
        assertEquals(listOf(BlockCandidate.Song(new)), s.vm.state.value.results)
        assertEquals("second", s.vm.state.value.input)
        assertEquals(false, s.vm.state.value.lookingUp)
    }

    @Test
    fun submitDoesNothingWhileLookingUp() = runTest {
        val s = setup()
        s.catalog.tracks = listOf(track("t1"))
        s.vm.onInput("song")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, s.vm.state.value.results.size)

        val gate = CompletableDeferred<Unit>()
        s.catalog.gates["song 2"] = gate
        s.vm.onInput("song 2") // looking up again; the old results are stale
        advanceTimeBy(301)
        runCurrent()
        assertTrue(s.vm.state.value.lookingUp)
        assertEquals(false, s.vm.onSubmit("song 2"))
        assertEquals(false, s.vm.onSubmit("song"), "the stale results answer another input")
        runCurrent()
        assertTrue(s.parts.blacklist.blacklist.value.tracks.isEmpty())

        gate.complete(Unit) // positive control: once the lookup is done, Enter blocks the first result
        runCurrent()
        assertEquals(false, s.vm.state.value.lookingUp)
        assertTrue(s.vm.onSubmit("song 2"))
        runCurrent()
        assertEquals("t1", s.parts.blacklist.blacklist.value.tracks.single().track.id)
    }

    @Test
    fun anAddOrRemoveWhoseFileCannotBeWrittenYetIsApplied() = runTest {
        val s = setup()
        val saved = track("t1")
        assertTrue(s.parts.intake.blockTrack(saved, by = null))
        runCurrent()
        dir.resolve("blacklist.json.tmp").toFile().mkdirs() // the store cannot write yet

        s.vm.onRemove("t1")
        runCurrent()
        assertEquals(false, s.vm.state.value.blockFailing, "only an unreadable blacklist refuses a change")
        assertTrue(s.vm.state.value.blacklist.tracks.isEmpty(), "applied at once, saving retried")

        s.vm.onAdd(BlockCandidate.Song(track("t2")))
        runCurrent()
        assertEquals(false, s.vm.state.value.blockFailing)
        assertEquals("t2", s.vm.state.value.blacklist.tracks.single().track.id)
        assertEquals("blacklist.json", s.parts.blacklist.saveProblem.value?.file)
    }

    @Test
    fun spotifyNotAnsweringShowsUnavailable() = runTest {
        val s = setup()
        s.catalog.searchFailure = SpotifyResult.Failed("down")
        s.vm.onInput("song")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(BlacklistMessage.SpotifyUnavailable, s.vm.state.value.message)
        assertEquals(emptyList(), s.vm.state.value.results)
    }

    @Test
    fun trackLinkOnSongsTabResolvesViaTrackLookup() = runTest {
        val s = setup()
        val linked = track(trackId)
        s.catalog.linkedTrack = SpotifyResult.Ok(linked)
        s.vm.onInput("https://open.spotify.com/track/$trackId")
        runCurrent() // no debounce for a link
        assertEquals(listOf(BlockCandidate.Song(linked)), s.vm.state.value.results)
        assertTrue(s.catalog.trackSearches.isEmpty(), "a link is not searched for")
    }
}
