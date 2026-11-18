package nl.diments.requestify.core.log

/**
 * The run before this one did not end properly: it was last alive at [lastAliveEpochMs]; [crashLine] is the first line
 * of its crash file when it crashed (null when it was killed or lost power); [restarted] when this run is the crash
 * guard's restart.
 */
data class PreviousRun(val lastAliveEpochMs: Long, val crashLine: String?, val restarted: Boolean)
