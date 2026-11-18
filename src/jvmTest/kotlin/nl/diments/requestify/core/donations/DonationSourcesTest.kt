package nl.diments.requestify.core.donations

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.JsonFileStore

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class DonationSourcesTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-sources") }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private class Fake(val kind: DonationSourceKind, val log: MutableList<String>) : DonationSource {
        override val health = MutableStateFlow(DonationHealth(kind))
        override fun start() {
            log += "start ${kind.name}"
            health.value = DonationHealth(kind, DonationStatus.Connected)
        }
        override fun stop() {
            log += "stop ${kind.name}"
            health.value = DonationHealth(kind, DonationStatus.Off)
        }
    }

    private fun settings() = SettingsStore(JsonFileStore(dir.resolve("settings.json"), Settings.serializer()) { Settings() })

    private fun SettingsStore.choose(kind: DonationSourceKind) = tryUpdate { it.copy(donations = it.donations.copy(source = kind)) }

    @Test
    fun runsOnlyTheChosenSource() = runTest {
        val log = mutableListOf<String>()
        val settings = settings()
        val sources = DonationSources(settings, { Fake(DonationSourceKind.DonoLink, log) }, { Fake(DonationSourceKind.StreamElements, log) }, backgroundScope)
        sources.start()
        runCurrent()
        assertEquals(emptyList(), log, "nothing chosen: nothing runs")
        assertEquals(DonationHealth(), sources.health.value)

        settings.choose(DonationSourceKind.DonoLink)
        runCurrent()
        assertEquals(listOf("start DonoLink"), log)
        assertEquals(DonationHealth(DonationSourceKind.DonoLink, DonationStatus.Connected), sources.health.value)

        sources.stop()
        assertEquals(listOf("start DonoLink", "stop DonoLink"), log)
    }

    @Test
    fun switchingStopsTheOldOneFirst() = runTest {
        val log = mutableListOf<String>()
        val settings = settings()
        settings.choose(DonationSourceKind.DonoLink)
        val sources = DonationSources(settings, { Fake(DonationSourceKind.DonoLink, log) }, { Fake(DonationSourceKind.StreamElements, log) }, backgroundScope)
        sources.start()
        runCurrent()

        settings.choose(DonationSourceKind.StreamElements)
        runCurrent()
        assertEquals(listOf("start DonoLink", "stop DonoLink", "start StreamElements"), log)
        assertEquals(DonationSourceKind.StreamElements, sources.health.value.kind)

        settings.choose(DonationSourceKind.None)
        runCurrent()
        assertEquals("stop StreamElements", log.last())
        assertEquals(DonationHealth(), sources.health.value)
    }


    @Test
    fun restartCurrentRestartsTheRunningSource() = runTest {
        val log = mutableListOf<String>()
        val settings = settings()
        settings.choose(DonationSourceKind.DonoLink)
        val sources = DonationSources(settings, { Fake(DonationSourceKind.DonoLink, log) }, { Fake(DonationSourceKind.StreamElements, log) }, backgroundScope)
        sources.start()
        runCurrent()

        sources.restartCurrent()
        assertEquals(listOf("start DonoLink", "stop DonoLink", "start DonoLink"), log)
        assertEquals(DonationStatus.Connected, sources.health.value.status)
    }
}
