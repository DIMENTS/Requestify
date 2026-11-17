package nl.diments.requestify.core.twitch.points

/** The last [capacity] ids added, oldest dropped first. Thread-safe. */
internal class RecentIds(private val capacity: Int) {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    operator fun contains(id: String): Boolean = id in ids

    /** Remembers [id]; false when it was known already. */
    @Synchronized
    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}
