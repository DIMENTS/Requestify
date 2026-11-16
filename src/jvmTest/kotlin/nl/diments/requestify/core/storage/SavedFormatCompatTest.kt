package nl.diments.requestify.core.storage

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.PlayedEntry
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.queue.RequestQueue
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.ThemeMode

/**
 * Files written by version 1 (checked in under `compat/`) must stay readable by every later version: a type
 * that is renamed or moved to another package must keep its saved name, or saved queues and settings are lost.
 */
@OptIn(ExperimentalPathApi::class)
class SavedFormatCompatTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-compat") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    /** Copies the checked-in [fixture] to [fileName] in the data folder, as an earlier version left it. */
    private fun installed(fixture: String, fileName: String): Path {
        val text = checkNotNull(javaClass.getResourceAsStream("/compat/$fixture")) { "missing fixture $fixture" }
            .use { it.readBytes().decodeToString() }
        return dir.resolve(fileName).also { it.writeText(text) }
    }

    private fun assertNothingMovedAside() =
        assertEquals(emptyList(), dir.listDirectoryEntries("*.corrupt-*").map { it.name })

    private fun track(id: String, name: String, artists: List<String>, durationMs: Long, explicit: Boolean = false, art: String? = null) =
        Track(id, "spotify:track:$id", name, artists, durationMs, explicit, art)

    @Test
    fun queueV1IsReadWithEveryPaymentKind() {
        val file = installed("queue-v1.json", "queue.json")
        val loaded = QueueStore(JsonFileStore(file, PersistedPlayback.serializer()) { PersistedPlayback() }).load()

        val bits = Request(
            "paid-bits",
            track("t1", "Blinding Lights", listOf("The Weeknd"), 200_040, art = "https://i.scdn.co/image/a1"),
            Requester("kaasboer", "kaasboer"),
            RequestSource.BITS,
            Payment.Bits(500),
            createdAtEpochMs = 1_763_290_000_000,
            sourceRef = "cheer-1",
        )
        val money = Request(
            "paid-money",
            track("t2", "Midnight City", listOf("M83"), 243_000, explicit = true),
            Requester("Viewer Two"),
            RequestSource.DONOLINK,
            Payment.Money(500, "EUR"),
            createdAtEpochMs = 1_763_290_001_000,
        )
        val free = Request(
            "free",
            track("t3", "Song Three", listOf("Artist A", "Artist B"), 180_000),
            Requester(""),
            RequestSource.MANUAL,
            payment = null,
            createdAtEpochMs = 1_763_290_002_000,
        )
        val played = Request(
            "played",
            track("t4", "Song Four", listOf("Artist C"), 210_000),
            Requester("Viewer Four", "viewer4"),
            RequestSource.STREAMELEMENTS,
            Payment.Money(250, "USD"),
            createdAtEpochMs = 1_763_289_000_000,
            sourceRef = "se-tip-9",
        )
        assertEquals(
            PersistedPlayback(
                queue = RequestQueue(listOf(bits, money)),
                handedOver = free,
                playing = null,
                history = listOf(PlayedEntry(played, playedAtEpochMs = 1_763_289_500_000)),
            ),
            loaded,
        )
        assertNothingMovedAside()
    }

    @Test
    fun settingsV1WithACustomColourIsRead() {
        val file = installed("settings-v1.json", "settings.json")
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }).settings.value
        assertEquals(
            Settings(
                spotifyClientId = "0123456789abcdef0123456789abcdef",
                themeMode = ThemeMode.Custom(-10011977),
                darkMode = DarkMode.Dark,
                language = AppLanguage.Dutch,
                paidPriority = false,
            ),
            loaded,
        )
        assertNothingMovedAside()
    }

    @Test
    fun settingsV1WithTheAlbumColourIsRead() {
        val file = installed("settings-v1-album.json", "settings.json")
        val loaded = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }).settings.value
        assertEquals(Settings(themeMode = ThemeMode.Album), loaded)
        assertNothingMovedAside()
    }
}
