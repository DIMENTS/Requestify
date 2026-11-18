package nl.diments.requestify.core.storage

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPathsTest {
    private lateinit var dir: Path

    @BeforeTest fun setUp() { dir = createTempDirectory("requestify-paths") }
    @AfterTest fun tearDown() { dir.deleteTestDir() }

    @Test
    fun updatesAreNotRoaming() {
        val local = dir.resolve("Local")
        val roaming = dir.resolve("Roaming")
        val env = mapOf("LOCALAPPDATA" to local.toString(), "APPDATA" to roaming.toString())
        val paths = AppPaths.default(env::get)
        assertEquals(roaming.resolve("Requestify"), paths.dataDir)
        assertEquals(local.resolve("Requestify-updates"), paths.updatesDir, "a downloaded installer never roams with the profile")
    }

    @Test
    fun withoutTheVariablesTheUsualFoldersAreUsed() {
        val home = dir.toString()
        val paths = AppPaths.default(env = { null }, userHome = home)
        assertEquals(dir.resolve("AppData").resolve("Roaming").resolve("Requestify"), paths.dataDir)
        assertEquals(dir.resolve("AppData").resolve("Local").resolve("Requestify-updates"), paths.updatesDir)
    }
}
