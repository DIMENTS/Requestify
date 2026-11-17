package nl.diments.requestify.core.storage

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A value kept in a [JsonFileStore], the way `SettingsStore` keeps the settings. When the saved file cannot be
 * read at start, the value is the default and [loadFailed] is true; the default is never written over the saved
 * file. A change that cannot be saved is not applied.
 */
class PersistedState<T : Any>(private val file: JsonFileStore<T>, private val default: () -> T) {
    private val loaded: T? = try {
        file.read()
    } catch (_: StoreReadException) {
        null
    }
    private val state = MutableStateFlow(loaded ?: default())
    private val _loadFailed = MutableStateFlow(loaded == null)

    val value: StateFlow<T> = state.asStateFlow()

    /** True while the saved file could not be read and the default is used instead. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /**
     * Applies [f] to the current value and saves the result. [f] returns the same value when there is nothing to
     * change; nothing is written then. Returns false, with the value unchanged, when the file cannot be read (still)
     * or written; true otherwise.
     */
    @Synchronized
    fun change(f: (T) -> T): Boolean = try {
        if (_loadFailed.value) {
            state.value = file.read()
            _loadFailed.value = false
        }
        val next = f(state.value)
        if (next != state.value) {
            file.write(next)
            state.value = next
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
