package nl.diments.requestify.core.twitch.points

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.twitch.RedemptionStatus
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult

/**
 * A Channel Points redemption status that Twitch still has to be told. [tries] counts the failed tries so far (it
 * sets the wait before the next one). Saved in redemptions.json: new fields need defaults.
 */
@Serializable
data class OutboxEntry(
    val rewardId: String,
    val redemptionId: String,
    val status: RedemptionStatus,
    val tries: Int = 0,
)

/**
 * Tells Twitch what became of Channel Points redemptions (fulfilled, or canceled: refunded), also across restarts, so a
 * viewer never loses points for a request that never played. Only for Requestify's own reward: Twitch refuses the
 * others.
 *
 * - **Saved first:** an entry is written to redemptions.json before Twitch is called. A write that fails keeps the
 *   entry in memory (it is still sent) and is retried on [backoffMs]. An unreadable file at start is never written
 *   over: its entries are merged in once it can be read (on [start], on the next change, or on that retry).
 * - **In order:** the oldest entry is sent first, the next one when it is done:
 *   - `Ok` or `NotFound` (already settled, e.g. in the dashboard, or the reward is gone) drops it; so does `Forbidden`
 *     (not a reward of this app).
 *   - `LoginNeeded` / `LoggedOut` stop sending until the next [start], keeping every entry.
 *   - `RateLimited` waits as long as Twitch asks, then everything goes on; it is not counted as a try.
 *   - `BadRequest` is about that entry alone: it counts a try and moves behind the others, which go on at once; it is
 *     tried again after its own backoff.
 *   - Anything else (Twitch or the network failing) counts a try and waits [backoffMs] by the entry's
 *     [OutboxEntry.tries] (then the last value again) before the same entry is tried again.
 * - **One per redemption:** a second entry for a redemption replaces the first one while that is not being sent; the
 *   same status again changes nothing.
 * - **A refund is final:** FULFILLED never replaces or follows a CANCELED of the same redemption that is still waiting
 *   here or on its way: the viewer was told the points came back. (Once a refund is in, Twitch itself refuses any
 *   later status, and [ChannelPointsSource] does not ask for one.)
 */
