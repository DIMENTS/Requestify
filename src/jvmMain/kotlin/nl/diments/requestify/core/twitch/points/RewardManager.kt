package nl.diments.requestify.core.twitch.points

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.settings.ChannelPointsSettings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.twitch.Reward
import nl.diments.requestify.core.twitch.RewardPatch
import nl.diments.requestify.core.twitch.RewardSpec
import nl.diments.requestify.core.twitch.TwitchApi
import nl.diments.requestify.core.twitch.TwitchResult
import nl.diments.requestify.core.twitch.eventsub.EventSubClient
import nl.diments.requestify.core.twitch.eventsub.EventSubEvent
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.twitch_points_default_prompt
import nl.diments.requestify.resources.twitch_points_default_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** A reward of the channel, and whether this app may manage it (Twitch lets only the app that made a reward do so). */
data class ScannedReward(val reward: Reward, val manageable: Boolean)

sealed interface RewardActionResult {
    data object Ok : RewardActionResult

    /** Twitch refused the name: another reward of the channel has it. */
    data object DuplicateTitle : RewardActionResult

    /** The channel is no Affiliate or Partner. */
    data object NotAffiliate : RewardActionResult

    /** Twitch could not be reached, or refused for another reason. */
    data object Failed : RewardActionResult
}

private const val DEFAULT_COST = 500

/**
 * The Channel Points reward Requestify takes requests on: creating Requestify's own, scanning and linking the
 * channel's others, editing it and keeping it in step with the stream. The choice is saved in
 * `settings.twitch.channelPoints` and announced to [rewardChanged] (EventSub subscribes the new reward's events).
 *
 * - **Own reward** (`managed`): name, cost and text are written to Twitch; the reward is paused while requests are
 *   closed (only while switched on) and on a graceful exit ([pauseForExit]); switching it off disables it and never
 *   deletes it.
 * - **A foreign reward linked directly** (`managed = false`) is never written to: Twitch refuses updates of a reward
 *   another app made.
 * - **Twitch's own reward events** ([start]): a changed title, cost or prompt is adopted into the settings, and a
 *   switch or pause that differs from the app's own is written back; a removed reward is marked in
 *   `rewardRemoved`, which the card turns into "Create again".
 *
 * Runs on [scope]; calls that write to Twitch are serialised.
 */
