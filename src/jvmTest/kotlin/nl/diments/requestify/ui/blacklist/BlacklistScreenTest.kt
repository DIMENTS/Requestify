package nl.diments.requestify.ui.blacklist

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
import nl.diments.requestify.ui.home.IntakeParts
import nl.diments.requestify.ui.home.intakeParts
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)
class BlacklistScreenTest {
    private val originalLocale = Locale.getDefault()
    private lateinit var dir: Path
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = createTempDirectory("requestify-blacklist-ui")
    }

    @AfterTest fun tearDown() {
        Locale.setDefault(originalLocale)
        job.cancel()
        dir.deleteRecursively()
    }

    private object NoCatalog : SpotifyCatalog {
        override suspend fun searchTracks(query: String, limit: Int): SpotifyResult<List<Track>> = SpotifyResult.Ok(emptyList())
        override suspend fun searchArtists(query: String, limit: Int): SpotifyResult<List<ArtistRef>> = SpotifyResult.Ok(emptyList())
        override suspend fun artist(id: String): SpotifyResult<ArtistRef> = SpotifyResult.NotFound
        override suspend fun track(id: String): SpotifyResult<Track> = SpotifyResult.NotFound
        override suspend fun isPremium(): SpotifyResult<Boolean?> = SpotifyResult.Ok(true)
    }

    private fun parts(): IntakeParts {
        val controller = PlaybackController(
            player = FakeSpotifyPlayer { 0L },
            store = QueueStore(
                JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer(), retryDelaysMs = emptyList()) { PersistedPlayback() },
            ),
            requestsOpen = MutableStateFlow(true),
            paidPriority = { true },
            scope = scope,
            clock = { 0L },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val settings = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })
        return intakeParts(dir, controller, NoCatalog, settings, scope) { 0L }
    }

    private fun track(id: String, name: String, artist: String) =
        Track(id, "spotify:track:$id", name, listOf(artist), 200_000, false, null)

    private fun viewModel(parts: IntakeParts, twitch: Boolean) = BlacklistViewModel(
        store = parts.blacklist,
        intake = parts.intake,
        catalog = NoCatalog,
        lookupViewer = { TwitchResult.Ok(null) },
        twitchConnected = MutableStateFlow(twitch),
        scope = scope,
    )

    private val nov12 = 1_762_948_800_000L // 12 Nov 2025, midday UTC: the same day in every time zone

    @Test
    fun tabsShowCountsAndRowsShowWhoBlocked() = runDesktopComposeUiTest(width = 1000, height = 800) {
        val parts = parts()
        parts.blacklist.addTrack(track("t1", "Never Gonna Give You Up", "Rick Astley"), by = "modjan", at = nov12)
        parts.blacklist.addTrack(track("t2", "Sandstorm", "Darude"), by = null, at = nov12 + 1)
        parts.blacklist.addArtist("a1", "Pinkfong", by = null, at = nov12)
        parts.blacklist.addViewer("42", "troll", by = "modjan", at = nov12)
        val vm = viewModel(parts, twitch = true)
        setContent { RequestifyTheme(BrandSeed, dark = true) { BlacklistScreen(vm) } }

        onNodeWithText("Songs · 2").assertIsDisplayed()
        onNodeWithText("Artists · 1").assertIsDisplayed()
        onNodeWithText("Viewers · 1").assertIsDisplayed()
        onNodeWithText("Never Gonna Give You Up").assertIsDisplayed()
        onNodeWithText("Rick Astley", substring = true).assertIsDisplayed()
        onNodeWithText("blocked by @modjan · 12 Nov", substring = true).assertIsDisplayed()
        onNodeWithText("blocked by you · 12 Nov", substring = true).assertIsDisplayed()

        onNodeWithText("Artists · 1").performClick()
        onNodeWithText("Pinkfong").assertIsDisplayed()
        onNodeWithText("blocked by you · 12 Nov", substring = true).assertIsDisplayed()

        onNodeWithText("Viewers · 1").performClick()
        onNodeWithText("@troll").assertIsDisplayed()
        onNodeWithText("blocked by @modjan · 12 Nov", substring = true).assertIsDisplayed()
        onNodeWithContentDescription("Unblock @troll").performClick()
        assertEquals(emptyList(), parts.blacklist.blacklist.value.viewers)
        onNodeWithText("Viewers · 0").assertIsDisplayed()
    }

    @Test
    fun viewersTabIsDisabledWithoutTwitch() = runDesktopComposeUiTest(width = 1000, height = 800) {
        val vm = viewModel(parts(), twitch = false)
        setContent { RequestifyTheme(BrandSeed, dark = true) { BlacklistScreen(vm) } }

        onNodeWithText("Viewers · 0").performClick()
        onNodeWithText("Connect Twitch to block viewers").assertIsDisplayed()
    }

    @Test
    fun emptyTabSaysSo() = runDesktopComposeUiTest(width = 1000, height = 800) {
        val vm = viewModel(parts(), twitch = true)
        setContent { RequestifyTheme(BrandSeed, dark = true) { BlacklistScreen(vm) } }
        onNodeWithText("No blocked songs").assertIsDisplayed()
    }
}
