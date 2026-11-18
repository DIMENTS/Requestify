package nl.diments.requestify.core.donations.donolink

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.diments.requestify.core.donations.Donation
import nl.diments.requestify.core.donations.DonationCursor
import nl.diments.requestify.core.donations.DonationCursorStore
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationSource
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.settings.DonationSourceKind

/**
 * DonoLink donations as requests. DonoLink has no push, so this asks every [pollMs] for what was recorded since the
 * saved [DonationCursor] and hands every new donation to [handle], oldest first, before the cursor moves past it: a
 * donation is handed over at least once (the handler drops one it already knows), never skipped. The very first start
 * begins at "now"; after that, a restart catches up from the saved cursor, page by page.
 *
 * A refused key stops it until the next [start] ([DonationStatus.KeyRefused]); a rate limit waits as long as DonoLink
 * asks; anything else is tried again after 5 s, doubling to 60 s ([DonationStatus.Retrying]). The key's expiry is read
 * at start and once a day.
 */
class DonoLinkSource(
    private val feed: DonoLinkFeed,
    private val handle: suspend (Donation) -> Unit,
    private val cursors: DonationCursorStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val pollMs: Long = 5_000,
) : DonationSource {
    private val lock = Any()
    private var job: Job? = null
    private val _health = MutableStateFlow(DonationHealth(DonationSourceKind.DonoLink))
    override val health: StateFlow<DonationHealth> = _health.asStateFlow()

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            _health.value = health.value.copy(status = DonationStatus.Connecting, retryingSinceMs = null)
            job = scope.launch { run() }
        }
    }

    override fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            _health.value = health.value.copy(status = DonationStatus.Off, retryingSinceMs = null)
        }
    }

    private sealed interface Polled {
        data object Done : Polled
        data object Refused : Polled
        data class Wait(val ms: Long) : Polled
        data object Failed : Polled
    }

    private suspend fun run() {
        var cursor = cursors.get(DonationSourceKind.DonoLink)
            ?: DonationCursor(Instant.ofEpochMilli(clock()).toString()).also { cursors.save(DonationSourceKind.DonoLink, it) }
        var nextMeAt = 0L
        var failures = 0
        while (true) {
            if (clock() >= nextMeAt) {
                when (val me = guarded { feed.me() }) {
                    is DonoLinkResult.Ok -> {
                        _health.value = health.value.copy(keyExpiresAtEpochMs = me.value.keyExpiresAtEpochMs)
                        nextMeAt = clock() + DAY_MS
                    }
                    is DonoLinkResult.KeyRefused -> return refused()
                    else -> nextMeAt = clock() + HOUR_MS
                }
            }
            // Whatever goes wrong unexpectedly is one more failure, tried again with the backoff: never the end.
            val (polled, moved) = try {
                poll(cursor)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Polled.Failed to (cursors.get(DonationSourceKind.DonoLink) ?: cursor)
            }
            cursor = moved
            when (polled) {
                Polled.Done -> {
                    failures = 0
                    _health.value = health.value.copy(status = DonationStatus.Connected, retryingSinceMs = null)
                    delay(pollMs)
                }
                Polled.Refused -> return refused()
                is Polled.Wait -> delay(polled.ms)
                Polled.Failed -> {
                    if (health.value.status != DonationStatus.Retrying) {
                        _health.value = health.value.copy(status = DonationStatus.Retrying, retryingSinceMs = clock())
                    }
                    delay(BACKOFF_MS.getOrElse(failures) { BACKOFF_MS.last() })
                    failures++
                }
            }
        }
    }

    private fun refused() {
        _health.value = health.value.copy(status = DonationStatus.KeyRefused, retryingSinceMs = null)
    }

    private suspend fun <T> guarded(call: suspend () -> DonoLinkResult<T>): DonoLinkResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DonoLinkResult.Failed(e.message ?: e::class.simpleName ?: "error")
    }

    /**
     * Reads every page recorded since [start]'s cursor and hands each new donation over; returns how it ended and the
     * cursor as far as it got (saved after each page).
     */
    private suspend fun poll(start: DonationCursor): Pair<Polled, DonationCursor> {
        var cursor = start
        // DonoLink's times may come with a space instead of the T: read leniently, like every other time from it.
        val startMs = parseInstant(start.since) ?: clock()
        var pageCursor: String? = null
        while (true) {
            val page = when (val answer = feed.activity(start.since, pageCursor)) {
                is DonoLinkResult.Ok -> answer.value
                is DonoLinkResult.KeyRefused -> return Polled.Refused to cursor
                is DonoLinkResult.RateLimited -> return Polled.Wait(answer.retryAfterMs) to cursor
                is DonoLinkResult.Failed -> return Polled.Failed to cursor
            }
            for (item in page.items) {
                val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val recorded = recordedAtOf(item) ?: continue
                val recordedMs = parseInstant(recorded) ?: continue
                val cursorMs = parseInstant(cursor.since) ?: startMs
                if (recordedMs < cursorMs || (recordedMs == cursorMs && id in cursor.seenAtSince)) continue
                parseDonoLinkDonation(item, clock())?.let { donation ->
                    try {
                        handle(donation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return Polled.Failed to cursor // not past it: it is handed over again next time
                    }
                }
                cursor = if (recordedMs == cursorMs) cursor.copy(seenAtSince = cursor.seenAtSince + id) else DonationCursor(recorded, setOf(id))
            }
            cursors.save(DonationSourceKind.DonoLink, cursor)
            pageCursor = page.nextCursor
            if (!page.hasMore || pageCursor == null) return Polled.Done to cursor
        }
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
        const val HOUR_MS = 3_600_000L
        val BACKOFF_MS = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
    }
}
