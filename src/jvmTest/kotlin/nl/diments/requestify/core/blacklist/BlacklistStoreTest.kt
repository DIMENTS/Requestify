package nl.diments.requestify.core.blacklist

import com.sun.nio.file.ExtendedOpenOption
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
import nl.diments.requestify.core.storage.inCallerSaveContext
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalPathApi::class)
class BlacklistStoreTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-blacklist")
        file = dir.resolve("blacklist.json")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store() = BlacklistStore(JsonFileStore(file, Blacklist.serializer(), retryDelaysMs = emptyList()) { Blacklist() }, inCallerSaveContext())
    private val track = Track("t1", "spotify:track:t1", "Song", listOf("A"), 200_000, false, null, listOf("a1"))

    @Test
    fun addIsIdempotentAndPersists() {
        val store = store()
        assertTrue(store.addTrack(track, null, 1))
        assertTrue(store.addTrack(track, "mod", 2))
        assertTrue(store.addArtist("a1", "A", "mod", 3))
        assertTrue(store.addArtist("a1", "A", null, 4))
        assertTrue(store.addArtist(null, "Nameless", null, 5))
        assertTrue(store.addArtist(null, "nameless", null, 6))
        assertTrue(store.addViewer("u1", "kaas", null, 7))
        assertTrue(store.addViewer("u1", "renamed", null, 8))
        val expected = Blacklist(
            tracks = listOf(BlockedTrack(track, 1)),
            artists = listOf(BlockedArtist("a1", "A", 3, "mod"), BlockedArtist(null, "Nameless", 5)),
            viewers = listOf(BlockedViewer("u1", "kaas", 7)),
        )
        assertEquals(expected, store.blacklist.value)
        assertEquals(expected, store().blacklist.value)

        assertTrue(store.removeTrack("t1"))
        assertTrue(store.removeArtist("a1"))
        assertTrue(store.removeArtist("NAMELESS"))
        assertTrue(store.removeViewer("u1"))
        assertEquals(Blacklist(), store().blacklist.value)
    }

    @Test
    fun unreadableFileIsNeverOverwritten() {
        store().addViewer("u1", "kaas", null, 1)
        val text = file.readText()
        Files.newByteChannel(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ).use {
            val locked = store()
            assertTrue(locked.loadFailed.value)
            assertEquals(Blacklist(), locked.blacklist.value, "the app still starts")
            assertFalse(locked.addTrack(track, null, 2), "a change cannot be saved yet")
            assertEquals(Blacklist(), locked.blacklist.value)
        }
        assertEquals(text, file.readText(), "the saved blacklist is untouched")
    }

    @Test
    fun aFailedWriteKeepsTheChangeAndReportsIt() {
        val store = store()
        Files.createDirectory(dir.resolve("blacklist.json.tmp")) // the temp file cannot be written
        assertTrue(store.addTrack(track, null, 1))
        assertEquals(listOf(track.id), store.blacklist.value.tracks.map { it.track.id }, "applied at once")
        assertEquals("blacklist.json", store.saveProblem.value?.file, "the failing save shows")
    }
}
