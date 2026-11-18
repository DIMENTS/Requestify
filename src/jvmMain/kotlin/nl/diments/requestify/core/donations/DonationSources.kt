package nl.diments.requestify.core.donations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.SettingsStore

/**
 * Runs exactly the donation source the settings name, for the app's whole life (with or without Twitch). Switching
 * stops the old source before the new one starts. [health] is the running source's health; [DonationHealth]'s default
 * while none runs.
 */
class DonationSources(
    private val settings: SettingsStore,
    private val donoLink: () -> DonationSource,
    private val streamElements: () -> DonationSource,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val built = HashMap<DonationSourceKind, DonationSource>()
    private val _health = MutableStateFlow(DonationHealth())
    val health: StateFlow<DonationHealth> = _health.asStateFlow()

    // Guarded by [lock].
    private var follower: Job? = null
    private var current: DonationSource? = null
    private var healthJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (follower?.isActive == true) return
            follower = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.settings.map { it.donations.source }.distinctUntilChanged().collect(::switchTo)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            follower?.cancel()
            follower = null
            switchTo(DonationSourceKind.None)
        }
    }

    /**
     * A new key or token was saved: the running source starts again with it (a refused or missing key stopped it). Its
     * cursor is kept: the source was on, so what came in meanwhile is caught up.
     */
    fun restartCurrent() {
        synchronized(lock) {
            val source = current ?: return
            source.stop()
            source.start()
        }
    }

    private fun switchTo(kind: DonationSourceKind) {
        synchronized(lock) {
            healthJob?.cancel()
            healthJob = null
            current?.stop()
            current = null
            _health.value = DonationHealth()
            if (kind == DonationSourceKind.None) return
            val source = built.getOrPut(kind) { if (kind == DonationSourceKind.DonoLink) donoLink() else streamElements() }
            current = source
            healthJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { source.health.collect { _health.value = it } }
            source.start()
        }
    }
}
