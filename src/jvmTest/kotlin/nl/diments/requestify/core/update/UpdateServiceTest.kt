package nl.diments.requestify.core.update

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.stream.GateStatus

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateServiceTest {
    private lateinit var dir: Path
    private lateinit var store: UpdateStore
    private var answer: ReleaseCheck = ReleaseCheck.NoneUsable
    private var checks = 0
    private var downloads = 0
    private var downloadFails: String? = null
    private val gate = MutableStateFlow(GateStatus(open = false, twitch = true, live = false, manual = false, clearAtEpochMs = null, clearPostponed = false))
    private val sixHours = 6 * 3_600_000L

    @BeforeTest fun setUp() {
        dir = createTempDirectory("requestify-service")
        store = UpdateStore(dir)
    }

    @AfterTest fun tearDown() { dir.deleteTestDir() }

    private fun release(version: String) = Release(
        checkNotNull(AppVersion.parse(version)), "new in $version", "https://github.com/DIMENTS/Requestify/releases/tag/v$version",
        ReleaseAsset("Requestify-$version.msi", "u", 1), ReleaseAsset("SHA256SUMS.txt", "s", 1),
    )

    private fun pendingOf(release: Release, attempts: Int = 0, from: String? = null) = PendingUpdate(
        release.version.toString(), release.msi.name, "ab", release.notes, release.pageUrl, attempts, from,
    )

    private fun TestScope.service(running: AppVersion? = AppVersion(0, 2, 0)) = UpdateService(
        running = running,
        latestRelease = { checks++; answer },
        download = { r ->
            downloads++
            downloadFails?.let { DownloadResult.Failed(it) } ?: pendingOf(r).also(store::savePending).let(DownloadResult::Ready)
        },
        store = store,
        gate = gate,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.events(service: UpdateService): List<UpdateEvent> {
        val seen = mutableListOf<UpdateEvent>()
        backgroundScope.launch { service.events.toList(seen) }
        runCurrent()
        return seen
    }

    @Test
    fun checksTwoMinutesAfterStartThenEverySixHours() = runTest {
        service().start()
        advanceTimeBy(119_999)
        assertEquals(0, checks, "not while everything is connecting")
        advanceTimeBy(2)
        assertEquals(1, checks)
        advanceTimeBy(sixHours)
        assertEquals(2, checks)
    }

    @Test
    fun aNewerReleaseBecomesReady() = runTest {
        answer = ReleaseCheck.Found(release("0.2.1"))
        val service = service()
        service.start()
        advanceTimeBy(120_001)
        assertEquals(UpdateState.Ready(AppVersion(0, 2, 1), "new in 0.2.1", "https://github.com/DIMENTS/Requestify/releases/tag/v0.2.1"), service.state.value)
    }

    @Test
    fun upToDateStaysQuiet() = runTest {
        answer = ReleaseCheck.Found(release("0.2.0"))
        val service = service()
        val events = events(service)
        service.start()
        advanceTimeBy(120_001)
        assertIs<UpdateState.UpToDate>(service.state.value)
        assertEquals(0, downloads)
        assertEquals(emptyList(), events)
    }

    @Test
    fun aFailedAutomaticCheckIsQuiet() = runTest {
        answer = ReleaseCheck.Failed("no network")
        val service = service()
        val events = events(service)
        service.start()
        advanceTimeBy(120_001)
        assertEquals(UpdateState.CheckFailed(manual = false), service.state.value)
        assertEquals(emptyList(), events, "nothing for the Log, the chip or a notification")

        service.checkNow()
        runCurrent()
        assertEquals(UpdateState.CheckFailed(manual = true), service.state.value, "asked by hand: say it")
    }

    @Test
    fun aFailedDownloadIsLoggedOncePerVersion() = runTest {
        answer = ReleaseCheck.Found(release("0.2.1"))
        downloadFails = "checksum mismatch"
        val service = service()
        val events = events(service)
        service.start()
        advanceTimeBy(120_001 + sixHours)
        assertEquals(2, downloads, "tried again at the next check")
        assertEquals(listOf<UpdateEvent>(UpdateEvent.DownloadFailed(AppVersion(0, 2, 1), "checksum mismatch")), events)
    }

    @Test
    fun afterASuccessfulInstallTheNextStartReportsUpdated() = runTest {
        store.savePending(pendingOf(release("0.2.1"), attempts = 1, from = "0.2.0"))
        val service = service(running = AppVersion(0, 2, 1))
        assertEquals(listOf<UpdateEvent>(UpdateEvent.Updated(AppVersion(0, 2, 0), AppVersion(0, 2, 1))), events(service), "replayed for the Log")
        assertEquals(JustUpdated("0.2.0", "0.2.1", "new in 0.2.1"), service.justUpdated.value)
        assertNull(store.pending(), "installed: nothing waits any more")
        service.dismissJustUpdated()
        assertNull(service.justUpdated.value)
        assertNull(store.justUpdated(), "the card shows once")
    }

    @Test
    fun aFailedInstallIsReportedAndRetriedOnce() = runTest {
        store.savePending(pendingOf(release("0.2.1"), attempts = 1, from = "0.2.0"))
        dir.resolve("install-exit-code.txt").writeText("1603")
        val first = service()
        assertEquals(listOf<UpdateEvent>(UpdateEvent.InstallFailed(AppVersion(0, 2, 1), 1603)), events(first))
        assertIs<UpdateState.Ready>(first.state.value, "one more try")
        assertNull(store.lastExitCode(), "reported once")

        store.savePending(pendingOf(release("0.2.1"), attempts = 2, from = "0.2.0"))
        val second = service()
        assertEquals(UpdateState.InstallFailed(AppVersion(0, 2, 1), "https://github.com/DIMENTS/Requestify/releases/tag/v0.2.1"), second.state.value)
        answer = ReleaseCheck.Found(release("0.2.1"))
        second.checkNow()
        runCurrent()
        assertIs<UpdateState.InstallFailed>(second.state.value, "not retried automatically after two attempts")
        assertEquals(0, downloads)
    }

    @Test
    fun installNowIsRefusedWhileLiveOrOpen() = runTest {
        store.savePending(pendingOf(release("0.2.1")))
        val service = service()
        val requests = mutableListOf<PendingUpdate>()
        backgroundScope.launch { service.installRequests.collect { requests += it } }
        runCurrent()

        gate.value = gate.value.copy(live = true)
        runCurrent()
        assertEquals(false, service.canInstallNow.value)
        service.installNow()
        runCurrent()
        assertEquals(emptyList(), requests, "never during a stream")

        gate.value = gate.value.copy(live = false, open = true)
        service.installNow()
        runCurrent()
        assertEquals(emptyList(), requests, "nor while requests are open")

        gate.value = gate.value.copy(open = false)
        runCurrent()
        assertEquals(true, service.canInstallNow.value)
        service.installNow()
        runCurrent()
        assertEquals(1, requests.single().attempts, "the attempt is counted before the install starts")
        assertEquals("0.2.0", requests.single().from)
    }

    @Test
    fun anUnexpectedErrorNeverReachesTheApp() = runTest {
        // An error escaping the updater would reach the crash guard: a restart, maybe mid-stream.
        val service = UpdateService(
            running = AppVersion(0, 2, 0),
            latestRelease = { throw IllegalStateException("something odd") },
            download = { error("not reached") },
            store = store,
            gate = gate,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        service.start()
        advanceTimeBy(120_001)
        assertEquals(UpdateState.CheckFailed(manual = false), service.state.value)
        service.checkNow()
        runCurrent()
        assertEquals(UpdateState.CheckFailed(manual = true), service.state.value)
    }

    @Test
    fun aPulledReleaseIsNotInstalled() = runTest {
        store.savePending(pendingOf(release("0.2.1"))) // downloaded before the maker pulled it (or made it a pre-release)
        val service = service()
        assertIs<UpdateState.Ready>(service.state.value)
        answer = ReleaseCheck.Found(release("0.2.0")) // the newest published one is the version running
        service.checkNow()
        runCurrent()
        assertIs<UpdateState.UpToDate>(service.state.value)
        assertNull(store.pending(), "never installed at a later start either")
    }

    @Test
    fun theDevBuildIsOff() = runTest {
        for (running in listOf(null, AppVersion(0, 0, 33443))) {
            val service = service(running)
            service.start()
            advanceTimeBy(120_001)
            assertEquals(UpdateState.Off, service.state.value)
        }
        assertEquals(0, checks)
    }
}
