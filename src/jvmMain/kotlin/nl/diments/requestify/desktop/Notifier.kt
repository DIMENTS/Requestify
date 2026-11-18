package nl.diments.requestify.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import nl.diments.requestify.core.health.clockTicks
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.diments.requestify.core.health.ConnectionProblem
import nl.diments.requestify.core.health.DonationProblem
import nl.diments.requestify.core.playback.SpotifyHealth
import nl.diments.requestify.core.settings.DonationSourceKind
import nl.diments.requestify.core.settings.KeyExpiryNotice
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.storage.SaveProblem
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.notice_close_hint_text
import nl.diments.requestify.resources.notice_close_hint_title
import nl.diments.requestify.resources.notice_donolink_key_text
import nl.diments.requestify.resources.notice_donolink_refused_title
import nl.diments.requestify.resources.notice_key_expiring_days_title
import nl.diments.requestify.resources.notice_key_expiring_hours_title
import nl.diments.requestify.resources.notice_key_expiring_one_day_title
import nl.diments.requestify.resources.notice_log_in_again_text
import nl.diments.requestify.resources.notice_premium_text
import nl.diments.requestify.resources.notice_premium_title
import nl.diments.requestify.resources.notice_restarted_text
import nl.diments.requestify.resources.notice_restarted_title
import nl.diments.requestify.resources.notice_reward_removed_text
import nl.diments.requestify.resources.notice_reward_removed_title
import nl.diments.requestify.resources.notice_saving_fails_text
import nl.diments.requestify.resources.notice_saving_fails_title
import nl.diments.requestify.resources.notice_spotify_login_title
import nl.diments.requestify.resources.notice_streamelements_refused_text
import nl.diments.requestify.resources.notice_streamelements_refused_title
import nl.diments.requestify.resources.notice_twitch_login_title
import org.jetbrains.compose.resources.getString

/** Where a click on a notification takes the streamer. */
enum class NoticeTarget { Home, Connections }

/** A Windows notification: only for what the streamer must act on (user's decision), plus two that explain the app. */
sealed interface Notice {
    val target: NoticeTarget get() = NoticeTarget.Connections

    data object SpotifyLoginNeeded : Notice
    data object SpotifyPremiumMissing : Notice
    data object TwitchLoginNeeded : Notice
    data class KeyRefused(val kind: DonationSourceKind) : Notice

    /** [daysLeft]: whole days until the DonoLink key expires (0: within 24 hours). */
    data class KeyExpiring(val daysLeft: Int) : Notice
    data object RewardRemoved : Notice

    data class SavingFails(val files: List<String>) : Notice {
        override val target: NoticeTarget get() = NoticeTarget.Home
    }

    data object RestartedAfterCrash : Notice {
        override val target: NoticeTarget get() = NoticeTarget.Home
    }

    data object CloseHint : Notice {
        override val target: NoticeTarget get() = NoticeTarget.Home
    }
}

/**
 * Sends a Windows notification when something starts that only the streamer can fix, following the same problem list
 * as the title-bar chip ([problems]) and the files that cannot be saved ([saveProblems]):
 * - a login or key problem is notified once when it starts (also when it is there at start: the app may have started
 *   hidden), and again only after it went away and came back;
 * - a DonoLink key that expires is notified once within its last week and once on its last day, per key, also across
 *   restarts ([KeyExpiryNotice] in the settings);
 * - a save outage only once it has lasted [savingNoticeAfterMs] (a short lock by a virus scanner fixes itself).
 * Anything else (a dropped connection, no active device, a deliberate log-out) is never notified.
 */
