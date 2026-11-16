package nl.diments.requestify.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.JsonFileStore

@Serializable
data class Settings(
    val spotifyClientId: String? = null,
    val themeMode: ThemeMode = ThemeMode.Album,
    val darkMode: DarkMode = DarkMode.System,
    val language: AppLanguage = AppLanguage.System,
    val paidPriority: Boolean = true,
)

@Serializable
sealed interface ThemeMode {
    @Serializable
    data object Album : ThemeMode

    @Serializable
    data class Custom(val seedArgb: Int) : ThemeMode
}

@Serializable
enum class DarkMode { System, Light, Dark }

@Serializable
enum class AppLanguage { System, English, Dutch }

class SettingsStore(private val file: JsonFileStore<Settings>) {
    private val state = MutableStateFlow(file.read())
    val settings: StateFlow<Settings> = state.asStateFlow()

    /** Applies [f], persists immediately, then emits the new value. */
    @Synchronized
    fun update(f: (Settings) -> Settings) {
        val next = f(state.value)
        file.write(next)
        state.value = next
    }
}
