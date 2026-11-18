package nl.diments.requestify.core.health

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionProblemsTest {
    private val day = 24 * 3_600_000L
    private val hour = 3_600_000L

    @Test
    fun aKeyExpiringCarriesItsExpiry() {
        val now = 1_000_000L
        val donoLink = DonationHealth(DonationSourceKind.DonoLink, DonationStatus.Connected, keyExpiresAtEpochMs = now + 3 * day)
        assertEquals(
            listOf(ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, now + 3 * day)),
            connectionProblems(SpotifyHealth.Ok, TwitchHealth(), donoLink, now),
        )
    }

    @Test
    fun ticksHourlyAndAtMidnight() = runTest {
        val base = Instant.parse("2026-11-18T22:30:00Z").toEpochMilli()
        val ticks = mutableListOf<Long>()
        val job = launch { clockTicks({ base + testScheduler.currentTime }, ZoneOffset.UTC).take(4).toList(ticks) }
        advanceTimeBy(3 * hour)
        runCurrent()
        job.join()
        assertEquals(
            listOf("2026-11-18T22:30:00Z", "2026-11-18T23:30:00Z", "2026-11-19T00:00:00Z", "2026-11-19T01:00:00Z"),
            ticks.map { Instant.ofEpochMilli(it).toString() },
        )
    }

    @Test
    fun theProblemsFollowTheClock() = runTest {
        val base = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
        val clock = { base + testScheduler.currentTime }
        val expiry = base + 8 * day
        val problems = problemsOverTime(
            spotify = MutableStateFlow(SpotifyHealth.Ok),
            twitch = MutableStateFlow(TwitchHealth()),
            donations = MutableStateFlow(DonationHealth(DonationSourceKind.DonoLink, DonationStatus.Connected, keyExpiresAtEpochMs = expiry)),
            ticks = clockTicks(clock, ZoneOffset.UTC),
        ).stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        runCurrent()
        assertTrue(problems.value.isEmpty(), "8 days left: nothing to warn about yet")

        advanceTimeBy(day + hour)
        runCurrent()
        assertEquals(
            listOf(ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, expiry)),
            problems.value,
            "a day later, without any change of health, the key is within its week",
        )
    }
}
