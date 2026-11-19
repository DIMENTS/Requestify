package nl.diments.requestify.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.deleteTestDir

class SingleInstanceTest {
    private lateinit var dir: Path
    private val running = CopyOnWriteArrayList<RunningInstance>()

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-instance") }

    @AfterTest fun tearDown() {
        running.forEach { it.close() }
        dir.deleteTestDir()
    }

    private fun start(onShow: () -> Unit = {}, askToShow: Boolean = true, waitForLockMs: Long = 0): InstanceStart =
        SingleInstance(dir).start(onShow, askToShow, waitForLockMs).also { if (it is InstanceStart.First) running += it.instance }

    /** Waits up to 2 s for [condition]. */
    private fun eventually(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun aSecondStartAsksTheFirstToShow() {
        val shown = AtomicInteger()
        assertIs<InstanceStart.First>(start(onShow = { shown.incrementAndGet() }))
        assertEquals(InstanceStart.AlreadyRunning, start())
        assertTrue(eventually { shown.get() == 1 }, "the running one shows its window once")
    }

    @Test
    fun twoStartsAtOnceGiveOneFirst() {
        val results = CopyOnWriteArrayList<InstanceStart>()
        val go = CountDownLatch(1)
        val threads = List(2) { Thread { go.await(); results += start() } }
        threads.forEach { it.start() }
        go.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, results.count { it is InstanceStart.First })
        assertEquals(1, results.count { it == InstanceStart.AlreadyRunning })
    }

    @Test
    fun anAutostartDoesNotAskToShow() {
        val shown = AtomicInteger()
        assertIs<InstanceStart.First>(start(onShow = { shown.incrementAndGet() }))
        assertEquals(InstanceStart.AlreadyRunning, start(askToShow = false))
        Thread.sleep(300)
        assertEquals(0, shown.get(), "Windows' autostart finds it running and does nothing visible")
    }

    @Test
    fun aStalePortFileDoesNotBlock() {
        dir.resolve("instance.port").writeText("1 stale-token") // left by a process that is gone
        assertIs<InstanceStart.First>(start())
    }

    @Test
    fun aWrongTokenIsIgnored() {
        val shown = AtomicInteger()
        assertIs<InstanceStart.First>(start(onShow = { shown.incrementAndGet() }))
        val port = dir.resolve("instance.port").readText().trim().split(" ")[0].toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            PrintWriter(socket.getOutputStream(), true).println("show not-the-token")
            BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
        }
        Thread.sleep(200)
        assertEquals(0, shown.get())
    }

    @Test
    fun afterCrashWaitsForTheOldLock() {
        val oldShown = AtomicInteger()
        val old = assertIs<InstanceStart.First>(start(onShow = { oldShown.incrementAndGet() }))
        Thread { Thread.sleep(300); old.instance.close() }.start() // the crashed process ends
        val restarted = start(askToShow = false, waitForLockMs = 5_000)
        assertIs<InstanceStart.First>(restarted, "the restart takes over once the old lock is gone")
        assertEquals(0, oldShown.get(), "it never asked the old one to show")
    }

    @Test
    fun aStartDuringQuitWaitsAndThenRuns() {
        val shown = AtomicInteger()
        val first = assertIs<InstanceStart.First>(start(onShow = { shown.incrementAndGet() })).instance
        first.markQuitting()
        Thread {
            Thread.sleep(300)
            first.close() // the Quit finishes
        }.start()
        val began = System.currentTimeMillis()
        val second = SingleInstance(dir).start(onShowRequested = {}).also { if (it is InstanceStart.First) running += it.instance }
        assertIs<InstanceStart.First>(second, "the new start waited for the quitting one and runs")
        assertTrue(System.currentTimeMillis() - began < 5_000)
        assertEquals(0, shown.get(), "a quitting instance does not show its window")
    }

    @Test
    fun aQuitThatNeverEndsLeavesTheNewStartOut() {
        val first = assertIs<InstanceStart.First>(start()).instance
        first.markQuitting()
        val second = SingleInstance(dir).start(onShowRequested = {}, quitWaitMs = 500)
        assertEquals(InstanceStart.AlreadyRunning, second)
    }

    @Test
    fun aStartThatWaitedStepsAsideWhenTheCheckSaysSo() {
        val first = assertIs<InstanceStart.First>(start()).instance
        first.markQuitting()
        Thread {
            Thread.sleep(300)
            first.close()
        }.start()
        // An update started installing while this start waited: it must not run the old version.
        val second = SingleInstance(dir).start(onShowRequested = {}, mayRunAfterWaiting = { false })
        assertEquals(InstanceStart.AlreadyRunning, second)
        assertIs<InstanceStart.First>(start(), "the lock was let go again")
    }
}
