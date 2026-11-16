package nl.diments.requestify.core.secrets

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.StoreReadException

interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

object SecretKeys {
    const val SPOTIFY_REFRESH_TOKEN = "spotify.refresh_token"
}

/**
 * Stores secrets as base64(DPAPI(utf8)) per key, scoped to the current Windows user. While the file cannot be read
 * (e.g. another program holds it), [get] finds nothing and [put] and [remove] throw instead of writing over it.
 */
class DpapiSecretStore(file: Path) : SecretStore {
    private val store = JsonFileStore<Map<String, String>>(
        file,
        MapSerializer(String.serializer(), String.serializer()),
    ) { emptyMap() }

    override fun get(key: String): String? {
        val saved = try {
            store.read()
        } catch (_: StoreReadException) {
            return null
        }
        val encoded = saved[key] ?: return null
        return try {
            val encrypted = Base64.getDecoder().decode(encoded)
            Crypt32Util.cryptUnprotectData(encrypted).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun put(key: String, value: String) {
        val encrypted = Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))
        store.write(store.read() + (key to Base64.getEncoder().encodeToString(encrypted)))
    }

    @Synchronized
    override fun remove(key: String) {
        val current = store.read()
        if (key in current) store.write(current - key)
    }
}

class InMemorySecretStore : SecretStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
