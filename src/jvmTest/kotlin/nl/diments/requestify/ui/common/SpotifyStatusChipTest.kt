package nl.diments.requestify.ui.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.RequestifyTheme

@OptIn(ExperimentalTestApi::class)
class SpotifyStatusChipTest {
    private val originalLocale = Locale.getDefault()

    @BeforeTest fun useEnglish() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun neverLoggedInAsksToConnectAndAnExpiredLoginToLogInAgain() = runComposeUiTest {
        val health = mutableStateOf<SpotifyHealth>(SpotifyHealth.LoggedOut)
        setContent { RequestifyTheme(BrandSeed, dark = false) { SpotifyStatusChip(health.value) } }
        onNodeWithText("Connect Spotify").assertIsDisplayed()

        health.value = SpotifyHealth.LoginNeeded
        waitForIdle()
        onNodeWithText("Log in to Spotify again").assertIsDisplayed()

        health.value = SpotifyHealth.Unreachable("token unavailable")
        waitForIdle()
        onNodeWithText("Spotify unreachable").assertIsDisplayed()
    }
}
