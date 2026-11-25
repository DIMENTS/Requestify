package nl.diments.requestify.core.spotify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps every Spotify Web API call from going out while Spotify asked to wait, whoever makes it, and counts the calls.
 * [clock] is wall time, so a wait also passes while the PC sleeps; one set back ends the wait.
 *
 * Spotify limits a Development Mode app twice, and publishes neither number: a rolling 30 s window over the whole app,
 * and a quota per developer account in buckets of endpoints (429 with the reason `QUOTA_EXCEEDED`). Which endpoints
 * share a bucket is not known, so a quota holds only the endpoint it was answered for. An endpoint is a method and a
 * path, like `GET me/player/queue`. Thread-safe.
 */
class SpotifyCallGate(private val clock: () -> Long = System::currentTimeMillis) {
    private class Hold(val endpoint: String, val fromMs: Long, val untilMs: Long, val quota: Boolean, val detail: String) {
        fun leftAt(now: Long): Long = if (now in fromMs until untilMs) untilMs - now else 0

        fun lingersAt(now: Long): Boolean = now in fromMs until untilMs + LINGER_MS
    }

    private var appHold: Hold? = null
    private val endpointHolds = HashMap<String, Hold>()

    private val _limit = MutableStateFlow<String?>(null)

    /**
     * The limit Spotify holds, as [SpotifyResult.RateLimited.detail] says it; null while it holds none. It stays a moment
     * ([LINGER_MS]) after its wait, until a call to the same endpoint is taken, so a limit that is reached again at once
     * is the same one. Looked at again with every call that is made.
     */
    val limit: StateFlow<String?> = _limit.asStateFlow()

    /** How often in a row each endpoint was refused. */
    private val refusals = HashMap<String, Int>()

    /** When the calls of the last 24 h went out. */
    private val sentAt = ArrayDeque<Long>()

    /** The rest of the wait before [endpoint] may be called; null when it may be now. */
    @Synchronized
    fun heldFor(endpoint: String): SpotifyResult.RateLimited? {
        val now = clock()
        forgetOldHolds()
        return listOfNotNull(appHold, endpointHolds[endpoint])
            .filter { it.leftAt(now) > 0 }
            .maxByOrNull { it.leftAt(now) }
            ?.let { SpotifyResult.RateLimited(it.leftAt(now), it.quota, it.detail) }
    }

    /** A call goes out. */
    @Synchronized
    fun sent() {
        sentAt.addLast(clock())
        forgetOldCalls()
    }

    /** Spotify answered a call to [endpoint] with anything but a 429. */
    @Synchronized
    fun taken(endpoint: String) {
        refusals.remove(endpoint)
        // An answer to a call sent before the refusal ends no wait.
        val now = clock()
        if (endpointHolds[endpoint]?.leftAt(now) == 0L) endpointHolds.remove(endpoint)
        if (appHold?.endpoint == endpoint && appHold?.leftAt(now) == 0L) appHold = null
        forgetOldHolds()
    }

    /**
     * Spotify refused a call to [endpoint] with a 429: how long it is held now. [retryAfterMs] is Spotify's own wait;
     * null or 0 for none, then it grows with every refusal in a row. [quota]: Spotify named its quota as the reason. A
     * wait the 30 s window cannot explain is a quota too, named or not.
     */
    @Synchronized
    fun refused(endpoint: String, retryAfterMs: Long?, quota: Boolean): SpotifyResult.RateLimited {
        val now = clock()
        val before = refusals[endpoint] ?: 0
        refusals[endpoint] = before + 1
        val named = retryAfterMs?.takeIf { it > 0 }
        val wait = named ?: grown(if (quota) QUOTA_FIRST_WAIT_MS else FIRST_WAIT_MS, before)
        val isQuota = quota || (named != null && named > LONGEST_WINDOW_WAIT_MS)
        val hold = Hold(endpoint, now, now + wait, isQuota, detail(endpoint, named, quota))
        if (isQuota) {
            endpointHolds[endpoint] = longer(endpointHolds[endpoint], hold, now)
        } else {
            appHold = longer(appHold, hold, now)
        }
        _limit.value = hold.detail
        return SpotifyResult.RateLimited(wait, isQuota, hold.detail)
    }

    /** A new login may be of another developer account: every wait is lifted. */
    @Synchronized
    fun reset() {
        appHold = null
        endpointHolds.clear()
        refusals.clear()
        _limit.value = null
    }

    private fun longer(old: Hold?, new: Hold, now: Long): Hold =
        if (old != null && old.leftAt(now) > new.leftAt(now)) old else new

    private fun grown(firstMs: Long, times: Int): Long =
        (firstMs shl times.coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(LONGEST_OWN_WAIT_MS)

    private fun detail(endpoint: String, retryAfterMs: Long?, quota: Boolean): String {
        forgetOldCalls()
        val now = clock()
        val recent = sentAt.count { now - it in 0..WINDOW_MS }
        val reason = if (quota) "QUOTA_EXCEEDED" else "HTTP 429"
        val wait = retryAfterMs?.let { "Retry-After ${it / 1000} s" } ?: "no Retry-After"
        return "$reason, $endpoint, $wait, $recent calls in 30 s, ${sentAt.size} in 24 h"
    }

    private fun forgetOldHolds() {
        val now = clock()
        if (appHold?.lingersAt(now) == false) appHold = null
        endpointHolds.values.removeIf { !it.lingersAt(now) }
        val holds = listOfNotNull(appHold) + endpointHolds.values
        if (holds.none { it.detail == _limit.value }) _limit.value = holds.maxByOrNull { it.fromMs }?.detail
    }

    private fun forgetOldCalls() {
        val now = clock()
        while (sentAt.isNotEmpty() && now - sentAt.first() > DAY_MS) sentAt.removeFirst()
    }

    private companion object {
        const val WINDOW_MS = 30_000L
        const val DAY_MS = 24 * 3_600_000L
        const val FIRST_WAIT_MS = 5_000L
        const val QUOTA_FIRST_WAIT_MS = 60_000L
        const val LONGEST_OWN_WAIT_MS = 900_000L

        /** A wait for the rolling 30 s window is never longer than this. */
        const val LONGEST_WINDOW_WAIT_MS = 60_000L
        const val MAX_DOUBLINGS = 20
        const val LINGER_MS = 120_000L
    }
}
