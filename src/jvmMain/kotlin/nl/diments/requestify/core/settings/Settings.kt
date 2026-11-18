package nl.diments.requestify.core.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.rules.Rules
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState
import nl.diments.requestify.core.storage.SaveContext
import nl.diments.requestify.core.storage.SaveProblem

@Serializable
data class Settings(
    val spotifyClientId: String? = null,
    val themeMode: ThemeMode = ThemeMode.Album,
    val darkMode: DarkMode = DarkMode.System,
    val language: AppLanguage = AppLanguage.System,
    val paidPriority: Boolean = true,
    val rules: Rules = Rules(),
    val twitch: TwitchSettings = TwitchSettings(),
    val donations: DonationSettings = DonationSettings(),
    /** The DonoLink expiry step last notified, so a restart does not notify it again. */
    val keyExpiryNotified: KeyExpiryNotice? = null,
)

/** A DonoLink key that expires at [expiresAtEpochMs] was notified at [stage]: 7 (within a week) or 1 (its last day). */
@Serializable
data class KeyExpiryNotice(val expiresAtEpochMs: Long, val stage: Int)

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
 * The app's settings ([PersistedState]): a change applies at once and is saved in the background. When the saved file
 * cannot be read at start (e.g. another program holds it), the app runs on the defaults and [loadFailed] is true; the
 * file is read again in the background, and the defaults are never written over it.
 */
class SettingsStore(file: JsonFileStore<Settings>, context: SaveContext) {
    private val state = PersistedState(file, context) { Settings() }

    val settings: StateFlow<Settings> get() = state.value

    /** True while the saved settings could not be read and the defaults are used instead. */
    val loadFailed: StateFlow<Boolean> get() = state.loadFailed

    /** Set while settings.json cannot be saved. */
    val saveProblem: StateFlow<SaveProblem?> get() = state.saveProblem

    /** Applies [f] at once. Throws [IllegalStateException] while the saved settings could not be read yet. */
    fun update(f: (Settings) -> Settings) {
        check(state.change(f)) { "The saved settings cannot be read yet" }
    }

    /** Like [update] for UI actions: false, with nothing changed, while the saved settings could not be read yet. */
    fun tryUpdate(f: (Settings) -> Settings): Boolean = state.change(f)

    /** Returns once every change made before this call is on disk. */
    suspend fun awaitSaved() = state.awaitSaved()

    /** [awaitSaved] with a deadline: true when everything was saved in time. */
    suspend fun flush(timeoutMs: Long): Boolean = state.flush(timeoutMs)
}
