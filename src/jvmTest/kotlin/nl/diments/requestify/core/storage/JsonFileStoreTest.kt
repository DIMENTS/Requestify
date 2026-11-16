package nl.diments.requestify.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import nl.diments.requestify.core.settings.Settings

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class JsonFileStoreTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-test") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store(path: Path = dir.resolve("settings.json")) =
        JsonFileStore(path, Settings.serializer()) { Settings() }

    @Test fun roundTrip() {
        val s = Settings(spotifyClientId = "a".repeat(32))
        val store = store()
        store.write(s)
        assertEquals(s, store.read())
    }

    @Test fun missingFileGivesDefault() {
        assertEquals(Settings(), store().read())
    }

    @Test fun corruptFileFallsBackToDefaultAndIsKept() {
        val path = dir.resolve("settings.json")
        val text = "{\"spotifyClientId\":"
        path.writeText(text)
        assertEquals(Settings(), store(path).read())
        val kept = dir.listDirectoryEntries("settings.json.corrupt-*")
        assertEquals(1, kept.size)
        assertEquals(text, kept.single().readText())
        assertFalse(path.exists())
    }

    @Test fun writeLeavesNoTempFile() {
        store().write(Settings())
        assertFalse(dir.resolve("settings.json.tmp").exists())
        assertEquals(listOf("settings.json"), dir.listDirectoryEntries().map { it.name })
    }

    @Test fun writeCreatesMissingParentDirectory() {
        val path = dir.resolve("nested").resolve("settings.json")
        store(path).write(Settings())
        assertEquals(Settings(), store(path).read())
    }

    @Test fun unknownKeysAreIgnored() {
        val path = dir.resolve("settings.json")
        path.writeText("{\"paidPriority\":false,\"futureField\":1}")
        assertEquals(Settings(paidPriority = false), store(path).read())
    }

    @Test fun invalidUtf8FallsBackToDefaultAndIsKept() {
        val path = dir.resolve("settings.json")
        Files.write(path, byteArrayOf('{'.code.toByte(), 0xC3.toByte()))
        assertEquals(Settings(), store(path).read())
        assertEquals(1, dir.listDirectoryEntries("settings.json.corrupt-*").size)
        assertFalse(path.exists())
    }

    @Test fun emptyFileFallsBackToDefaultAndIsKept() {
        val path = dir.resolve("settings.json")
        path.writeText("")
        assertEquals(Settings(), store(path).read())
        assertEquals(1, dir.listDirectoryEntries("settings.json.corrupt-*").size)
    }

    @Test fun overwriteReplacesPreviousValue() {
        val store = store()
        store.write(Settings(paidPriority = false))
        store.write(Settings(spotifyClientId = "x"))
        assertEquals(Settings(spotifyClientId = "x"), store.read())
    }
}
