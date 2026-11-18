package nl.diments.requestify.core.donations.streamelements

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.donations.DonationCursor
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationSource
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.log.LogRecord
import nl.diments.requestify.core.model.RequestSource
import nl.diments.requestify.core.settings.DonationSourceKind

/**
 * StreamElements tips as requests, live only: no confirmed way exists to fetch tips made while Requestify could not
 * hear them (docs/superpowers/specs/2026-11-18-streamelements-api-notes.md), so every such time is reported on [gaps]
 * for the Log instead: a lost connection, and the time the app was off (from when it was last connected, kept in the
 * cursor store every minute and at [stop]). A blip shorter than a minute is no gap.
 */
class StreamElementsSource(
    private val feed: AstroFeed,
    private val handle: suspend (Donation) -> Unit,
    private val cursors: DonationCursorStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : DonationSource {
    private val lock = Any()
    private var job: Job? = null

    private val _health = MutableStateFlow(DonationHealth(DonationSourceKind.StreamElements, canCatchUp = false))
    override val health: StateFlow<DonationHealth> = _health.asStateFlow()

    private val _gaps = MutableSharedFlow<LogRecord.GapNoticed>(extraBufferCapacity = Int.MAX_VALUE)
    val gaps: SharedFlow<LogRecord.GapNoticed> = _gaps.asSharedFlow()

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            // Off since the last connected moment of an earlier run (none on the very first start).
            val lostAt = cursors.get(DonationSourceKind.StreamElements)?.since?.let { Instant.parse(it).toEpochMilli() }
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) { feed.tips.collect(::onTip) }
                launch(start = CoroutineStart.UNDISPATCHED) { followState(lostAt) }
            }
            feed.start()
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (feed.state.value == AstroState.Connected) remember()
            job?.cancel()
            job = null
            feed.stop()
            _health.value = health.value.copy(status = DonationStatus.Off, retryingSinceMs = null)
        }
    }

    private suspend fun onTip(data: JsonObject) {
        val donation = parseStreamElementsTip(data, clock()) ?: return
        try {
            handle(donation)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // nothing to retry with: the Log shows the request did not come in
        }
    }

    private suspend fun followState(offSince: Long?) {
        var lostAt = offSince
        var heartbeat: Job? = null
        coroutineScope {
            feed.state.collect { state ->
                when (state) {
                    AstroState.Connected -> {
                        val now = clock()
                        lostAt?.let { if (now - it >= MIN_GAP_MS) _gaps.tryEmit(gap(it, now)) }
                        lostAt = null
                        set(DonationStatus.Connected, null)
                        heartbeat?.cancel()
                        heartbeat = launch {
                            while (true) {
                                remember()
                                delay(HEARTBEAT_MS)
                            }
                        }
                    }
                    is AstroState.Disconnected -> {
                        heartbeat?.cancel()
                        if (lostAt == null) lostAt = state.sinceEpochMs
                        set(DonationStatus.Retrying, state.sinceEpochMs)
                    }
                    AstroState.Refused -> {
                        heartbeat?.cancel()
                        set(DonationStatus.KeyRefused, null)
                    }
                    AstroState.Connecting -> set(DonationStatus.Connecting, null)
                    AstroState.Stopped -> heartbeat?.cancel()
                }
            }
        }
    }

    private fun set(status: DonationStatus, retryingSince: Long?) {
        _health.value = health.value.copy(status = status, retryingSinceMs = retryingSince)
    }

    /** Keeps "connected until now", so the next start knows since when the app was off. */
    private fun remember() {
        cursors.save(DonationSourceKind.StreamElements, DonationCursor(Instant.ofEpochMilli(clock()).toString()))
    }

    private fun gap(from: Long, to: Long) =
        LogRecord.GapNoticed("gap:${RequestSource.STREAMELEMENTS.name}:$to", to, RequestSource.STREAMELEMENTS, from, to)

    private companion object {
        const val HEARTBEAT_MS = 60_000L
        const val MIN_GAP_MS = 60_000L
    }
}
