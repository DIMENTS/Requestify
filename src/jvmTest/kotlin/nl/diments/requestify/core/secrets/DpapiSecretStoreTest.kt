package nl.diments.requestify.core.secrets

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class DpapiSecretStoreTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-test")
        file = dir.resolve("secrets.json")
    }

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test fun roundTrip() {
        val store = DpapiSecretStore(file)
        store.put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "tok-123")
        assertEquals("tok-123", store.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertEquals("tok-123", DpapiSecretStore(file).get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
    }

    @Test fun fileHasNoPlaintext() {
        DpapiSecretStore(file).put(SecretKeys.SPOTIFY_REFRESH_TOKEN, "tok-123")
        assertFalse(file.readText().contains("tok-123"))
    }

    @Test fun undecryptableEntryReturnsNull() {
        file.writeText("""{"${SecretKeys.SPOTIFY_REFRESH_TOKEN}":"!!not-base64!!","other":"AAAA"}""")
        val store = DpapiSecretStore(file)
        assertNull(store.get(SecretKeys.SPOTIFY_REFRESH_TOKEN))
        assertNull(store.get("other"))
        assertNull(store.get("absent"))
    }

    @Test fun removeDeletes() {
        val store = DpapiSecretStore(file)
        store.put("a", "1")
        store.put("b", "2")
        store.remove("a")
        assertNull(store.get("a"))
        assertEquals("2", store.get("b"))
    }

    @Test fun inMemoryStoreBehaves() {
        val store = InMemorySecretStore()
        store.put("k", "v")
        assertEquals("v", store.get("k"))
        store.remove("k")
        assertNull(store.get("k"))
    }
}
