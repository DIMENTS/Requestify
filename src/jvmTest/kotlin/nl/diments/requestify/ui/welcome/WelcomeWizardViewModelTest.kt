package nl.diments.requestify.ui.welcome

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.WizardStep
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.storage.inCallerSaveContext

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeWizardViewModelTest {
    private lateinit var dir: Path
    private lateinit var settings: SettingsStore
    private val spotify = MutableStateFlow(false)
    private val twitch = MutableStateFlow(false)
    private val donations = MutableStateFlow(false)

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-welcome")
        settings = store(dir.resolve("settings.json"))
    }

    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private fun store(file: Path) = SettingsStore(JsonFileStore(file, Settings.serializer()) { Settings() }, inCallerSaveContext())

    private fun TestScope.wizard(settings: SettingsStore = this@WelcomeWizardViewModelTest.settings) =
        WelcomeWizardViewModel(settings, spotify, twitch, donations, backgroundScope).also { runCurrent() }

    @Test
    fun aNewUserSeesItFromTheWelcomeCard() = runTest {
        val state = wizard().state.value
        assertTrue(state.visible)
        assertEquals(WizardStep.Welcome, state.step)
        assertEquals(emptySet(), state.connected)
        assertEquals(3, state.remaining)
    }

    @Test
    fun anExistingUserNeverSeesItUnasked() = runTest {
        WelcomeWizardViewModel.markExistingUser(settings, anyConnectionSaved = true)
        assertFalse(wizard().state.value.visible, "someone who already set Requestify up gets no wizard after an update")

        val fresh = store(dir.resolve("fresh.json"))
        WelcomeWizardViewModel.markExistingUser(fresh, anyConnectionSaved = false)
        assertTrue(wizard(fresh).state.value.visible)

        val halfway = store(dir.resolve("halfway.json"))
        halfway.tryUpdate { it.copy(welcomeStep = WizardStep.Twitch) }
        WelcomeWizardViewModel.markExistingUser(halfway, anyConnectionSaved = true)
        assertTrue(wizard(halfway).state.value.visible, "connecting Spotify inside the wizard does not end it")
    }

    @Test
    fun unreadableSettingsNeverShowIt() = runTest {
        val unreadable = store(dir.resolve("settings-dir").createDirectories())
        assertFalse(wizard(unreadable).state.value.visible, "unknown whether it was done: never in the way")
    }

    @Test
    fun aConnectedPlatformIsSkipped() = runTest {
        spotify.value = true
        val wizard = wizard()
        assertEquals(setOf(WizardPlatform.Spotify), wizard.state.value.connected, "shown as a badge from the start")
        wizard.begin()
        runCurrent()
        assertEquals(WizardStep.Twitch, wizard.state.value.step)
    }

    @Test
    fun connectingCelebratesThenAdvances() = runTest {
        val wizard = wizard()
        wizard.begin()
        runCurrent()
        assertEquals(WizardStep.Spotify, wizard.state.value.step)
        spotify.value = true
        runCurrent()
        assertEquals(WizardPlatform.Spotify, wizard.state.value.celebrating)
        assertEquals(WizardStep.Spotify, wizard.state.value.step, "the card stays while it celebrates")
        assertEquals(2, wizard.state.value.remaining)
        wizard.celebrated()
        runCurrent()
        assertNull(wizard.state.value.celebrating)
        assertEquals(WizardStep.Twitch, wizard.state.value.step)
    }

    @Test
    fun laterMovesOnWithoutABadge() = runTest {
        val wizard = wizard()
        wizard.begin()
        wizard.later()
        runCurrent()
        assertEquals(WizardStep.Twitch, wizard.state.value.step)
        assertEquals(setOf(WizardPlatform.Spotify), wizard.state.value.later)
        assertEquals(emptySet(), wizard.state.value.connected)
        wizard.later()
        wizard.later()
        runCurrent()
        assertEquals(WizardStep.Done, wizard.state.value.step)
    }

    @Test
    fun aHalfFinishedWizardResumes() = runTest {
        val first = wizard()
        first.begin()
        first.later()
        runCurrent()
        assertEquals(WizardStep.Twitch, settings.settings.value.welcomeStep, "kept for the next start")
        assertEquals(WizardStep.Twitch, wizard().state.value.step, "the window closed halfway: back at the same card")

        twitch.value = true // connected on the Connections page meanwhile
        assertEquals(WizardStep.Donations, wizard().state.value.step, "a card that is done by now is skipped")
    }

    @Test
    fun skipAllAndFinishEndIt() = runTest {
        val wizard = wizard()
        wizard.skipAll()
        runCurrent()
        assertFalse(wizard.state.value.visible)
        assertTrue(settings.settings.value.welcomeDone)

        wizard.reopen()
        runCurrent()
        wizard.finish()
        runCurrent()
        assertFalse(wizard.state.value.visible)
        assertNull(settings.settings.value.welcomeStep)
    }

    @Test
    fun reopenStartsAgainAtWelcome() = runTest {
        val wizard = wizard()
        wizard.begin()
        wizard.later()
        wizard.finish()
        runCurrent()
        wizard.reopen()
        runCurrent()
        val state = wizard.state.value
        assertTrue(state.visible)
        assertEquals(WizardStep.Welcome, state.step)
        assertEquals(emptySet(), state.later, "a fresh round")
    }

    @Test
    fun remainingCountsWhatIsLeft() = runTest {
        val wizard = wizard()
        assertEquals(3, wizard.state.value.remaining)
        twitch.value = true
        runCurrent()
        assertEquals(2, wizard.state.value.remaining)
        wizard.begin()
        wizard.later() // Spotify later
        runCurrent()
        assertEquals(1, wizard.state.value.remaining, "only the donations are left")
    }
}
