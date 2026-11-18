package nl.diments.requestify.desktop

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Started by Windows at sign-in. */
const val AUTOSTART_ARG = "--autostart"

/** Started again by the crash guard after an error. */
const val AFTER_CRASH_ARG = "--after-crash"

/** Run by the uninstaller only: removes the autostart entry ([cleanUpForUninstall]) and exits at once. */
const val UNINSTALL_CLEANUP_ARG = "--uninstall-cleanup"

/** How this run was started: by the streamer, by Windows at sign-in, or by the crash guard. */
enum class LaunchMode { Normal, Autostart, AfterCrash }

fun launchMode(args: Array<String>): LaunchMode = when {
    AFTER_CRASH_ARG in args -> LaunchMode.AfterCrash
    AUTOSTART_ARG in args -> LaunchMode.Autostart
    else -> LaunchMode.Normal
}

/**
 * Where the installed app's launcher is: null when this is not an installed build (`gradlew run`). This and
 * [Autostart] are the only places that know how Requestify is packaged, so a later installer can replace them.
 */
data class Installation(val launcherExe: Path?) {
    val isInstalled: Boolean get() = launcherExe != null

    companion object {
        /** The running app: jpackage's launcher sets `jpackage.app-path` to its own exe. */
        fun current(): Installation = fromAppPath(System.getProperty("jpackage.app-path"))

        fun fromAppPath(appPath: String?): Installation {
            val exe = try {
                appPath?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            } catch (_: InvalidPathException) {
                null
            }
            return Installation(exe?.takeIf { Files.isRegularFile(it) })
        }
    }
}
