package nl.diments.requestify.ui.setup

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.donolink.DonoLinkFeed
import nl.diments.requestify.core.donations.donolink.DonoLinkResult
import nl.diments.requestify.core.donations.streamelements.SeResult
import nl.diments.requestify.core.donations.streamelements.StreamElementsApi
import nl.diments.requestify.core.secrets.SecretKeys
import nl.diments.requestify.core.secrets.SecretStore
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore

/** The last test of the donation source's key. */
sealed interface DonationTest {
    data object Idle : DonationTest
    data object Testing : DonationTest

    /** The key works: it belongs to [name]; a DonoLink key stops working at [keyExpiresAtEpochMs]. */
    data class Ok(val name: String, val keyExpiresAtEpochMs: Long?) : DonationTest

    data object Refused : DonationTest
    data class Failed(val message: String) : DonationTest
}

/** What the Donations card shows. [keyDaysLeft]: whole days until the DonoLink key expires, when known. */
data class DonationsCardState(
    val kind: DonationSourceKind,
    val hasKey: Boolean,
    val test: DonationTest,
    val minimumCents: Long,
    val health: DonationHealth,
    val keyDaysLeft: Int?,
)

/**
 * The Donations card: one source at a time (none, DonoLink or StreamElements). Switching a source on starts it at
 * "now" (nothing from while it was off is picked up); the other source's key stays saved. A pasted key is saved
 * (Windows-protected) and tested: DonoLink's owner and expiry, StreamElements' channel.
 */
class DonationsSetupViewModel(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val cursors: DonationCursorStore,
    private val donoLink: DonoLinkFeed,
    private val streamElements: StreamElementsApi,
    health: StateFlow<DonationHealth>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val openBrowser: (String) -> Boolean,
) {
    private val test = MutableStateFlow<DonationTest>(DonationTest.Idle)

    /** Bumped when a key is saved, so [DonationsCardState.hasKey] is read again. */
    private val keysChanged = MutableStateFlow(0)

    val state: StateFlow<DonationsCardState> = combine(settings.settings, test, health, keysChanged) { s, t, h, _ -> stateOf(s, t, h) }
        .stateIn(scope, SharingStarted.Eagerly, stateOf(settings.settings.value, test.value, health.value))

    private fun stateOf(s: Settings, t: DonationTest, h: DonationHealth): DonationsCardState {
        val kind = s.donations.source
        val expires = (t as? DonationTest.Ok)?.keyExpiresAtEpochMs ?: h.keyExpiresAtEpochMs.takeIf { h.kind == kind }
        return DonationsCardState(
            kind = kind,
            hasKey = keyName(kind)?.let { secrets.get(it) } != null,
            test = t,
            minimumCents = if (kind == DonationSourceKind.StreamElements) s.donations.streamElementsMinimumCents else s.donations.donoLinkMinimumCents,
            // Before its source runs: StreamElements is live-only (no confirmed way to fetch tips made meanwhile).
            health = if (h.kind == kind) h else DonationHealth(kind, canCatchUp = kind != DonationSourceKind.StreamElements),
            keyDaysLeft = expires?.let { ((it - clock()) / DAY_MS).toInt().coerceAtLeast(0) },
        )
    }

    fun choose(kind: DonationSourceKind) {
        if (kind == settings.settings.value.donations.source) return
        // Switched on: from now on. Saved before the source starts, so it never catches up the time it was off.
        if (kind != DonationSourceKind.None) cursors.resetToNow(kind, Instant.ofEpochMilli(clock()).toString())
        if (!settings.tryUpdate { it.copy(donations = it.donations.copy(source = kind)) }) return
        test.value = DonationTest.Idle
        if (keyName(kind)?.let { secrets.get(it) } != null) runTest(kind)
    }

    /** Saves [text] as the chosen source's key and tests it. */
    fun pasteKey(text: String) {
        val kind = settings.settings.value.donations.source
        val name = keyName(kind) ?: return
        val key = text.trim().takeIf { it.isNotEmpty() } ?: return
        try {
            secrets.put(name, key)
        } catch (e: Exception) {
            test.value = DonationTest.Failed(e.message ?: "could not save")
            return
        }
        keysChanged.value++
        runTest(kind)
    }

    /** False (nothing changes) for less than one cent. */
    fun setMinimum(cents: Long): Boolean {
        if (cents < 1) return false
        return settings.tryUpdate {
            val d = it.donations
            it.copy(
                donations = if (d.source == DonationSourceKind.StreamElements) d.copy(streamElementsMinimumCents = cents) else d.copy(donoLinkMinimumCents = cents),
            )
        }
    }

    fun openDashboard() {
        when (settings.settings.value.donations.source) {
            DonationSourceKind.DonoLink -> openBrowser(DONOLINK_DASHBOARD)
            DonationSourceKind.StreamElements -> openBrowser(STREAMELEMENTS_DASHBOARD)
            DonationSourceKind.None -> Unit
        }
    }

    private fun runTest(kind: DonationSourceKind) {
        test.value = DonationTest.Testing
        scope.launch {
            test.value = when (kind) {
                DonationSourceKind.DonoLink -> when (val me = donoLink.me()) {
                    is DonoLinkResult.Ok -> DonationTest.Ok(me.value.ownerName, me.value.keyExpiresAtEpochMs)
                    is DonoLinkResult.KeyRefused -> DonationTest.Refused
                    is DonoLinkResult.RateLimited -> DonationTest.Failed("rate limited")
                    is DonoLinkResult.Failed -> DonationTest.Failed(me.message)
                }
                DonationSourceKind.StreamElements -> when (val channel = streamElements.channel()) {
                    is SeResult.Ok -> DonationTest.Ok(channel.value.name, null)
                    SeResult.Refused -> DonationTest.Refused
                    is SeResult.Failed -> DonationTest.Failed(channel.message)
                }
                DonationSourceKind.None -> DonationTest.Idle
            }
        }
    }

    private fun keyName(kind: DonationSourceKind): String? = when (kind) {
        DonationSourceKind.DonoLink -> SecretKeys.DONOLINK_KEY
        DonationSourceKind.StreamElements -> SecretKeys.STREAMELEMENTS_JWT
        DonationSourceKind.None -> null
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
        const val DONOLINK_DASHBOARD = "https://donolink.nl/dashboard"
        const val STREAMELEMENTS_DASHBOARD = "https://streamelements.com/dashboard/account/channels"
    }
}
