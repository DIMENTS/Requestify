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

        override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> {
            trackSearches += query
            return SpotifyResult.Ok(tracks)
        }

        override suspend fun searchArtists(query: String, limit: Int): SpotifyResult<List<ArtistRef>> {
            artistSearches += query
            return SpotifyResult.Ok(artists)
        }

        override suspend fun artist(id: String): SpotifyResult<ArtistRef> = linkedArtist

        override suspend fun track(id: String): SpotifyResult<Track> = SpotifyResult.NotFound

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

    private fun TestScope.setup(twitch: Boolean = true): Setup {
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
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
        val parts = intakeParts(dir, controller, catalog, settings, backgroundScope) { testScheduler.currentTime }
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
}