class RewardManager(
    private val api: TwitchApi,
    private val settings: SettingsStore,
    private val events: Flow<EventSubEvent>,
    private val rewardChanged: () -> Unit,
    val requestsOpen: StateFlow<Boolean>,
    private val language: () -> AppLanguage,
    private val scope: CoroutineScope,
    private val retryFirstMs: Long = 5_000,
    private val retryMaxMs: Long = 300_000,
) {
    constructor(
        api: TwitchApi,
        settings: SettingsStore,
        eventSub: EventSubClient,
        requestsOpen: StateFlow<Boolean>,
        language: () -> AppLanguage,
        scope: CoroutineScope,
    ) : this(api, settings, eventSub.events, eventSub::rewardChanged, requestsOpen, language, scope)

    /** What the reward is known to be on Twitch: from its last answer or event. Guarded by [lock]. */
    private data class Known(val id: String, val enabled: Boolean, val paused: Boolean)

    private val lock = Mutex()
    private var known: Known? = null
    private var jobs: List<Job> = emptyList()

    /** Bumped by every sync outcome: a retry that was scheduled before the latest one is stale and does nothing. */
    private var retryGeneration = 0L
    private var retryDelayMs = retryFirstMs
    private val _outOfSync = MutableStateFlow(false)

    /** True while Twitch's enabled or paused flag is not what the app wants (a change was refused or not reached). */
    val outOfSync: StateFlow<Boolean> = _outOfSync.asStateFlow()

    private enum class Synced { Yes, Retry, LoggedOut }

    private fun points() = settings.settings.value.twitch.channelPoints

    /** The channel's rewards, each marked manageable or not; null when Twitch could not answer. */
    suspend fun scan(): List<ScannedReward>? {
        val all = (api.rewards(onlyManageable = false) as? TwitchResult.Ok)?.value ?: return null
        val own = (api.rewards(onlyManageable = true) as? TwitchResult.Ok)?.value?.mapTo(HashSet()) { it.id } ?: return null
        return all.map { ScannedReward(it, it.id in own) }
    }

    /**
     * Creates Requestify's own reward and links it. The defaults come from the app language; when the reward was
     * removed on Twitch, the new one has the name, cost and text the old one had.
     */
    suspend fun createNew(): RewardActionResult {
        val p = points()
        val spec = if (p.rewardRemoved && p.title != null) {
            RewardSpec(p.title, p.cost, p.prompt ?: text(Res.string.twitch_points_default_prompt))
        } else {
            RewardSpec(text(Res.string.twitch_points_default_title), DEFAULT_COST, text(Res.string.twitch_points_default_prompt))
        }
        return create(spec)
    }

    /** Creates Requestify's own reward with [from]'s cost and text, under [title] (Twitch needs it to be unique). */
    suspend fun copy(from: Reward, title: String = from.title): RewardActionResult =
        create(RewardSpec(title.trim(), from.cost, from.prompt))

    private suspend fun create(spec: RewardSpec): RewardActionResult = lock.withLock {
        when (val result = api.createReward(spec)) {
            is TwitchResult.Ok -> if (adopt(result.value, managed = true)) RewardActionResult.Ok else RewardActionResult.Failed
            else -> failure(result)
        }
    }

    /** Uses [reward]: managed when it is Requestify's own, otherwise linked without management. */
    fun link(reward: ScannedReward) {
        adopt(reward.reward, reward.manageable)
    }

    /** Writes the given fields to Twitch and adopts the answer. Only for Requestify's own reward. */
    suspend fun edit(title: String?, cost: Int?, prompt: String?): RewardActionResult = lock.withLock {
        val p = points()
        val id = p.rewardId
        if (id == null || !p.managed || p.rewardRemoved) return@withLock RewardActionResult.Failed
        if (title == null && cost == null && prompt == null) return@withLock RewardActionResult.Ok
        when (val result = api.updateReward(id, RewardPatch(title = title?.trim(), cost = cost, prompt = prompt))) {
            is TwitchResult.Ok -> {
                known = Known(id, result.value.isEnabled, result.value.isPaused)
                adoptText(result.value)
                RewardActionResult.Ok
            }
            is TwitchResult.NotFound -> {
                markRemoved(id)
                RewardActionResult.Failed
            }
            else -> failure(result)
        }
    }

    /** The Channel Points switch. Requestify's own reward is enabled or disabled on Twitch, never deleted. */
    suspend fun setEnabled(on: Boolean) {
        if (!settings.tryUpdate { it.withPoints { p -> p.copy(enabled = on) } }) return
        sync()
    }

    /** Pauses Requestify's own reward when the app closes; nothing for a switched-off or a foreign reward. */
    suspend fun pauseForExit() = lock.withLock {
        val p = points()
        val id = p.rewardId
        if (id == null || !p.managed || !p.enabled || p.rewardRemoved) return@withLock
        if (api.updateReward(id, RewardPatch(isPaused = true)) is TwitchResult.Ok) known = null
    }

    /** The exit pause was not followed by an exit (a logout that could not go on): back to what the requests want. */
    suspend fun resumeAfterExitPause() = sync()

    /** Follows the switch, the reward and whether requests are open, and Twitch's own reward events. */
    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch {
                combine(requestsOpen, settings.settings.map { it.twitch.channelPoints.wanted() }) { open, wanted -> open to wanted }
                    .distinctUntilChanged()
                    .collect { sync() }
            },
            scope.launch {
                events.collect { event ->
                    try {
                        when (event) {
                            is EventSubEvent.RewardUpdated -> onUpdated(event.reward)
                            is EventSubEvent.RewardRemoved -> onRemoved(event.rewardId)
                            else -> Unit
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One bad event never stops the next.
                    }
                }
            },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        known = null
        retryGeneration++
        _outOfSync.value = false
    }

    /** What decides whether Twitch has to be told something. */
    private data class Wanted(val id: String?, val managed: Boolean, val enabled: Boolean, val removed: Boolean)

    private fun ChannelPointsSettings.wanted() = Wanted(rewardId, managed, enabled, rewardRemoved)

    private suspend fun sync() = lock.withLock { syncLocked() }


    /** Makes Twitch's enabled and paused flags what the app wants; only for Requestify's own reward. */
    private suspend fun syncLocked() = afterSync(trySync())

    private suspend fun trySync(): Synced {
        val p = points()
        val id = p.rewardId
        if (id == null || !p.managed || p.rewardRemoved) return Synced.Yes
        val current = known?.takeIf { it.id == id }
        val wantPaused = !requestsOpen.value
        val patch = RewardPatch(
            isEnabled = p.enabled.takeIf { current?.enabled != it },
            // A reward that is switched off is hidden: its pause does not matter.
            isPaused = wantPaused.takeIf { p.enabled && current?.paused != it },
        )
        if (patch.isEnabled == null && patch.isPaused == null) return Synced.Yes
        return when (val result = api.updateReward(id, patch)) {
            is TwitchResult.Ok -> {
                known = Known(id, result.value.isEnabled, result.value.isPaused)
                Synced.Yes
            }
            is TwitchResult.NotFound -> {
                markRemoved(id)
                Synced.Yes
            }
            // Logged out: nothing can be sent; the session starting again syncs. A 401 that a refresh did not cure is
            // retried like any failure (a login that is really dead ends the session, which stops this anyway).
            is TwitchResult.LoggedOut -> Synced.LoggedOut
            else -> Synced.Retry
        }
    }

    /** [known] stays as it was on a failure, so the next sync sends the same change again. */
    private fun afterSync(result: Synced) {
        val generation = ++retryGeneration
        _outOfSync.value = result != Synced.Yes
        when (result) {
            Synced.Yes -> retryDelayMs = retryFirstMs
            Synced.LoggedOut -> Unit
            Synced.Retry -> {
                val wait = retryDelayMs
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(retryMaxMs)
                scope.launch {
                    delay(wait)
                    if (generation == retryGeneration) sync()
                }
            }
        }
    }

    private suspend fun onUpdated(reward: Reward) {
        if (reward.id != points().rewardId) return
        adoptText(reward)
        lock.withLock {
            if (reward.id != points().rewardId) return@withLock
            known = Known(reward.id, reward.isEnabled, reward.isPaused)
            syncLocked()
        }
    }

    private fun onRemoved(rewardId: String) {
        if (rewardId != points().rewardId) return
        markRemoved(rewardId)
    }

    private fun markRemoved(id: String) {
        known = null
        settings.tryUpdate { s ->
            if (s.twitch.channelPoints.rewardId == id) s.withPoints { it.copy(rewardRemoved = true) } else s
        }
    }

    /** Saves [reward] as the linked one and tells EventSub; false when it could not be saved. */
    private fun adopt(reward: Reward, managed: Boolean): Boolean {
        val saved = settings.tryUpdate {
            it.withPoints { p ->
                p.copy(
                    enabled = true,
                    rewardId = reward.id,
                    managed = managed,
                    title = reward.title,
                    cost = reward.cost,
                    prompt = reward.prompt,
                    rewardRemoved = false,
                )
            }
        }
        if (!saved) return false
        known = if (managed) Known(reward.id, reward.isEnabled, reward.isPaused) else null
        rewardChanged()
        return true
    }

    private fun adoptText(reward: Reward) {
        settings.tryUpdate { s ->
            val p = s.twitch.channelPoints
            if (p.rewardId != reward.id || (p.title == reward.title && p.cost == reward.cost && p.prompt == reward.prompt)) {
                s
            } else {
                s.withPoints { it.copy(title = reward.title, cost = reward.cost, prompt = reward.prompt) }
            }
        }
    }

    private fun failure(result: TwitchResult<*>): RewardActionResult = when (result) {
        // Twitch says the name is taken in a plain 400; its other 400s (AutoMod, a bad value) are no duplicate.
        is TwitchResult.BadRequest ->
            if (result.message.contains("unique", ignoreCase = true) || result.message.contains("duplicate", ignoreCase = true)) {
                RewardActionResult.DuplicateTitle
            } else {
                RewardActionResult.Failed
            }
        is TwitchResult.Forbidden -> RewardActionResult.NotAffiliate
        else -> RewardActionResult.Failed
    }

    /**
     * A string in the app language. [AppLanguage] decides the JVM default locale at start (Main), so this is normally
     * just [getString]; only when the setting says otherwise (changed since start) the default is switched for the call.
     */
    private suspend fun text(resource: StringResource): String {
        val target = when (language()) {
            AppLanguage.System -> null
            AppLanguage.English -> Locale.ENGLISH
            AppLanguage.Dutch -> Locale.forLanguageTag("nl-NL")
        }
        if (target == null || target.language == Locale.getDefault().language) return getString(resource)
        return localeSwitch.withLock {
            val previous = Locale.getDefault()
            Locale.setDefault(target)
            try {
                getString(resource)
            } finally {
                Locale.setDefault(previous)
            }
        }
    }
}

private val localeSwitch = Mutex()

private fun nl.diments.requestify.core.settings.Settings.withPoints(change: (ChannelPointsSettings) -> ChannelPointsSettings) =
    copy(twitch = twitch.copy(channelPoints = change(twitch.channelPoints)))
