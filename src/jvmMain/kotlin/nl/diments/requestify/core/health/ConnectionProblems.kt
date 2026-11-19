package nl.diments.requestify.core.health

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import nl.diments.requestify.core.donations.DonationHealth
import nl.diments.requestify.core.donations.DonationStatus
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind

/** What is wrong with the donation source. */
enum class DonationProblem { KeyRefused, NoKey, Disconnected, KeyExpiring }

/** Something that needs the streamer's attention on Connections: the title-bar chip, the tray and the notifier. */
sealed interface ConnectionProblem {
    data class Spotify(val health: SpotifyHealth) : ConnectionProblem
    data object TwitchLoginNeeded : ConnectionProblem

    /** The bot's login was refused: replies come from the streamer until the bot logs in again. */
    data object TwitchBotLoginNeeded : ConnectionProblem

    /** The channel does not let the bot talk ([reason], Twitch's words): replies come from the streamer meanwhile. */
    data class TwitchBotBlocked(val reason: String) : ConnectionProblem
    data object TwitchDisconnected : ConnectionProblem
    data object RewardRemoved : ConnectionProblem

    /**
     * [keyExpiresAtEpochMs]: when the DonoLink key stops working, and [daysLeft] whole days until then, for
     * [DonationProblem.KeyExpiring]. The days are part of the problem so the list changes (and is shown again) as they
     * count down.
     */
    data class Donation(
        val kind: DonationSourceKind,
        val problem: DonationProblem,
        val keyExpiresAtEpochMs: Long? = null,
        val daysLeft: Int? = null,
    ) : ConnectionProblem
}

/** What Twitch's session reports; all false (nothing wrong) until the session is wired. */
data class TwitchHealth(
    val loginNeeded: Boolean = false,
    val disconnected: Boolean = false,
    val rewardRemoved: Boolean = false,
    /** The bot's login was refused (see `TwitchBotHealth`). */
    val botLoginNeeded: Boolean = false,
    /** Why the channel holds the bot back; null while it does not. */
    val botBlockedReason: String? = null,
)

/** A DonoLink key is warned about from this long before it expires. */
const val KEY_WARNING_MS = 7 * 24 * 3_600_000L

/**
 * Everything that is wrong right now, most important first: the logins and keys first (Spotify, Twitch, the donation
 * source), then a missing Spotify device, then connections that are down, then Twitch's removed reward, then a
 * DonoLink key that expires within a week ([nowMs]). [SpotifyHealth.Ok] and [SpotifyHealth.NothingPlaying] are not
 * problems.
 */
fun connectionProblems(
    spotify: SpotifyHealth,
    twitch: TwitchHealth,
    donations: DonationHealth = DonationHealth(),
    nowMs: Long = System.currentTimeMillis(),
): List<ConnectionProblem> = buildList {
    when (spotify) {
        SpotifyHealth.LoggedOut, SpotifyHealth.LoginNeeded, SpotifyHealth.PremiumRequired ->
            add(ConnectionProblem.Spotify(spotify))
        else -> Unit
    }
    if (twitch.loginNeeded) add(ConnectionProblem.TwitchLoginNeeded)
    if (twitch.botLoginNeeded) add(ConnectionProblem.TwitchBotLoginNeeded)
    val kind = donations.kind
    if (kind != DonationSourceKind.None && donations.status == DonationStatus.KeyRefused) {
        add(ConnectionProblem.Donation(kind, DonationProblem.KeyRefused))
    }
    if (kind != DonationSourceKind.None && donations.status == DonationStatus.NoKey) {
        add(ConnectionProblem.Donation(kind, DonationProblem.NoKey))
    }
    if (spotify == SpotifyHealth.NoActiveDevice) add(ConnectionProblem.Spotify(spotify))
    if (spotify is SpotifyHealth.Unreachable || spotify is SpotifyHealth.RateLimited) add(ConnectionProblem.Spotify(spotify))
    if (twitch.disconnected) add(ConnectionProblem.TwitchDisconnected)
    twitch.botBlockedReason?.let { add(ConnectionProblem.TwitchBotBlocked(it)) }
    if (kind != DonationSourceKind.None && donations.status == DonationStatus.Retrying) {
        add(ConnectionProblem.Donation(kind, DonationProblem.Disconnected))
    }
    if (twitch.rewardRemoved) add(ConnectionProblem.RewardRemoved)
    val expires = donations.keyExpiresAtEpochMs
    if (kind == DonationSourceKind.DonoLink && donations.status != DonationStatus.Off && donations.status != DonationStatus.KeyRefused &&
        expires != null && expires - nowMs <= KEY_WARNING_MS
    ) {
        add(ConnectionProblem.Donation(kind, DonationProblem.KeyExpiring, expires, ((expires - nowMs).coerceAtLeast(0) / DAY_MS).toInt()))
    }
}

/**
 * The time, now and then again every [everyMs] and at each local midnight ([zone]), so what depends on the time alone
 * (a key that expires within a week, the days it has left) is looked at again without any other change.
 */
fun clockTicks(clock: () -> Long, zone: ZoneId, everyMs: Long = 3_600_000L): Flow<Long> = flow {
    while (true) {
        val now = clock()
        emit(now)
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        delay(minOf(now + everyMs, midnight) - now)
    }
}

/** [connectionProblems] as health changes and as [ticks] pass, at the time of the latest tick. */
fun problemsOverTime(
    spotify: Flow<SpotifyHealth>,
    twitch: Flow<TwitchHealth>,
    donations: Flow<DonationHealth>,
    ticks: Flow<Long>,
): Flow<List<ConnectionProblem>> = combine(spotify, twitch, donations, ticks) { s, t, d, now -> connectionProblems(s, t, d, now) }

private const val DAY_MS = 24 * 3_600_000L
