package nl.diments.requestify.core.queue

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.model.Payment
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.model.Requester
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.ThemeMode
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class QueueStoreTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-test") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() =
        QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() })

    private fun req(id: String, payment: Payment? = null) = Request(
        id = id,
        track = Track(id, "spotify:track:$id", "Song $id", listOf("Artist"), 180_000, false, null),
        requester = Requester("Viewer", "viewer"),
        source = RequestSource.CHAT,
        payment = payment,
        createdAtEpochMs = 1L,
    )

    @Test fun queueStoreRoundTrip() {
        val p = PersistedPlayback(
            queue = RequestQueue(listOf(req("a"), req("b", Payment.Money(500, "EUR")))),
            handedOver = req("c", Payment.Bits(100)),
            playing = req("d"),
        )
        val store = store()
        store.save(p)
        assertEquals(p, store.load())
    }

    @Test fun emptyLoadGivesDefault() {
        assertEquals(PersistedPlayback(), store().load())
    }

    @Test fun historyCappedTo50() {
        val history = (1L..60L).map { PlayedEntry(req("r$it"), playedAtEpochMs = it) }.shuffled()
        val store = store()
        store.save(PersistedPlayback(history = history))
        val loaded = store.load().history
        assertEquals(50, loaded.size)
        assertEquals((11L..60L).toSet(), loaded.map { it.playedAtEpochMs }.toSet())
    }

    @Test fun settingsStoreUpdatePersistsAndEmits() {
        val file = JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }
        val store = SettingsStore(file)
        store.update { it.copy(themeMode = ThemeMode.Custom(0xFF112233.toInt()), darkMode = DarkMode.Dark) }
        val expected = Settings(themeMode = ThemeMode.Custom(0xFF112233.toInt()), darkMode = DarkMode.Dark)
        assertEquals(expected, store.settings.value)
        assertEquals(expected, SettingsStore(file).settings.value)
    }
}
