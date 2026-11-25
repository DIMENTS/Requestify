package nl.diments.requestify.ui.common

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.health.DonationProblem
import nl.diments.requestify.core.health.TwitchHealth
import nl.diments.requestify.core.health.connectionProblems
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class ConnectionChipTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun problemsAreOrderedMostImportantFirst() {
        assertEquals(
            listOf(
                ConnectionProblem.TwitchLoginNeeded,
                ConnectionProblem.Spotify(SpotifyHealth.Unreachable("x")),
                ConnectionProblem.TwitchDisconnected,
                ConnectionProblem.RewardRemoved,
            ),
            connectionProblems(
                SpotifyHealth.Unreachable("x"),
                TwitchHealth(loginNeeded = true, disconnected = true, rewardRemoved = true),
            ),
        )
        assertEquals(
            listOf(ConnectionProblem.Spotify(SpotifyHealth.PremiumRequired), ConnectionProblem.TwitchLoginNeeded),
            connectionProblems(SpotifyHealth.PremiumRequired, TwitchHealth(loginNeeded = true)),
        )
        assertEquals(
            listOf(
                ConnectionProblem.TwitchLoginNeeded,
                ConnectionProblem.Spotify(SpotifyHealth.NoActiveDevice),
                ConnectionProblem.TwitchDisconnected,
            ),
            connectionProblems(SpotifyHealth.NoActiveDevice, TwitchHealth(loginNeeded = true, disconnected = true)),
        )
        assertEquals(
            listOf(ConnectionProblem.Spotify(SpotifyHealth.LoggedOut)),
            connectionProblems(SpotifyHealth.LoggedOut, TwitchHealth()),
        )
        assertEquals(
            listOf(ConnectionProblem.Spotify(SpotifyHealth.LoginNeeded)),
            connectionProblems(SpotifyHealth.LoginNeeded, TwitchHealth()),
        )
        assertEquals(
            listOf(ConnectionProblem.Spotify(SpotifyHealth.RateLimited(1))),
            connectionProblems(SpotifyHealth.RateLimited(1), TwitchHealth()),
        )
    }

    @Test
    fun aWaitSpotifyAskedForSaysWhenItIsOver() = runComposeUiTest {
        val now = Instant.parse("2026-11-25T13:00:00Z").toEpochMilli()
        val zone = ZoneOffset.UTC
        fun args(health: SpotifyHealth.RateLimited) = health.look(now, zone).args

        assertEquals(emptyList(), args(SpotifyHealth.RateLimited(now + 59_999)), "over before it is read")
        assertEquals(listOf("13:01"), args(SpotifyHealth.RateLimited(now + 60_000)))
        assertEquals(listOf("13:01"), args(SpotifyHealth.RateLimited(now + 5_000, quota = true)))
        assertEquals(listOf("14:33"), args(SpotifyHealth.RateLimited(now + 92 * 60_000 + 1, quota = true)), "never too early")
        assertEquals(listOf("Thu 06:11"), args(SpotifyHealth.RateLimited(now + 61_860_000, quota = true)), "tomorrow")

        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                ConnectionChip(listOf(ConnectionProblem.Spotify(SpotifyHealth.RateLimited(System.currentTimeMillis() + 3_600_000, quota = true))), onClick = {})
            }
        }
        onNodeWithText("Spotify limit reached, back at", substring = true).assertIsDisplayed()
    }

    @Test
    fun nothingPlayingIsNotAProblem() {
        assertEquals(emptyList(), connectionProblems(SpotifyHealth.NothingPlaying, TwitchHealth()))
        assertEquals(emptyList(), connectionProblems(SpotifyHealth.Ok, TwitchHealth()))
    }

    @Test
    fun chipSaysAllConnectedWithoutProblems() = runComposeUiTest {
        setContent { RequestifyTheme(BrandSeed, dark = true) { ConnectionChip(emptyList(), onClick = {}) } }
        onNodeWithText("All connected").assertIsDisplayed()
    }

    @Test
    fun chipShowsTheFirstProblemAndClicks() = runComposeUiTest {
        var clicks = 0
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                ConnectionChip(
                    listOf(ConnectionProblem.TwitchLoginNeeded, ConnectionProblem.RewardRemoved),
                    onClick = { clicks++ },
                )
            }
        }
        onNodeWithText("Twitch: log in again").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun donationProblemsShowInTheChip() = runComposeUiTest {
        val now = 1_000_000_000_000L
        val day = 24 * 3_600_000L
        fun problems(health: DonationHealth) = connectionProblems(SpotifyHealth.Ok, TwitchHealth(), health, now)

        assertEquals(emptyList(), problems(DonationHealth()))
        assertEquals(emptyList(), problems(DonationHealth(DonationSourceKind.DonoLink, DonationStatus.Connected, keyExpiresAtEpochMs = now + 30 * day)))
        assertEquals(
            listOf(ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyRefused)),
            problems(DonationHealth(DonationSourceKind.DonoLink, DonationStatus.KeyRefused)),
        )
        assertEquals(
            listOf(ConnectionProblem.Donation(DonationSourceKind.StreamElements, DonationProblem.Disconnected)),
            problems(DonationHealth(DonationSourceKind.StreamElements, DonationStatus.Retrying, retryingSinceMs = now - 1_000)),
        )
        assertEquals(
            listOf(ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, now + 6 * day, daysLeft = 6)),
            problems(DonationHealth(DonationSourceKind.DonoLink, DonationStatus.Connected, keyExpiresAtEpochMs = now + 6 * day)),
        )
        assertEquals(
            listOf(ConnectionProblem.TwitchLoginNeeded, ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyRefused)),
            connectionProblems(SpotifyHealth.Ok, TwitchHealth(loginNeeded = true), DonationHealth(DonationSourceKind.DonoLink, DonationStatus.KeyRefused), now),
            "logins first",
        )

        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                ConnectionChip(listOf(ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyRefused)), onClick = {})
            }
        }
        onNodeWithText("DonoLink: new key needed").assertIsDisplayed()
    }

    @Test
    fun aMissingKeySaysAKeyIsNeeded() = runComposeUiTest {
        assertEquals(
            listOf(ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.NoKey)),
            connectionProblems(SpotifyHealth.Ok, TwitchHealth(), DonationHealth(DonationSourceKind.DonoLink, DonationStatus.NoKey), 0),
        )
        setContent {
            RequestifyTheme(BrandSeed, dark = true) {
                ConnectionChip(listOf(ConnectionProblem.Donation(DonationSourceKind.StreamElements, DonationProblem.NoKey)), onClick = {})
            }
        }
        onNodeWithText("StreamElements: token needed").assertIsDisplayed()
    }

    @Test
    fun keyExpiringSaysTheDays() = kotlinx.coroutines.runBlocking {
        val now = 1_800_000_000_000L
        val day = 24 * 3_600_000L
        suspend fun text(expiresAt: Long): String {
            val look = ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, expiresAt).statusLook(now)
            return org.jetbrains.compose.resources.getString(look.text, *look.args.toTypedArray())
        }
        assertEquals("DonoLink key expires in 6 days", text(now + 6 * day + 3_600_000))
        assertEquals("DonoLink key expires in 1 day", text(now + day + 3_600_000))
        assertEquals("DonoLink key expires within 24 hours", text(now + 2 * 3_600_000))
    }

    @Test
    fun nothingConnectedIsNeutral() = runComposeUiTest {
        setContent { RequestifyTheme(BrandSeed, dark = true) { ConnectionChip(emptyList(), nothingConnected = true, onClick = {}) } }
        onNodeWithText("Nothing connected yet").assertIsDisplayed()
        onNodeWithText("All connected").assertDoesNotExist()
    }
}