class RedemptionOutbox(
    private val file: JsonFileStore<List<OutboxEntry>>,
    private val api: TwitchApi,
    private val scope: CoroutineScope,
    private val backoffMs: List<Long> = listOf(5_000, 15_000, 60_000, 300_000), // then every 5 min
) {
    private val lock = Any()

    /** Null while the saved file could not be read. */
    private val initial: List<OutboxEntry>? = try {
        file.read()
    } catch (_: IOException) {
        null
    }

    // Guarded by [lock]. [list] is what [entries] shows. Entries are told apart by identity, so a newer entry for a
    // redemption never stands in for the one being sent or held.
    private var list: List<OutboxEntry> = initial.orEmpty()
    private var loaded = initial != null
    private var unsaved = false

    /** The entry being sent now: it is never replaced while it is. */
    private var inFlight: OutboxEntry? = null

    /** Entries Twitch refused as a bad request, waiting out their own backoff; skipped until then. */
    private val held: MutableSet<OutboxEntry> = Collections.newSetFromMap(IdentityHashMap())
    private var sender: Job? = null
    private var saver: Job? = null

    /** Redemptions whose entry was sent (or dropped as settled already, or not ours) in this run. */
    private val sent = RecentIds(SENT_IDS)

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _entries = MutableStateFlow(list)

    /** What is still to be sent, oldest first. */
    val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()

    /** Adds the status change, saved before it is sent. */
    fun enqueue(rewardId: String, redemptionId: String, status: RedemptionStatus) {
        val entry = OutboxEntry(rewardId, redemptionId, status)
        synchronized(lock) {
            change { current ->
                val waiting = current.indexOfFirst { it.redemptionId == redemptionId && it !== inFlight }
                val sending = inFlight?.takeIf { it.redemptionId == redemptionId }
                val refunding = current.any { it.redemptionId == redemptionId && it.status == RedemptionStatus.CANCELED }
                when {
                    status == RedemptionStatus.FULFILLED && refunding -> current // a refund is final
                    waiting >= 0 && current[waiting].status == status -> current
                    waiting >= 0 -> current.toMutableList().also { it[waiting] = entry }
                    sending?.status == status -> current // on its way already; a failure keeps it
                    else -> current + entry
                }
            }
        }
        wake.trySend(Unit)
    }

    /**
     * True when an entry for [redemptionId] was sent in this run, or dropped because Twitch had it settled already or
     * it is not this app's: whatever Twitch still lists for it is from before.
     */
    fun wasSent(redemptionId: String): Boolean = redemptionId in sent

    /** Starts sending (again), at once; does nothing while it is already sending. */
    fun start() {
        synchronized(lock) {
            if (!sync()) saveLater()
            if (sender?.isActive == true) return
            sender = scope.launch { sendAll() }
        }
    }

    /** Stops sending; every entry stays, saved. A call cut off here is sent again on the next [start]. */
    fun stop() {
        synchronized(lock) {
            sender?.cancel()
            sender = null
        }
    }

    private suspend fun sendAll() {
        while (true) {
            val head = synchronized(lock) { list.firstOrNull { it !in held }.also { inFlight = it } }
            if (head == null) {
                wake.receive()
                continue
            }
            val result = try {
                api.setRedemptionStatus(head.rewardId, head.redemptionId, head.status)
            } catch (e: CancellationException) {
                synchronized(lock) { if (inFlight === head) inFlight = null }
                throw e
            } catch (e: Exception) {
                TwitchResult.Failed(e::class.simpleName ?: "error") // never let one call end the sender
            }
            val waitMs = synchronized(lock) {
                if (inFlight === head) inFlight = null
                answered(head, result)
            } ?: return // until the next start
            if (waitMs > 0) delay(waitMs)
        }
    }

    /** How long everything waits before the next try: 0 for at once, null until the next [start]. Caller holds [lock]. */
    private fun answered(head: OutboxEntry, result: TwitchResult<Unit>): Long? = when (result) {
        is TwitchResult.Ok, TwitchResult.NotFound, is TwitchResult.Forbidden -> {
            sent.add(head.redemptionId)
            change { current -> current.filterNot { it === head } }
            0
        }
        TwitchResult.LoginNeeded, TwitchResult.LoggedOut -> null
        is TwitchResult.RateLimited -> maxOf(result.retryAfterMs, MIN_WAIT_MS)
        is TwitchResult.BadRequest -> {
            // Something about this entry alone: the others go first, it waits its own backoff.
            val retried = head.copy(tries = head.tries + 1)
            change { current -> if (current.none { it === head }) current else current.filterNot { it === head } + retried }
            hold(retried, backoffFor(retried.tries))
            0
        }
        is TwitchResult.Failed -> {
            val retried = head.copy(tries = head.tries + 1)
            change { current -> current.map { if (it === head) retried else it } }
            backoffFor(retried.tries)
        }
    }

    private fun backoffFor(tries: Int): Long = backoffMs.getOrElse(tries - 1) { backoffMs.lastOrNull() ?: DEFAULT_BACKOFF_MS }

    /** Skips [entry] for [ms]. Caller holds [lock]. */
    private fun hold(entry: OutboxEntry, ms: Long) {
        held += entry
        scope.launch {
            delay(ms)
            synchronized(lock) { held -= entry }
            wake.trySend(Unit)
        }
    }

    /** Applies [f] and saves the result, or keeps it in memory and saves it later. Caller holds [lock]. */
    private fun change(f: (List<OutboxEntry>) -> List<OutboxEntry>) {
        val next = f(list)
        if (next == list) return
        list = next
        _entries.value = next
        unsaved = true
        if (!sync()) saveLater()
    }

    /**
     * Reads the saved file if it could not be read before (its entries go first: they are older; for a redemption in
     * both, the one in memory wins), then writes what is unsaved. True when everything is saved. Caller holds [lock].
     */
    private fun sync(): Boolean {
        if (!loaded) {
            val saved = try {
                file.read()
            } catch (_: IOException) {
                return false
            }
            val ourIds = list.mapTo(HashSet()) { it.redemptionId }
            list = saved.filter { it.redemptionId !in ourIds } + list
            _entries.value = list
            loaded = true
            if (list != saved) unsaved = true
            if (list.isNotEmpty()) wake.trySend(Unit)
        }
        if (!unsaved) return true
        return try {
            file.write(list)
            unsaved = false
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Retries [sync] on [backoffMs] (then the last value again) until it succeeds. Caller holds [lock]. */
    private fun saveLater() {
        if (saver?.isActive == true) return
        saver = scope.launch {
            var attempt = 0
            while (true) {
                delay(backoffMs.getOrElse(attempt++) { backoffMs.lastOrNull() ?: DEFAULT_BACKOFF_MS })
                if (synchronized(lock) { sync() }) return@launch
            }
        }
    }

    private companion object {
        const val DEFAULT_BACKOFF_MS = 300_000L

        /** A rate limit that is already over still waits this long, so the sender cannot spin. */
        const val MIN_WAIT_MS = 1_000L
        const val SENT_IDS = 1_000
    }
}
