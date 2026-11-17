package nl.diments.requestify.core.rules

import kotlinx.serialization.Serializable

/** The streamer's rules for requests. Saved in settings.json: new fields need defaults. */
@Serializable
data class Rules(
    val maxQueueLength: Int = 25,
    val maxPerViewer: Int = 2,
    /** 0 is off. */
    val cooldownMinutes: Int = 0,
    val noDuplicates: Boolean = true,
    val duplicateWindowMinutes: Int = 60,
    val minDurationMs: Long = 30_000,
    val maxDurationMs: Long = 420_000,
    val blockExplicit: Boolean = false,
)
