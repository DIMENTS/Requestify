package nl.diments.requestify.ui.welcome

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.WizardStep

/** What the wizard connects, one card each. */
enum class WizardPlatform { Spotify, Twitch, Donations }

/**
 * The welcome wizard as the window shows it. [connected]: badges at the top; [later]: skipped with "Later doen";
 * [celebrating]: the platform that just connected on its own card (the UI celebrates, then calls
 * [WelcomeWizardViewModel.celebrated]); [remaining]: platforms neither connected nor skipped ("Nog 2 te gaan").
 */
data class WizardState(
    val visible: Boolean,
    val step: WizardStep,
    val connected: Set<WizardPlatform>,
    val later: Set<WizardPlatform>,
    val celebrating: WizardPlatform?,
    val remaining: Int,
)

/**
 * The welcome wizard's steps. It shows until it is finished or skipped ([SettingsStore]'s `welcomeDone`), never while
 * the settings cannot be read, and never unasked to someone who set Requestify up before it existed
 * ([markExistingUser]). Its card is kept, so a window closed halfway resumes there; a card whose platform is connected
 * meanwhile (on the Connections page) is skipped. The connecting itself is the Connections page's logic.
 */
class WelcomeWizardViewModel(
    private val settings: SettingsStore,
    private val spotifyConnected: StateFlow<Boolean>,
    private val twitchConnected: StateFlow<Boolean>,
    private val donationsConnected: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    /** False: never shown (tests and renders of the rest of the app). */
    private val enabled: Boolean = true,
    /** Logged in as an Affiliate or Partner without the Channel Points reward yet: the Twitch card stays for it. */
    private val twitchOffersPoints: StateFlow<Boolean> = MutableStateFlow(false),
) {
    /** Platforms celebrated in this round: one that stays on screen (Channel Points) is not celebrated again. */
    private val celebratedAlready = mutableSetOf<WizardPlatform>()
    private fun connectedNow(): Set<WizardPlatform> = buildSet {
        if (spotifyConnected.value) add(WizardPlatform.Spotify)
        if (twitchConnected.value) add(WizardPlatform.Twitch)
        if (donationsConnected.value) add(WizardPlatform.Donations)
    }

    private val connected: StateFlow<Set<WizardPlatform>> =
        combine(spotifyConnected, twitchConnected, donationsConnected) { _, _, _ -> connectedNow() }
            .stateIn(scope, SharingStarted.Eagerly, connectedNow())

    private val step = MutableStateFlow(resumeAt(settings.settings.value.welcomeStep ?: WizardStep.Welcome))
    private val later = MutableStateFlow(emptySet<WizardPlatform>())
    private val celebrating = MutableStateFlow<WizardPlatform?>(null)

    val state: StateFlow<WizardState> = combine(
        combine(settings.settings, settings.loadFailed) { s, failed -> enabled && !s.welcomeDone && !failed },
        step, connected, later, celebrating,
    ) { visible, step, connected, later, celebrating ->
        WizardState(visible, step, connected, later, celebrating, WizardPlatform.entries.count { it !in connected && it !in later })
    }.stateIn(scope, SharingStarted.Eagerly, snapshot())

    init {
        // The platform of the card on screen connected: celebrate it there before moving on.
        scope.launch {
            connected.collect { now ->
                val here = step.value.platform
                if (here != null && here in now && here !in celebratedAlready && celebrating.value == null && state.value.visible) {
                    celebratedAlready += here
                    celebrating.value = here
                }
            }
        }
    }

    /** Welcome → the first card whose platform is not connected yet. */
    fun begin() = moveTo(next(WizardStep.Welcome))

    /** "Later doen" (also "Geen" donations): on to the next card, without a badge. */
    fun later() {
        step.value.platform?.let { later.value += it }
        moveTo(next(step.value))
    }

    /**
     * The UI finished celebrating a connection: on to the next card. An Affiliate or Partner stays on the Twitch card,
     * which now shows the Channel Points reward, until "Verder" ([continueOn]).
     */
    fun celebrated() {
        val celebrated = celebrating.value
        celebrating.value = null
        if (celebrated == WizardPlatform.Twitch && twitchOffersPoints.value) return
        moveTo(next(step.value))
    }

    /** "Verder" on a card whose platform is connected: on to the next card (no "later" for it). */
    fun continueOn() = moveTo(next(step.value))

    /** "Overslaan" on the welcome card. */
    fun skipAll() = end()

    /** "Requestify openen" on the Klaar card. */
    fun finish() = end()

    /** Settings → Over Requestify → "Welkomstwizard openen": a fresh round from the welcome card. */
    fun reopen() {
        later.value = emptySet()
        celebratedAlready.clear()
        celebrating.value = null
        step.value = WizardStep.Welcome
        settings.tryUpdate { it.copy(welcomeDone = false, welcomeStep = WizardStep.Welcome) }
    }

    private fun end() {
        celebrating.value = null
        settings.tryUpdate { it.copy(welcomeDone = true, welcomeStep = null) }
    }

    private fun moveTo(next: WizardStep) {
        step.value = next
        settings.tryUpdate { it.copy(welcomeStep = next) }
    }

    /** The next card after [from] whose platform is not connected; the Klaar card at the end. */
    private fun next(from: WizardStep): WizardStep =
        WizardStep.entries.drop(from.ordinal + 1).first { it.platform == null || it.platform !in connectedNow() }

    /** Where a saved wizard resumes: its card, unless that platform got connected meanwhile. */
    private fun resumeAt(saved: WizardStep): WizardStep =
        if (saved.platform != null && saved.platform in connectedNow()) next(saved) else saved

    private fun snapshot() = WizardState(
        visible = enabled && !settings.settings.value.welcomeDone && !settings.loadFailed.value,
        step = step.value,
        connected = connectedNow(),
        later = emptySet(),
        celebrating = null,
        remaining = WizardPlatform.entries.count { it !in connectedNow() },
    )

    companion object {
        /**
         * Once at start: someone who set Requestify up before the wizard existed (any connection saved, and no wizard
         * started) never gets it unasked. A wizard in progress is left alone.
         */
        fun markExistingUser(settings: SettingsStore, anyConnectionSaved: Boolean) {
            val s = settings.settings.value
            if (!s.welcomeDone && s.welcomeStep == null && anyConnectionSaved) settings.tryUpdate { it.copy(welcomeDone = true) }
        }
    }
}

/** The platform a card connects; null for Welcome and Klaar. */
val WizardStep.platform: WizardPlatform?
    get() = when (this) {
        WizardStep.Spotify -> WizardPlatform.Spotify
        WizardStep.Twitch -> WizardPlatform.Twitch
        WizardStep.Donations -> WizardPlatform.Donations
        WizardStep.Welcome, WizardStep.Done -> null
    }
