package nl.diments.requestify.desktop

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore
import nl.diments.requestify.core.storage.deleteTestDir
import nl.diments.requestify.core.storage.inCallerSaveContext

class AutostartTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-autostart") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    /** HKCU in memory: key → name → value. */
    private class FakeRegistry : RegistryPort {
        val strings = HashMap<String, MutableMap<String, String>>()
        val binaries = HashMap<String, MutableMap<String, ByteArray>>()
        var writes = 0
        override fun getString(key: String, name: String) = strings[key]?.get(name)
        override fun setString(key: String, name: String, value: String) { writes++; strings.getOrPut(key) { HashMap() }[name] = value }
        override fun delete(key: String, name: String) { writes++; strings[key]?.remove(name); binaries[key]?.remove(name) }
        override fun getBinary(key: String, name: String) = binaries[key]?.get(name)
        override fun setBinary(key: String, name: String, value: ByteArray) { writes++; binaries.getOrPut(key) { HashMap() }[name] = value }
    }

    private val registry = FakeRegistry()
    private val exe = Path.of("C:\\Program Files\\Requestify\\Requestify.exe")
    private val run = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val approved = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

    private fun autostart(installation: Installation = Installation(exe)) = RunKeyAutostart(registry, installation)
    private fun settings() = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() }, inCallerSaveContext())

    @Test
    fun theRunEntryQuotesThePathAndMarksAutostart() {
        autostart().enable()
        assertEquals("\"C:\\Program Files\\Requestify\\Requestify.exe\" --autostart", registry.getString(run, "Requestify"))
        assertEquals(AutostartState.On, autostart().state())
    }

    @Test
    fun firstInstalledStartTurnsItOn() {
        val settings = settings()
        applyAutostartAtStart(settings, autostart())
        assertEquals(AutostartState.On, autostart().state())
        assertEquals(true, settings.settings.value.startWithWindows)
    }

    @Test
    fun switchedOffStaysOff() {
        val settings = settings()
        settings.tryUpdate { it.copy(startWithWindows = false) }
        applyAutostartAtStart(settings, autostart())
        assertEquals(AutostartState.Off, autostart().state())
        assertEquals(0, registry.writes, "nothing turns it back on")
    }

    @Test
    fun taskManagerDisabledIsReported() {
        autostart().enable()
        registry.setBinary(approved, "Requestify", byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) // Task Manager → Disable
        assertEquals(AutostartState.DisabledInTaskManager, autostart().state())

        val settings = settings()
        settings.tryUpdate { it.copy(startWithWindows = true) }
        applyAutostartAtStart(settings, autostart())
        assertEquals(AutostartState.DisabledInTaskManager, autostart().state(), "a start never undoes Task Manager")
    }

    @Test
    fun enablingClearsTaskManagersDisable() {
        registry.setBinary(approved, "Requestify", byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        autostart().enable()
        assertContentEquals(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), registry.getBinary(approved, "Requestify"))
        assertEquals(AutostartState.On, autostart().state())
        autostart().disable()
        assertEquals(AutostartState.Off, autostart().state())
    }

    @Test
    fun notInstalledIsUnavailableAndWritesNothing() {
        val notInstalled = autostart(Installation(null))
        val settings = settings()
        assertEquals(AutostartState.Unavailable, notInstalled.state())
        notInstalled.enable()
        applyAutostartAtStart(settings, notInstalled)
        assertEquals(0, registry.writes)
        assertEquals(null, settings.settings.value.startWithWindows, "still undecided for the installed app")
    }

    @Test
    fun aMovedInstallUpdatesTheEntry() {
        registry.setString(run, "Requestify", "\"D:\\Old\\Requestify.exe\" --autostart")
        val settings = settings()
        settings.tryUpdate { it.copy(startWithWindows = true) }
        applyAutostartAtStart(settings, autostart())
        assertTrue(registry.getString(run, "Requestify")!!.startsWith("\"C:\\Program Files\\Requestify\\Requestify.exe\""))
    }
}
