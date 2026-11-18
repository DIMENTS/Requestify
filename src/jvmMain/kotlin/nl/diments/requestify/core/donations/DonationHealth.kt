package nl.diments.requestify.core.donations

import kotlinx.coroutines.flow.StateFlow
import nl.diments.requestify.core.settings.DonationSourceKind

/** [NoKey]: no key or token is saved yet; [KeyRefused]: the platform refused the saved one. */
enum class DonationStatus { Off, Connecting, Connected, Retrying, KeyRefused, NoKey }

/**
 * How the donation source is doing, for the connection chip, the Donations card and the Log. [retryingSinceMs]: when
 * it last worked, while it is [DonationStatus.Retrying]. [keyExpiresAtEpochMs]: when the DonoLink key stops working.
 * [canCatchUp]: false when donations made while Requestify is off cannot be fetched afterwards.
 */
data class DonationHealth(
    val kind: DonationSourceKind = DonationSourceKind.None,
    val status: DonationStatus = DonationStatus.Off,
    val retryingSinceMs: Long? = null,
    val keyExpiresAtEpochMs: Long? = null,
    val canCatchUp: Boolean = true,
)

/** One donation platform, run from [start] until [stop]. */
interface DonationSource {
    val health: StateFlow<DonationHealth>
    fun start()
    fun stop()
}
