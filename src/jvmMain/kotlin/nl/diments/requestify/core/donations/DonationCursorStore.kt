package nl.diments.requestify.core.donations

import kotlinx.serialization.Serializable
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.PersistedState

/**
 * How far a donation source has read: everything recorded before [since] (ISO-8601 UTC) is handled, and so are the
 * items recorded exactly at [since] whose ids are in [seenAtSince] (the platform's `since` is inclusive, so only
 * those can come back).
 */
@Serializable
data class DonationCursor(val since: String, val seenAtSince: Set<String> = emptySet())

/** Saved in donations.json; new fields need defaults. */
@Serializable
data class DonationCursors(val donoLink: DonationCursor? = null, val streamElements: DonationCursor? = null)

/** Each donation source's [DonationCursor], kept across restarts. */
class DonationCursorStore(file: JsonFileStore<DonationCursors>) {
    private val state = PersistedState(file) { DonationCursors() }

    fun get(kind: DonationSourceKind): DonationCursor? = when (kind) {
        DonationSourceKind.DonoLink -> state.value.value.donoLink
        DonationSourceKind.StreamElements -> state.value.value.streamElements
        DonationSourceKind.None -> null
    }

    /** False when it could not be saved (the source then keeps it in memory and saves it after its next page). */
    fun save(kind: DonationSourceKind, cursor: DonationCursor): Boolean = state.change { all ->
        when (kind) {
            DonationSourceKind.DonoLink -> all.copy(donoLink = cursor)
            DonationSourceKind.StreamElements -> all.copy(streamElements = cursor)
            DonationSourceKind.None -> all
        }
    }

    /** The streamer switched [kind] on: it starts at [nowIso], and nothing from while it was off is picked up. */
    fun resetToNow(kind: DonationSourceKind, nowIso: String): Boolean = save(kind, DonationCursor(nowIso))
}
