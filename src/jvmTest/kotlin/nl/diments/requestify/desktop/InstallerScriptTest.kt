package nl.diments.requestify.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Requestify's own additions to jpackage's WiX script (packaging/wix/main.wxs), as Windows Installer will run them. */
class InstallerScriptTest {
    private val script = Files.readString(Path.of("packaging/wix/main.wxs"))

    @Test
    fun aFailedUpgradeBringsTheOldVersionBack() {
        // Inside the install transaction: if the new version fails to install, the removal of the old one rolls back.
        // jpackage's own place (before CostInitialize) commits the removal first: a failed update left no Requestify.
        assertTrue("""<RemoveExistingProducts After="InstallInitialize"/>""" in script, "RemoveExistingProducts must run inside the transaction")
        assertTrue("Before=\"CostInitialize\"" !in script)
    }

    @Test
    fun onlyARealUninstallCleansUp() {
        assertTrue("""<Custom Action="RequestifyUninstallCleanup" Before="RemoveFiles">REMOVE~="ALL" AND NOT UPGRADINGPRODUCTCODE</Custom>""" in script)
    }

    @Test
    fun onlyAHandInstallStartsRequestify() {
        assertTrue("""<Custom Action="RequestifyLaunch" After="ExecuteAction">NOT Installed AND NOT REMOVE AND NOT REQUESTIFY_UPDATER</Custom>""" in script)
    }
}