class Notifier(
    private val problems: StateFlow<List<ConnectionProblem>>,
    private val saveProblems: StateFlow<List<SaveProblem>>,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val send: (Notice) -> Unit,
    private val savingNoticeAfterMs: Long = 120_000,
    /** A problem gone for less than this that comes back is the same one: no second notification. */
    private val rearmAfterMs: Long = 600_000,
    /** When to look at a key's expiry again although nothing else changed (its days count down by themselves). */
    private val ticks: Flow<Long> = clockTicks(clock, ZoneId.systemDefault()),
) {
    /** The notifiable problems seen last time: one that is already here is not notified again. */
    private var present = emptySet<Notice>()
    private var savingWatch: Job? = null

    fun start() {
        scope.launch { problems.collect { onProblems(it) } }
        scope.launch { saveProblems.collect { onSaves(it) } }
        scope.launch { ticks.collect { checkKeyExpiry(problems.value) } }
    }

    /** When each notified problem went away: it is notified again only after a real absence ([rearmAfterMs]). */
    private val goneSince = HashMap<Notice, Long>()

    private fun onProblems(list: List<ConnectionProblem>) {
        val now = list.mapNotNull { it.notice() }.toSet()
        val at = clock()
        (present - now).forEach { goneSince[it] = at }
        for (notice in now - present) {
            val gone = goneSince.remove(notice)
            // A problem that flaps (Premium shows on each failed hand-over and clears at the next tick) is no new one.
            if (gone == null || at - gone >= rearmAfterMs) send(notice)
        }
        present = now
        checkKeyExpiry(list)
    }

    private fun checkKeyExpiry(list: List<ConnectionProblem>) {
        list.filterIsInstance<ConnectionProblem.Donation>()
            .firstOrNull { it.problem == DonationProblem.KeyExpiring && it.keyExpiresAtEpochMs != null }
            ?.let { onKeyExpiring(it.keyExpiresAtEpochMs!!) }
    }

    private fun onKeyExpiring(expiresAt: Long) {
        val left = expiresAt - clock()
        val stage = if (left < DAY_MS) 1 else 7 // its last day: under 24 hours, so the text says "within 24 hours"
        val notified = settings.settings.value.keyExpiryNotified?.takeIf { it.expiresAtEpochMs == expiresAt }
        if (notified != null && notified.stage <= stage) return
        send(Notice.KeyExpiring(daysLeft = (left.coerceAtLeast(0) / DAY_MS).toInt()))
        settings.tryUpdate { it.copy(keyExpiryNotified = KeyExpiryNotice(expiresAt, stage)) }
    }

    private fun onSaves(list: List<SaveProblem>) {
        if (list.isEmpty()) {
            savingWatch?.cancel()
            savingWatch = null
            return
        }
        if (savingWatch != null) return
        savingWatch = scope.launch {
            delay(savingNoticeAfterMs)
            val still = saveProblems.value
            if (still.isNotEmpty()) send(Notice.SavingFails(still.map { it.file }))
        }
    }

    private fun ConnectionProblem.notice(): Notice? = when (this) {
        is ConnectionProblem.Spotify -> when (health) {
            SpotifyHealth.LoginNeeded -> Notice.SpotifyLoginNeeded
            SpotifyHealth.PremiumRequired -> Notice.SpotifyPremiumMissing
            else -> null
        }
        ConnectionProblem.TwitchLoginNeeded -> Notice.TwitchLoginNeeded
        ConnectionProblem.RewardRemoved -> Notice.RewardRemoved
        is ConnectionProblem.Donation -> if (problem == DonationProblem.KeyRefused) Notice.KeyRefused(kind) else null
        ConnectionProblem.TwitchDisconnected -> null
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}

/** A notification's title and text, in the app's language. */
suspend fun noticeText(notice: Notice): Pair<String, String> = when (notice) {
    Notice.SpotifyLoginNeeded -> getString(Res.string.notice_spotify_login_title) to getString(Res.string.notice_log_in_again_text)
    Notice.SpotifyPremiumMissing -> getString(Res.string.notice_premium_title) to getString(Res.string.notice_premium_text)
    Notice.TwitchLoginNeeded -> getString(Res.string.notice_twitch_login_title) to getString(Res.string.notice_log_in_again_text)
    is Notice.KeyRefused -> if (notice.kind == DonationSourceKind.StreamElements) {
        getString(Res.string.notice_streamelements_refused_title) to getString(Res.string.notice_streamelements_refused_text)
    } else {
        getString(Res.string.notice_donolink_refused_title) to getString(Res.string.notice_donolink_key_text)
    }
    is Notice.KeyExpiring -> when (notice.daysLeft) {
        0 -> getString(Res.string.notice_key_expiring_hours_title)
        1 -> getString(Res.string.notice_key_expiring_one_day_title)
        else -> getString(Res.string.notice_key_expiring_days_title, notice.daysLeft)
    } to getString(Res.string.notice_donolink_key_text)
    Notice.RewardRemoved -> getString(Res.string.notice_reward_removed_title) to getString(Res.string.notice_reward_removed_text)
    is Notice.SavingFails ->
        getString(Res.string.notice_saving_fails_title) to getString(Res.string.notice_saving_fails_text, notice.files.joinToString())
    Notice.RestartedAfterCrash -> getString(Res.string.notice_restarted_title) to getString(Res.string.notice_restarted_text)
    Notice.CloseHint -> getString(Res.string.notice_close_hint_title) to getString(Res.string.notice_close_hint_text)
}
