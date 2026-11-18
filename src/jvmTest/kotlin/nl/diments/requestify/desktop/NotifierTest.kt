package nl.diments.requestify.desktop

import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.health.DonationProblem
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.SaveProblem
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.storage.inCallerSaveContext

@OptIn(ExperimentalCoroutinesApi::class)
class NotifierTest {
    private lateinit var dir: Path
    private val day = 24 * 3_600_000L
    private val hour = 3_600_000L
    private val base = 1_800_000_000_000L

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-notifier") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private val problems = MutableStateFlow<List<ConnectionProblem>>(emptyList())
    private val saves = MutableStateFlow<List<SaveProblem>>(emptyList())
    private val sent = mutableListOf<Notice>()

    private fun settings() = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())

    private fun TestScope.notifier(store: SettingsStore = settings()) =
        Notifier(problems, saves, store, backgroundScope, clock = { base + testScheduler.currentTime }, send = { sent += it }).also {
            it.start()
            runCurrent()
        }

    private fun expiring(expiresAt: Long) = ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.KeyExpiring, expiresAt)

    @Test
    fun aProblemPresentAtStartIsNotifiedOnce() = runTest {
        problems.value = listOf(ConnectionProblem.TwitchLoginNeeded)
        notifier()
        problems.value = listOf(ConnectionProblem.TwitchLoginNeeded, ConnectionProblem.Spotify(SpotifyHealth.NoActiveDevice))
        runCurrent()
        assertEquals(listOf<Notice>(Notice.TwitchLoginNeeded), sent)
    }

    @Test
    fun aProblemIsNotifiedAgainOnlyAfterItWentAway() = runTest {
        notifier()
        problems.value = listOf(ConnectionProblem.Spotify(SpotifyHealth.LoginNeeded))
        runCurrent()
        problems.value = listOf(ConnectionProblem.Spotify(SpotifyHealth.LoginNeeded), ConnectionProblem.RewardRemoved)
        runCurrent()
        problems.value = emptyList()
        runCurrent()
        problems.value = listOf(ConnectionProblem.Spotify(SpotifyHealth.LoginNeeded))
        runCurrent()
        assertEquals(listOf(Notice.SpotifyLoginNeeded, Notice.RewardRemoved, Notice.SpotifyLoginNeeded), sent)
    }

    @Test
    fun deliberateAndSelfHealingStatesAreNeverNotified() = runTest {
        notifier()
        problems.value = listOf(
            ConnectionProblem.Spotify(SpotifyHealth.LoggedOut),
            ConnectionProblem.Spotify(SpotifyHealth.NoActiveDevice),
            ConnectionProblem.Spotify(SpotifyHealth.Unreachable("down")),
            ConnectionProblem.Spotify(SpotifyHealth.RateLimited(5_000)),
            ConnectionProblem.TwitchDisconnected,
            ConnectionProblem.Donation(DonationSourceKind.DonoLink, DonationProblem.NoKey),
            ConnectionProblem.Donation(DonationSourceKind.StreamElements, DonationProblem.Disconnected),
        )
        runCurrent()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun refusedKeysAndPremiumAreNotified() = runTest {
        notifier()
        problems.value = listOf(
            ConnectionProblem.Spotify(SpotifyHealth.PremiumRequired),
            ConnectionProblem.Donation(DonationSourceKind.StreamElements, DonationProblem.KeyRefused),
        )
        runCurrent()
        assertEquals(listOf(Notice.SpotifyPremiumMissing, Notice.KeyRefused(DonationSourceKind.StreamElements)), sent)
    }

    @Test
    fun expiryNotifiesAtSevenDaysAndOnTheLastDay() = runTest {
        notifier()
        val expiresAt = base + 6 * day + hour
        problems.value = listOf(expiring(expiresAt))
        runCurrent()
        assertEquals(listOf<Notice>(Notice.KeyExpiring(daysLeft = 6)), sent)

        advanceTimeBy(3 * day)
        problems.value = listOf(expiring(expiresAt)) // looked at again by the clock: same list
        problems.value = emptyList()
        problems.value = listOf(expiring(expiresAt))
        runCurrent()
        assertEquals(1, sent.size, "the week's step is sent once")

        advanceTimeBy(3 * day + hour / 2) // under a day left
        problems.value = emptyList()
        problems.value = listOf(expiring(expiresAt))
        runCurrent()
        assertEquals(listOf<Notice>(Notice.KeyExpiring(6), Notice.KeyExpiring(0)), sent)
    }

    @Test
    fun expiryStepsSurviveARestart() = runTest {
        val store = settings()
        val expiresAt = base + 5 * day
        problems.value = listOf(expiring(expiresAt))
        notifier(store)
        assertEquals(1, sent.size)
        sent.clear()

        notifier(settings()) // the app starts again
        assertEquals(emptyList(), sent, "not \"5 days\" at every start")
    }

    @Test
    fun aNewKeyStartsTheExpiryStepsOver() = runTest {
        notifier()
        problems.value = listOf(expiring(base + 5 * day))
        runCurrent()
        problems.value = listOf(expiring(base + 3 * day)) // another key, with its own expiry
        runCurrent()
        assertEquals(listOf<Notice>(Notice.KeyExpiring(5), Notice.KeyExpiring(3)), sent)
    }

    @Test
    fun aLongSleepSendsEachExpiryStepOnce() = runTest {
        notifier()
        problems.value = listOf(expiring(base + 12 * hour)) // the PC slept through the whole week
        runCurrent()
        assertEquals(listOf<Notice>(Notice.KeyExpiring(0)), sent, "only the last-day step, not both at once")
    }

    @Test
    fun aSaveOutageNotifiesAfterTwoMinutesOnly() = runTest {
        notifier()
        saves.value = listOf(SaveProblem("settings.json", "disk full", base))
        runCurrent()
        advanceTimeBy(119_000)
        runCurrent()
        assertEquals(emptyList(), sent)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf<Notice>(Notice.SavingFails(listOf("settings.json"))), sent)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, sent.size, "once per outage")
    }

    @Test
    fun aShortSaveOutageIsSilent() = runTest {
        notifier()
        saves.value = listOf(SaveProblem("settings.json", "locked by a virus scanner", base))
        runCurrent()
        advanceTimeBy(30_000)
        saves.value = emptyList()
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun noticeTextsExistInBothLanguages() = runBlocking {
        val notices = listOf(
            Notice.SpotifyLoginNeeded, Notice.SpotifyPremiumMissing, Notice.TwitchLoginNeeded,
            Notice.KeyRefused(DonationSourceKind.DonoLink), Notice.KeyRefused(DonationSourceKind.StreamElements),
            Notice.KeyExpiring(0), Notice.KeyExpiring(1), Notice.KeyExpiring(5), Notice.RewardRemoved,
            Notice.SavingFails(listOf("settings.json")), Notice.RestartedAfterCrash, Notice.CloseHint,
        )
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("nl-NL"))) {
                Locale.setDefault(locale)
                val texts = notices.map { noticeText(it) }
                texts.forEach { (title, text) -> assertTrue(title.isNotBlank() && text.isNotBlank(), "$locale: $title / $text") }
                assertEquals(texts.size, texts.toSet().size, "$locale: every notice says something of its own")
            }
            Locale.setDefault(Locale.forLanguageTag("nl-NL"))
            assertEquals("DonoLink-sleutel verloopt over 5 dagen", noticeText(Notice.KeyExpiring(5)).first)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
