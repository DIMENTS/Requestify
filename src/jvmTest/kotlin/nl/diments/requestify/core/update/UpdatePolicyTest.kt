package nl.diments.requestify.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.diments.requestify.core.stream.GateStatus
import nl.diments.requestify.core.stream.StreamState

class UpdatePolicyTest {
    private val running = AppVersion(0, 2, 0)
    private val now = 50_000_000L

    private fun pending(version: String = "0.2.1", attempts: Int = 0) =
        PendingUpdate(version, "Requestify-$version.msi", "ab", "", "", attempts)

    private fun installs(
        afterCrash: Boolean = false,
        running: AppVersion? = this.running,
        pending: PendingUpdate? = pending(),
        stream: StreamAtStart = StreamAtStart.NeverSeenLive,
    ) = installAtStart(afterCrash, running, pending, stream, now)

    private fun status(open: Boolean = false, live: Boolean = false) =
        GateStatus(open = open, twitch = true, live = live, manual = false, clearAtEpochMs = null, clearPostponed = false)

    @Test
    fun aStartWithinTheGraceDoesNotInstall() {
        assertFalse(installs(stream = StreamAtStart.LastSeenLive(now - 60_000)), "restarted a minute into a stream")
        assertFalse(installs(stream = StreamAtStart.LastSeenLive(now - 600_000)), "exactly ten minutes still counts as live")
    }

    @Test
    fun aStartAfterTheGraceInstalls() {
        assertTrue(installs(stream = StreamAtStart.LastSeenLive(now - 600_001)))
        assertTrue(installs(stream = StreamAtStart.NeverSeenLive))
    }

    @Test
    fun anUnreadableStreamFileDoesNotInstall() {
        assertEquals(StreamAtStart.Unreadable, streamAtStart(state = null, now = now))
        assertFalse(installs(stream = StreamAtStart.Unreadable), "unknown whether live: lean towards not installing")
    }

    @Test
    fun aMissingStreamFileDoesNotBlock() {
        // A missing stream.json reads as the defaults.
        assertEquals(StreamAtStart.NeverSeenLive, streamAtStart(StreamState(), now))
        assertTrue(installs(stream = streamAtStart(StreamState(), now)), "a streamer without Twitch is updated too")
    }

    @Test
    fun theLastLiveMomentComesFromTheStreamFile() {
        assertEquals(StreamAtStart.LastSeenLive(5), streamAtStart(StreamState(live = true, lastSeenLiveMs = 5), now))
        assertEquals(StreamAtStart.LastSeenLive(now), streamAtStart(StreamState(live = true), now), "live, but when unknown: as good as now")
        // The stream dropped: its grace runs from when it went offline, and requests may come back any moment.
        assertEquals(StreamAtStart.LastSeenLive(now - 60_000), streamAtStart(StreamState(live = false, offlineSinceMs = now - 60_000), now))
        assertFalse(installs(stream = streamAtStart(StreamState(offlineSinceMs = now - 60_000), now)), "restarting things a minute after a drop")
    }

    @Test
    fun aCrashRestartNeverInstalls() {
        assertFalse(installs(afterCrash = true), "it must come back fast, and may be mid-stream")
    }

    @Test
    fun aLocalOrDevBuildNeverInstalls() {
        assertFalse(installs(running = null), "gradlew run")
        assertFalse(installs(running = AppVersion(0, 0, 33443)), "a build on the developer's PC")
    }

    @Test
    fun twoFailedAttemptsStopAutomaticInstalls() {
        assertTrue(installs(pending = pending(attempts = 1)), "one retry")
        assertFalse(installs(pending = pending(attempts = 2)), "then only by hand")
    }

    @Test
    fun anOlderOrEqualPendingIsNotInstalled() {
        assertFalse(installs(pending = null))
        assertFalse(installs(pending = pending("0.2.0")))
        assertFalse(installs(pending = pending("0.1.9")))
        assertFalse(installs(pending = pending("garbage")))
    }

    @Test
    fun installNowNeedsOfflineAndClosed() {
        assertTrue(canInstallNow(status()))
        assertFalse(canInstallNow(status(live = true)), "never during a stream")
        assertFalse(canInstallNow(status(open = true)), "nor while requests are open")
    }
}
