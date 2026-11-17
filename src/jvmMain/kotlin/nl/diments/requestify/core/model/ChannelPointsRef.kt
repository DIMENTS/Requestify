package nl.diments.requestify.core.model

/**
 * [Request.sourceRef] of a Channel Points request: "cp:<rewardId>:<redemptionId>", or "cpx:..." for a directly
 * linked reward that Requestify did not create ([managed] false).
 */
data class ChannelPointsRef(val rewardId: String, val redemptionId: String, val managed: Boolean) {
    fun format(): String = "${if (managed) "cp" else "cpx"}:$rewardId:$redemptionId"

    companion object {
        /** Null when [ref] is not a Channel Points reference. */
        fun parse(ref: String?): ChannelPointsRef? {
            val parts = ref?.split(":") ?: return null
            if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null
            return when (parts[0]) {
                "cp" -> ChannelPointsRef(parts[1], parts[2], managed = true)
                "cpx" -> ChannelPointsRef(parts[1], parts[2], managed = false)
                else -> null
            }
        }
    }
}
