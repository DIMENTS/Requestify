package nl.diments.requestify.core.secrets

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.nio.file.ExtendedOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.StoreReadException
import nl.diments.requestify.core.storage.inCallerSaveContext

@OptIn(kotlin.io.path.ExperimentalPathApi::class, ExperimentalCoroutinesApi::class)
class DpapiSecretStoreTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-test")
        file = dir.resolve("secrets.json")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun TestScope.saves() = SaveContext(backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))

    @Test fun roundTrip() {
        val store = DpapiSecretStore(file, inCallerSaveContext())
        store.put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "tok-123")
        assertEquals("tok-123", store.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals("tok-123", DpapiSecretStore(file, inCallerSaveContext()).get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    @Test fun fileHasNoPlaintext() {
        DpapiSecretStore(file, inCallerSaveContext()).put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "tok-123")
        assertFalse(file.readText().contains("tok-123"))
    }

    @Test fun undecryptableEntryReturnsNull() {
        file.writeText("""{"${SecretKeys.SPOTIFY_REFRESH_TOKEN}":"!!not-base64!!","other":"AAAA"}""")
        val store = DpapiSecretStore(file, inCallerSaveContext())
        assertNull(store.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertNull(store.get("other"))
        assertNull(store.get("absent"))
    }

    @Test fun removeDeletes() {
        val store = DpapiSecretStore(file, inCallerSaveContext())
        store.put("a", "1")
        store.put("b", "2")
        store.remove("a")
        assertNull(store.get("a"))
        assertEquals("2", store.get("b"))
        val restarted = DpapiSecretStore(file, inCallerSaveContext())
        assertNull(restarted.get("a"))
        assertEquals("2", restarted.get("b"))
    }

    @Test fun getNeverDecryptsAgain() {
        DpapiSecretStore(file, inCallerSaveContext()).put("a", "1")
        var decrypts = 0
        val store = DpapiSecretStore(file, inCallerSaveContext(), unprotect = { decrypts++; Crypt32Util.cryptUnprotectData(it) })
        val atStart = decrypts
        repeat(5) { assertEquals("1", store.get("a")) }
        assertEquals(1, atStart, "read once, at start")
        assertEquals(atStart, decrypts, "a get answers from memory")
    }

    @Test fun putIsVisibleAtOnceAndEncryptedOffTheCaller() = runTest {
        var encrypts = 0
        val store = DpapiSecretStore(file, saves(), protect = { encrypts++; Crypt32Util.cryptProtectData(it) })
        store.put(SecretKeys.DONOLINK_KEY, "dl-secret")
        assertEquals("dl-secret", store.get(SecretKeys.DONOLINK_KEY), "visible at once")
        assertEquals(0, encrypts, "not encrypted by the caller")
        assertFalse(file.exists(), "not written by the caller")
        runCurrent()
        assertEquals(1, encrypts)
        assertFalse(file.readText().contains("dl-secret"))
    }

    @Test fun aRestartReadsWhatWasPut() = runTest {
        val store = DpapiSecretStore(file, saves())
        store.put("a", "1")
        store.awaitSaved()
        assertEquals("1", DpapiSecretStore(file, saves()).get("a"))
    }

    @Test fun anUnreadableFileRefusesPutAndIsNeverOverwritten() = runTest {
        DpapiSecretStore(file, inCallerSaveContext()).put("a", "1")
        val text = file.readText()
        // Another program holds the file and denies reading it (a Windows sharing violation).
        Files.newByteChannel(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ).use {
            val store = DpapiSecretStore(file, saves())
            assertNull(store.get("a"), "nothing known while unreadable")
            assertFailsWith<StoreReadException> { store.put("b", "2") }
            assertFailsWith<StoreReadException> { store.remove("a") }
            advanceTimeBy(20_000)
            runCurrent()
        }
        assertEquals(text, file.readText(), "never written over")
    }

    @Test fun anUnreadableFileIsReadAgainInTheBackground() = runTest {
        DpapiSecretStore(file, inCallerSaveContext()).put("a", "1")
        val lock = Files.newByteChannel(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)
        val store = DpapiSecretStore(file, saves())
        assertNull(store.get("a"))
        lock.close()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("1", store.get("a"))
        store.put("b", "2")
        runCurrent()
        assertTrue(DpapiSecretStore(file, inCallerSaveContext()).let { it.get("a") == "1" && it.get("b") == "2" })
    }

    @Test fun inMemoryStoreBehaves() {
        val store = InMemorySecretStore()
        store.put("k", "v")
        assertEquals("v", store.get("k"))
        store.remove("k")
        assertNull(store.get("k"))
    }
}
