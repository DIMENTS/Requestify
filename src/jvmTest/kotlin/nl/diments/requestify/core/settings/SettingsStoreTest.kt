package nl.diments.requestify.core.settings

import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalPathApi::class)
class SettingsStoreTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-settings")
        file = dir.resolve("settings.json")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val saved = Settings(spotifyClientId = "0123456789abcdef0123456789abcdef", darkMode = DarkMode.Dark)

    private fun jsonStore() = JsonFileStore(file, Settings.serializer(), retryDelaysMs = emptyList()) { Settings() }

    /** Another program holds the file and denies reading it (a Windows sharing violation). */
    private fun lockedForReading(): SeekableByteChannel =
        Files.newByteChannel(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)

    @Test
    fun readableSettingsAreLoaded() {
        jsonStore().write(saved)
        val store = SettingsStore(jsonStore())
        assertEquals(saved, store.settings.value)
        assertFalse(store.loadFailed.value)
    }

    @Test
    fun unreadableSettingsUseTheDefaultsAndAreNeverOverwritten() {
        jsonStore().write(saved)
        val text = file.readText()
        lockedForReading().use {
            val store = SettingsStore(jsonStore())
            assertEquals(Settings(), store.settings.value, "the app still starts")
            assertTrue(store.loadFailed.value)
            assertFalse(store.tryUpdate { it.copy(darkMode = DarkMode.Light) }, "a change cannot be saved yet")
            assertEquals(Settings(), store.settings.value)
        }
        assertEquals(text, file.readText(), "the saved settings are untouched")
    }

    @Test
    fun aChangeOnceTheFileIsReadableKeepsTheSavedSettings() {
        jsonStore().write(saved)
        val store = lockedForReading().use { SettingsStore(jsonStore()) }
        assertTrue(store.loadFailed.value)

        assertTrue(store.tryUpdate { it.copy(language = AppLanguage.Dutch) })
        val expected = saved.copy(language = AppLanguage.Dutch)
        assertEquals(expected, store.settings.value)
        assertFalse(store.loadFailed.value)
        assertEquals(expected, jsonStore().read())
    }
}
