package nl.diments.requestify

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.desktop.InstanceStart
import nl.diments.requestify.desktop.LaunchMode
import nl.diments.requestify.desktop.NoticeTarget
import nl.diments.requestify.desktop.SingleInstance
import nl.diments.requestify.ui.Destination

class AppStartTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-start") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    @Test
    fun onlyANormalStartShowsTheWindow() {
        assertTrue(initiallyVisible(LaunchMode.Normal))
        assertFalse(initiallyVisible(LaunchMode.Autostart), "Windows' autostart: hidden in the tray")
        assertFalse(initiallyVisible(LaunchMode.AfterCrash), "the crash restart: hidden in the tray")
    }

    @Test
    fun aShowRequestBeforeTheWindowIsKept() {
        val shell = ShellState(visible = false)
        shell.showRequested() // a second start asks while this one is still starting
        assertTrue(shell.visible, "the window shows once it is composed")
        val raised = shell.raiseRequests
        shell.showRequested()
        assertTrue(shell.raiseRequests > raised, "an open window is brought to the front again")
    }

    @Test
    fun aNoticeClickOpensItsTarget() {
        val shell = ShellState(visible = false)
        shell.noticeClicked(NoticeTarget.Connections)
        assertTrue(shell.visible)
        assertEquals(Destination.Connections, shell.openOn)
        shell.opened()
        assertEquals(null, shell.openOn, "once shown, the streamer navigates freely")
        shell.noticeClicked(NoticeTarget.Home)
        assertEquals(Destination.Home, shell.openOn)
    }

    @Test
    fun anotherInstanceExitsWithoutTouchingFiles() {
        val first = assertIs<InstanceStart.First>(SingleInstance(dir).start(onShowRequested = {}))
        try {
            val before = dir.listDirectoryEntries().map { it.name }.toSet()
            assertEquals(InstanceStart.AlreadyRunning, SingleInstance(dir).start(onShowRequested = {}))
            assertEquals(before, dir.listDirectoryEntries().map { it.name }.toSet(), "a second start writes nothing")
        } finally {
            first.instance.close()
        }
    }
}
