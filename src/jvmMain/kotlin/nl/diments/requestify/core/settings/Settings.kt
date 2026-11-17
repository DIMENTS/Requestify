package nl.diments.requestify.core.settings

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.StoreReadException

@Serializable
data class Settings(
    val spotifyClientId: String? = null,
    val themeMode: ThemeMode = ThemeMode.Album,
    val darkMode: DarkMode = DarkMode.System,
    val language: AppLanguage = AppLanguage.System,
    val paidPriority: Boolean = true,
    val twitch: TwitchSettings = TwitchSettings(),
)

/** Saved in settings.json under the stable names below: never change them, or saved settings are lost. */
@Serializable
sealed interface ThemeMode {
    @Serializable
    @SerialName("album")
    data object Album : ThemeMode

    @Serializable
    @SerialName("custom")
    data class Custom(val seedArgb: Int) : ThemeMode
}

@Serializable
enum class DarkMode { System, Light, Dark }

@Serializable
enum class AppLanguage { System, English, Dutch }

/**
 * The app's settings. When the saved file cannot be read at start (e.g. another program holds it), the app runs
 * on the defaults and [loadFailed] is true; the defaults are never written over the saved file.
 */
class SettingsStore(private val file: JsonFileStore<Settings>) {
    private val loaded: Settings? = try {
        file.read()
    } catch (_: StoreReadException) {
        null
    }
    private val state = MutableStateFlow(loaded ?: Settings())
    private val _loadFailed = MutableStateFlow(loaded == null)

    val settings: StateFlow<Settings> = state.asStateFlow()

    /** True while the saved settings could not be read and the defaults are used instead. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /**
     * Applies [f], persists immediately, then emits the new value. Throws when the file cannot be written, or when
     * the saved settings still cannot be read: a change is only ever applied to (and saved over) what was saved.
     */
    @Synchronized
    fun update(f: (Settings) -> Settings) {
        if (_loadFailed.value) {
            state.value = file.read()
            _loadFailed.value = false
        }
        val next = f(state.value)
        file.write(next)
        state.value = next
    }

    /**
     * Like [update] for UI actions: returns false instead of throwing when the file cannot be written (e.g. another
     * program holds it for a moment). Nothing changes then, so the caller can say so and the user can try again.
     */
    fun tryUpdate(f: (Settings) -> Settings): Boolean = try {
        update(f)
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
