package nl.diments.requestify.core.twitch.points

import java.io.IOException
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
 * - **In order:** the oldest entry is sent first, the next one only when it is done. `Ok` or `NotFound` (already
 *   settled, e.g. in the dashboard, or the reward is gone) drops it; so does `Forbidden` (not a reward of this app).
 *   `LoginNeeded` / `LoggedOut` stop sending until the next [start], keeping every entry. Anything else waits
 *   [backoffMs] by the entry's [OutboxEntry.tries] (then the last value again; never less than a rate limit asks for)
 *   and tries the same entry again.
 * - **One per redemption:** a second entry for a redemption replaces the first one while that is not being sent; the
 *   same status again changes nothing.
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
    // redemption never stands in for the one being sent.
    private var list: List<OutboxEntry> = initial.orEmpty()
    private var loaded = initial != null
    private var unsaved = false

    /** The entry being sent now: it is never replaced while it is. */
    private var inFlight: OutboxEntry? = null
    private var sender: Job? = null
    private var saver: Job? = null

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
                when {
                    waiting >= 0 && current[waiting].status == status -> current
                    waiting >= 0 -> current.toMutableList().also { it[waiting] = entry }
                    sending?.status == status -> current // on its way already; a failure keeps it
                    else -> current + entry
                }
            }
        }
        wake.trySend(Unit)
    }

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
            val head = synchronized(lock) { list.firstOrNull().also { inFlight = it } }
            if (head == null) {
                wake.receive()
                continue
            }
            val result = try {
                api.setRedemptionStatus(head.rewardId, head.redemptionId, head.status)
            } catch (e: CancellationException) {
                synchronized(lock) { inFlight = null }
                throw e
            } catch (e: Exception) {
                TwitchResult.Failed(e::class.simpleName ?: "error") // never let one call end the sender
            }
            val waitMs = synchronized(lock) {
                inFlight = null
                answered(head, result)
            } ?: return // until the next start
            if (waitMs > 0) delay(waitMs)
        }
    }

    /** How long to wait before the next try: 0 for at once, null until the next [start]. Caller holds [lock]. */
    private fun answered(head: OutboxEntry, result: TwitchResult<Unit>): Long? = when (result) {
        is TwitchResult.Ok, TwitchResult.NotFound, is TwitchResult.Forbidden -> {
            change { current -> current.filterNot { it === head } }
            0
        }
        TwitchResult.LoginNeeded, TwitchResult.LoggedOut -> null
        is TwitchResult.BadRequest, is TwitchResult.RateLimited, is TwitchResult.Failed -> {
            val tries = head.tries + 1
            change { current -> current.map { if (it === head) it.copy(tries = tries) else it } }
            val backoff = backoffMs.getOrElse(tries - 1) { backoffMs.lastOrNull() ?: DEFAULT_BACKOFF_MS }
            if (result is TwitchResult.RateLimited) maxOf(backoff, result.retryAfterMs) else backoff
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
    }
}
