package nl.diments.requestify.core.log

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.health.KEY_WARNING_MS
import nl.diments.requestify.core.settings.DonationSourceKind

/**
 * The donation source's connection as the Log sees it: its state per platform, and, when the streamer switches to
 * another source (or to none), a [ConnectionSnapshot.SourceChanged] for the one left, so its open outage ends there.
 */
fun donationConnections(health: Flow<DonationHealth>, now: () -> Long): Flow<Pair<LogService, ConnectionSnapshot>> = flow {
    var last: LogService? = null
    health.collect { h ->
        val service = h.service()
        if (last != null && last != service) emit(last!! to ConnectionSnapshot.SourceChanged)
        if (service != null) emit(service to h.toSnapshot(now()))
        last = service
    }
}

private fun DonationHealth.service(): LogService? = when (kind) {
    DonationSourceKind.DonoLink -> LogService.DonoLink
    DonationSourceKind.StreamElements -> LogService.StreamElements
    DonationSourceKind.None -> null
}

/** A key about to expire is news once, like a lost connection. */
private fun DonationHealth.toSnapshot(nowMs: Long): ConnectionSnapshot = when {
    status == DonationStatus.KeyRefused -> ConnectionSnapshot.KeyRefused
    status == DonationStatus.Retrying -> ConnectionSnapshot.Down
    keyExpiresAtEpochMs?.let { it - nowMs <= KEY_WARNING_MS } == true -> ConnectionSnapshot.KeyExpiring
    else -> ConnectionSnapshot.Up
}
