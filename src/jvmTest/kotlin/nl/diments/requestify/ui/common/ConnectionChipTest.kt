package nl.diments.requestify.ui.common

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nl.diments.requestify.core.playback.SpotifyHealth
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
}
