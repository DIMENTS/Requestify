package nl.diments.requestify.core.playback

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.queue.PersistedPlayback
import nl.diments.requestify.core.queue.QueueStore
import nl.diments.requestify.core.spotify.SpotifyDevice
import nl.diments.requestify.core.spotify.SpotifyResult
import nl.diments.requestify.core.storage.JsonFileStore

/** Spotify's Web API only reaches the active device: while nothing is active, the controller makes one active. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class PlaybackControllerDeviceTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-devices") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val trackA = Track("A", "spotify:track:A", "Song A", listOf("Artist"), 200_000, false, null)

    private fun device(id: String?, active: Boolean = false, restricted: Boolean = false) =
        SpotifyDevice(id, isActive = active, isRestricted = restricted)

    private fun TestScope.fakePlayer() = FakeSpotifyPlayer { testScheduler.currentTime }

    private fun TestScope.controller(player: FakeSpotifyPlayer) = PlaybackController(
        player = player,
        store = QueueStore(JsonFileStore(dir.resolve("queue.json"), PersistedPlayback.serializer()) { PersistedPlayback() }),
        requestsOpen = MutableStateFlow(true),
        paidPriority = { true },
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Spotify has nothing active and lists [devices]; the first poll has run. */
    private fun TestScope.startIdle(player: FakeSpotifyPlayer, vararg devices: SpotifyDevice): PlaybackController {
        player.devicesResult = SpotifyResult.Ok(devices.toList())
        val controller = controller(player)
        controller.start()
        runCurrent()
        return controller
    }

    /** Moves virtual time forward by [ms] and runs everything that is due at the new time. */
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun FakeSpotifyPlayer.timesOf(name: String) = callsTo(name).map { it.atMs }

    private fun FakeSpotifyPlayer.transferredTo() = callsTo("transferPlayback").map { it.arg }

    @Test
    fun atStartupTheFirstDeviceThatTakesCommandsBecomesActiveWithoutPlaying() = runTest {
        val player = fakePlayer()
        val controller = startIdle(player, device("tv", restricted = true), device(null), device("phone"), device("pc"))
        assertEquals(listOf("phone"), player.transferredTo())
        assertEquals(listOf(0L), player.timesOf("transferPlayback"))
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)

        player.plays(trackA, progressMs = 42_000, isPlaying = false) // the phone has the last song, paused
        advance(1_000) // polled again soon, to show it
        assertEquals(listOf(0L, 1_000L), player.timesOf("currentlyPlaying"))
        assertEquals(SpotifyHealth.Ok, controller.health.value)
        assertEquals(false, controller.nowPlaying.value?.isPlaying)
        assertTrue(player.callsTo("resume").isEmpty(), "nothing starts playing")
        assertEquals(listOf("phone"), player.transferredTo())
    }

    @Test
    fun aDeviceThatIsActiveAlreadyIsLeftAlone() = runTest {
        val player = fakePlayer()
        val controller = startIdle(player, device("phone"), device("pc", active = true))
        advance(12_000)
        assertTrue(player.transferredTo().isEmpty())
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)
    }

    @Test
    fun withoutADeviceItAsksToOpenSpotifyAndLooksAgainUntilThereIsOne() = runTest {
        val player = fakePlayer()
        val controller = startIdle(player) // Spotify is open nowhere
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)
        advance(3_000)
        assertEquals(listOf(0L), player.timesOf("devices"), "not on every poll")
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value, "until it looks again")

        player.devicesResult = SpotifyResult.Ok(listOf(device("tv", restricted = true))) // takes no commands
        advance(3_000)
        assertEquals(listOf(0L, 6_000L), player.timesOf("devices"))
        assertEquals(SpotifyHealth.NoActiveDevice, controller.health.value)

        player.devicesResult = SpotifyResult.Ok(listOf(device("pc"))) // the streamer opened Spotify
        advance(6_000)
        assertEquals(listOf(0L, 6_000L, 12_000L), player.timesOf("devices"))
        assertEquals(listOf("pc"), player.transferredTo())
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)
    }

    @Test
    fun whileSomethingIsActiveNoDevicesAreReadAndOnceNothingIsItLooksAtOnce() = runTest {
        val player = fakePlayer()
        player.plays(trackA, progressMs = 60_000)
        val controller = controller(player)
        controller.start()
        runCurrent()
        advance(9_000)
        assertTrue(player.callsTo("devices").isEmpty())

        player.currentlyPlayingResult = SpotifyResult.Ok(null) // Spotify let the device go
        player.devicesResult = SpotifyResult.Ok(listOf(device("pc")))
        advance(3_000)
        assertEquals(listOf(12_000L), player.timesOf("devices"))
        assertEquals(listOf("pc"), player.transferredTo())
    }

    @Test
    fun aCommandRefusedForLackOfADeviceMakesOneActiveAtOnce() = runTest {
        val player = fakePlayer()
        val controller = startIdle(player, device("pc", active = true))
        advance(1_000)
        player.devicesResult = SpotifyResult.Ok(listOf(device("pc"))) // Spotify has let it go since
        player.playCommandResults.add(SpotifyResult.NoActiveDevice)
        controller.togglePause()
        runCurrent()
        assertEquals(listOf(0L, 1_000L), player.timesOf("devices"))
        assertEquals(listOf("pc"), player.transferredTo())
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value, "a device is active again")
    }

    @Test
    fun aRefusedTransferIsTriedAgainAtTheNextLook() = runTest {
        val player = fakePlayer()
        player.transferResults.add(SpotifyResult.Failed("HTTP 502"))
        val controller = startIdle(player, device("pc"))
        advance(3_000)
        assertEquals(listOf(0L, 3_000L), player.timesOf("currentlyPlaying"), "no sooner than usual")
        assertEquals(listOf(0L), player.timesOf("transferPlayback"))
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value, "an extra call never changes health")

        advance(3_000)
        assertEquals(listOf(0L, 6_000L), player.timesOf("transferPlayback"))
    }

    @Test
    fun aDeviceReadThatFailsChangesNothingAndIsTriedAgainLater() = runTest {
        val player = fakePlayer()
        player.devicesResult = SpotifyResult.Failed("HTTP 500")
        val controller = controller(player)
        controller.start()
        runCurrent()
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value)
        advance(6_000)
        assertEquals(listOf(0L, 6_000L), player.timesOf("devices"))
        assertTrue(player.transferredTo().isEmpty())
    }

    @Test
    fun aRateLimitedDeviceReadWaitsAsAsked() = runTest {
        val player = fakePlayer()
        player.devicesResult = SpotifyResult.RateLimited(10_000)
        val controller = controller(player)
        controller.start()
        runCurrent()
        advance(9_999)
        assertEquals(listOf(0L), player.timesOf("currentlyPlaying"))
        advance(1)
        assertEquals(listOf(0L, 10_000L), player.timesOf("currentlyPlaying"))
        assertEquals(SpotifyHealth.NothingPlaying, controller.health.value, "an extra read never changes health")
    }
}
