package nl.diments.requestify.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinReg
import nl.diments.requestify.core.settings.SettingsStore

/** Whether Windows starts Requestify at sign-in. */
enum class AutostartState {
    On,
    Off,

    /** The entry is there, but switched off in Task Manager → Startup apps. */
    DisabledInTaskManager,

    /** Not an installed build (`gradlew run`): there is no launcher to start. */
    Unavailable,
}

/** Starting with Windows. One of the two places (with [Installation]) that know how Requestify is packaged. */
interface Autostart {
    fun state(): AutostartState
    /** False when Windows refused it (a policy or virus scanner can deny the registry): it never throws. */
    fun enable(): Boolean

    /** False when Windows refused it; it never throws. */
    fun disable(): Boolean

    /** Points an existing entry at the launcher as it is installed now (after a reinstall elsewhere). */
    fun repairPath()
}

/** The current user's registry (HKEY_CURRENT_USER), the little [RunKeyAutostart] needs of it. */
interface RegistryPort {
    fun getString(key: String, name: String): String?
    fun setString(key: String, name: String, value: String)
    fun delete(key: String, name: String)
    fun getBinary(key: String, name: String): ByteArray?
    fun setBinary(key: String, name: String, value: ByteArray)
}

/** [RegistryPort] on HKEY_CURRENT_USER through JNA. A missing key or value reads as null. */
object JnaRegistry : RegistryPort {
    private val root = WinReg.HKEY_CURRENT_USER

    override fun getString(key: String, name: String): String? = read { Advapi32Util.registryGetStringValue(root, key, name) }

    override fun setString(key: String, name: String, value: String) {
        if (!Advapi32Util.registryKeyExists(root, key)) Advapi32Util.registryCreateKey(root, key)
        Advapi32Util.registrySetStringValue(root, key, name, value)
    }

    override fun delete(key: String, name: String) {
        if (Advapi32Util.registryValueExists(root, key, name)) Advapi32Util.registryDeleteValue(root, key, name)
    }

    override fun getBinary(key: String, name: String): ByteArray? = read { Advapi32Util.registryGetBinaryValue(root, key, name) }

    override fun setBinary(key: String, name: String, value: ByteArray) {
        if (!Advapi32Util.registryKeyExists(root, key)) Advapi32Util.registryCreateKey(root, key)
        Advapi32Util.registrySetBinaryValue(root, key, name, value)
    }

    private fun <T : Any> read(block: () -> T): T? = try {
        block()
    } catch (_: Win32Exception) {
        null
    }
}

/**
 * Starting with Windows through the per-user Run key (no admin rights): `"<launcher>" --autostart`. Task Manager's
 * Startup switch writes a StartupApproved value next to it; an odd first byte means the streamer switched it off
 * there, which [state] reports and nothing here undoes except an explicit [enable].
 */
class RunKeyAutostart(
    private val registry: RegistryPort,
    private val installation: Installation,
    private val valueName: String = "Requestify",
) : Autostart {
    override fun state(): AutostartState {
        if (!installation.isInstalled) return AutostartState.Unavailable
        registry.getString(RUN_KEY, valueName) ?: return AutostartState.Off
        val approved = registry.getBinary(APPROVED_KEY, valueName)
        return if (approved != null && approved.isNotEmpty() && approved[0].toInt() and 1 == 1) {
            AutostartState.DisabledInTaskManager
        } else {
            AutostartState.On
        }
    }

    override fun enable(): Boolean {
        val command = command() ?: return false
        return tryWrite {
            registry.setString(RUN_KEY, valueName, command)
            registry.setBinary(APPROVED_KEY, valueName, ENABLED)
        }
    }

    override fun disable(): Boolean {
        if (!installation.isInstalled) return false
        return tryWrite {
            registry.delete(RUN_KEY, valueName)
            registry.delete(APPROVED_KEY, valueName)
        }
    }

    override fun repairPath() {
        val command = command() ?: return
        val current = registry.getString(RUN_KEY, valueName) ?: return
        if (current != command) tryWrite { registry.setString(RUN_KEY, valueName, command) }
    }

    /** A registry write that Windows may refuse: false then, never an exception (it would end the app at start). */
    private fun tryWrite(write: () -> Unit): Boolean = try {
        write()
        true
    } catch (_: RuntimeException) {
        false
    }

    /** The launcher in quotes (its path has spaces: Program Files) and the autostart argument. */
    private fun command(): String? = installation.launcherExe?.let { "\"$it\" $AUTOSTART_ARG" }

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

        /** What Task Manager writes for "Enabled". */
        val ENABLED = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * At start: an installed app whose streamer never chose turns it on once (the parent design's default) and remembers
 * that; one that is on gets its entry pointed at the launcher as installed now; one the streamer switched off, in
 * Settings or in Task Manager, is left alone.
 */
fun applyAutostartAtStart(settings: SettingsStore, autostart: Autostart) {
    // The streamer's choice cannot be read yet: never undo it (a Task Manager disable included).
    if (settings.loadFailed.value || autostart.state() == AutostartState.Unavailable) return
    when (settings.settings.value.startWithWindows) {
        null -> if (autostart.enable()) settings.tryUpdate { it.copy(startWithWindows = true) }
        true -> autostart.repairPath()
        false -> Unit
    }
}

/** No starting with Windows here (tests, and builds that cannot): always [AutostartState.Unavailable]. */
object NoAutostart : Autostart {
    override fun state() = AutostartState.Unavailable
    override fun enable() = false
    override fun disable() = false
    override fun repairPath() {}
}
