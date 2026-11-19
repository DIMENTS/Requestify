package nl.diments.requestify.core.secrets

import com.sun.jna.platform.win32.Crypt32Util
import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import nl.diments.requestify.core.storage.BackgroundWriter
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.SaveProblem
import nl.diments.requestify.core.storage.StoreReadException

interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)

    /** Set while the secrets cannot be saved; never for a store that keeps nothing on disk. */
    val saveProblem: StateFlow<SaveProblem?> get() = NO_PROBLEM

    /** Returns once every change made before this call is saved; at once for a store that keeps nothing on disk. */
    suspend fun awaitSaved() {}

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = true
}

/** The save problem of a store that keeps nothing on disk: never one. */
private val NO_PROBLEM: StateFlow<SaveProblem?> = MutableStateFlow(null)

object SecretKeys {
    const val SPOTIFY_REFRESH_TOKEN = "spotify.refresh_token"
    const val TWITCH_REFRESH_TOKEN = "twitch.refresh_token"

    /** The optional bot account's login (plan 6), apart from the broadcaster's. */
    const val TWITCH_BOT_REFRESH_TOKEN = "twitch.bot_refresh_token"
    const val DONOLINK_KEY = "donolink.key"
    const val STREAMELEMENTS_JWT = "streamelements.jwt"
}

/**
 * Stores secrets as base64(DPAPI(utf8)) per key, scoped to the current Windows user. The file is read and decrypted
 * once, when the store is built (before the window exists); [get] answers from memory. [put] and [remove] apply in
 * memory at once and are encrypted and saved in the background ([BackgroundWriter]). While the file could not be read
 * at start (e.g. another program holds it), [get] finds nothing and [put] and [remove] throw [StoreReadException]
 * instead of writing over it; the file is read again every [rereadEveryMs] until it can be.
 */
class DpapiSecretStore(
    file: Path,
    private val context: SaveContext,
    private val protect: (ByteArray) -> ByteArray = Crypt32Util::cryptProtectData,
    private val unprotect: (ByteArray) -> ByteArray = Crypt32Util::cryptUnprotectData,
    private val rereadEveryMs: Long = 5_000,
) : SecretStore {
    private val store = JsonFileStore<Map<String, String>>(
        file,
        MapSerializer(String.serializer(), String.serializer()),
    ) { emptyMap() }

    /** Guarded by this. The secrets in plain text, and the saved entries that cannot be decrypted (kept as they are). */
    private var plain: Map<String, String> = emptyMap()
    private var undecryptable: Map<String, String> = emptyMap()

    /** True while the file could not be read; written under this, read by the re-read loop too. */
    @Volatile
    private var loadFailed = true

    init {
        readDecrypted()?.let(::adopt)
    }

    private val writer = BackgroundWriter(store.fileName, ::encryptAndWrite, context)

    override val saveProblem: StateFlow<SaveProblem?> get() = writer.problem

    init {
        if (loadFailed) context.scope.launch(context.io) { readAgainUntilItWorks() }
    }

    @Synchronized
    override fun get(key: String): String? = plain[key]

    @Synchronized
    override fun put(key: String, value: String) {
        if (loadFailed) throw StoreReadException(store.path, IOException("secrets.json cannot be read yet"))
        plain = plain + (key to value)
        writer.save(Saved(plain, undecryptable))
    }

    @Synchronized
    override fun remove(key: String) {
        if (loadFailed) throw StoreReadException(store.path, IOException("secrets.json cannot be read yet"))
        if (key !in plain && key !in undecryptable) return
        plain = plain - key
        undecryptable = undecryptable - key
        writer.save(Saved(plain, undecryptable))
    }

    override suspend fun awaitSaved() = writer.awaitSaved()

    override suspend fun flush(timeoutMs: Long): Boolean = writer.flush(timeoutMs)

    private class Loaded(val plain: Map<String, String>, val undecryptable: Map<String, String>)

    /**
     * Reads and decrypts the file, touching nothing of this store: the slow part (the file, with its retries, and
     * DPAPI) runs without the lock. Null when the file cannot be read.
     */
    private fun readDecrypted(): Loaded? {
        val saved = try {
            store.read()
        } catch (_: StoreReadException) {
            return null
        }
        val decrypted = HashMap<String, String>()
        val kept = HashMap<String, String>()
        saved.forEach { (key, encoded) ->
            val value = try {
                unprotect(Base64.getDecoder().decode(encoded)).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            if (value != null) decrypted[key] = value else kept[key] = encoded
        }
        return Loaded(decrypted, kept)
    }

    /** Takes what [readDecrypted] read, under the lock: from now on [put] and [remove] may write. */
    @Synchronized
    private fun adopt(loaded: Loaded) {
        plain = loaded.plain
        undecryptable = loaded.undecryptable
        loadFailed = false
    }

    private suspend fun readAgainUntilItWorks() {
        while (true) {
            delay(rereadEveryMs)
            val loaded = readDecrypted() ?: continue
            adopt(loaded)
            return
        }
    }

    /** Runs on the background writer, off the UI thread: DPAPI and the file write. */
    private fun encryptAndWrite(saved: Saved) {
        val encrypted = saved.plain.mapValues { (_, value) -> Base64.getEncoder().encodeToString(protect(value.toByteArray(Charsets.UTF_8))) }
        store.write(saved.undecryptable + encrypted)
    }

    private class Saved(val plain: Map<String, String>, val undecryptable: Map<String, String>)
}

class InMemorySecretStore : SecretStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
