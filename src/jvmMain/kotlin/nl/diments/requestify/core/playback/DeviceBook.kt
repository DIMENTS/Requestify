package nl.diments.requestify.core.playback

import nl.diments.requestify.core.spotify.SpotifyDevice

/**
 * When Spotify's devices are read while it has nothing active, which one is made active, and whether the last read
 * found none at all. Not thread-safe: used only under the controller's `spotifyMutex`, like [UpcomingBook].
 */
internal class DeviceBook(private val config: PlaybackConfig, private val clock: () -> Long) {
    private var readAtMs: Long? = null // null: read them on the next tick that finds nothing active

    /**
     * The last read found no device that takes commands, so Spotify is open nowhere: until a read finds one or
     * something is active.
     */
    var noDevice = false
        private set

    /** Whether the devices are read on this tick: at once when nothing is active, then every [PlaybackConfig.deviceCheckMs]. */
    fun due(): Boolean {
        val readAt = readAtMs ?: return true
        return clock() - readAt >= config.deviceCheckMs
    }

    /** The devices are being read now. */
    fun markRead() {
        readAtMs = clock()
    }

    /** A command found no active device: read the devices on the next tick. */
    fun markStale() {
        readAtMs = null
    }

    /** Something is active: read the devices as soon as nothing is. */
    fun reset() {
        readAtMs = null
        noDevice = false
    }

    /**
     * The device to make active, from [devices] as Spotify lists them: the first one that takes commands. Null when
     * one is active already (it is left alone), or when none takes commands ([noDevice] then).
     */
    fun pick(devices: List<SpotifyDevice>): String? {
        val usable = devices.filter { it.id != null && !it.isRestricted }
        val active = devices.any { it.isActive }
        noDevice = !active && usable.isEmpty()
        return if (active) null else usable.firstOrNull()?.id
    }
}
