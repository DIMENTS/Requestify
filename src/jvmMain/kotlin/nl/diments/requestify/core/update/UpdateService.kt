package nl.diments.requestify.core.update

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.diments.requestify.core.stream.GateStatus

/** What Settings → Over Requestify shows about updates. */
sealed interface UpdateState {
    /** This build does not update itself (`gradlew run`, or a local build). */
    data object Off : UpdateState

    /** Not checked yet in this run. */
    data object Idle : UpdateState

    data class Checking(val manual: Boolean) : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState

    /** Downloaded and verified: installs at a quiet start, or on "Nu bijwerken". */
    data class Ready(val version: AppVersion, val notes: String, val pageUrl: String) : UpdateState

    /** GitHub could not be asked. Only a check by hand says so; an automatic one shows as "not checked". */
    data class CheckFailed(val manual: Boolean) : UpdateState

    /** Installing [version] failed twice: only a download by hand from [pageUrl] now. */
    data class InstallFailed(val version: AppVersion, val pageUrl: String) : UpdateState

    /** [version] was found while live: it downloads once the stream is offline (never ~90 MB during a stream). */
    data class WaitingForOffline(val version: AppVersion) : UpdateState
}

/** For the Log: what happened to an update. Never a notification. */
sealed interface UpdateEvent {
    data class Updated(val from: AppVersion, val to: AppVersion) : UpdateEvent
    data class InstallFailed(val version: AppVersion, val exitCode: Int?) : UpdateEvent
    data class DownloadFailed(val version: AppVersion, val reason: String) : UpdateEvent
}

/**
 * Keeps Requestify up to date from GitHub Releases, quietly. Checks [firstCheckDelayMs] after [start] (not while
 * everything connects), then every [everyMs], and on [checkNow]; a newer release is downloaded and verified
 * ([download]) and then waits ([UpdateState.Ready]). Installing is not done here: a start installs it when
 * [installAtStart] allows ([prepareInstallAtStart]), or the streamer asks ([installNow], only when [canInstallNow]),
 * which [installRequests] hands to the app's shell.
 *
 * At construction it settles what the last run left ([store]): an install that worked becomes [UpdateEvent.Updated]
 * and the "Bijgewerkt" note ([justUpdated]); one that failed becomes [UpdateEvent.InstallFailed], tried once more, and
 * after two attempts only by hand. [events] replays these for a Log that subscribes after construction.
 */
class UpdateService(
    private val running: AppVersion?,
    private val latestRelease: suspend () -> ReleaseCheck,
    private val download: suspend (Release) -> DownloadResult,
    private val store: UpdateStore,
    private val gate: StateFlow<GateStatus>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val firstCheckDelayMs: Long = 120_000,
    private val everyMs: Long = 21_600_000,
    private val maxAttempts: Int = 2,
) {
    private val on = running != null && !running.isLocalBuild
    private val _state = MutableStateFlow<UpdateState>(if (on) UpdateState.Idle else UpdateState.Off)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UpdateEvent>(replay = 8, extraBufferCapacity = 8)
    val events: SharedFlow<UpdateEvent> = _events.asSharedFlow()

    private val _justUpdated = MutableStateFlow<JustUpdated?>(null)
    val justUpdated: StateFlow<JustUpdated?> = _justUpdated.asStateFlow()

    val canInstallNow: StateFlow<Boolean> = combine(_state, gate) { s, g -> s is UpdateState.Ready && canInstallNow(g) }
        .stateIn(scope, SharingStarted.Eagerly, _state.value is UpdateState.Ready && canInstallNow(gate.value))

    private val _installRequests = MutableSharedFlow<PendingUpdate>(extraBufferCapacity = 1)
    val installRequests: SharedFlow<PendingUpdate> = _installRequests.asSharedFlow()

    /** A check asked for by hand failed while an update is ready (or failed): it says so next to it (F-2). */
    private val _checkFailedNote = MutableStateFlow(false)
    val checkFailedNote: StateFlow<Boolean> = _checkFailedNote.asStateFlow()

    /** "Nu bijwerken" asked once this run: a double click is no second attempt (F-3). */
    private val installRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The failed install this start found (also in [events]): a start that installs again at once writes it to the Log
     * itself, as its Log never starts (F-1).
     */
    var startFailure: UpdateEvent.InstallFailed? = null
        private set

    private val checking = Mutex()

    init {
        if (on) reconcile(running!!)
    }

    /** Settles what the last run left: an install that worked, one that failed, or an update still waiting. */
    private fun reconcile(running: AppVersion) {
        _justUpdated.value = store.justUpdated()
        val pending = store.pending() ?: return
        val target = AppVersion.parse(pending.version)
        if (target == null) {
            store.clearPending()
            return
        }
        if (running >= target) {
            // Installed: by us (attempts > 0), or by hand (also a newer version than ours). Only ours is reported, and only
            // for the version we installed: a newer one installed by hand is not announced with our notes.
            if (pending.attempts > 0 && running == target) {
                val from = pending.from?.let(AppVersion::parse) ?: running
                _events.tryEmit(UpdateEvent.Updated(from, running))
                JustUpdated(from.toString(), running.toString(), pending.notes).also { store.saveJustUpdated(it); _justUpdated.value = it }
            }
            store.clearPending()
            store.clearExitCode()
            return
        }
        if (pending.attempts > 0) {
            val failure = UpdateEvent.InstallFailed(target, store.lastExitCode())
            startFailure = failure
            _events.tryEmit(failure)
            store.clearExitCode()
        }
        _state.value = if (pending.attempts >= maxAttempts) {
            UpdateState.InstallFailed(target, pending.pageUrl)
        } else {
            UpdateState.Ready(target, pending.notes, pending.pageUrl)
        }
    }

    fun start() {
        if (!on) return
        scope.launch {
            delay(firstCheckDelayMs)
            while (true) {
                check(manual = false)
                delay(everyMs)
            }
        }
        // A release found while live downloads as soon as the stream is offline.
        scope.launch {
            gate.map { it.live }.distinctUntilChanged().collect { live ->
                if (!live && _state.value is UpdateState.WaitingForOffline) check(manual = false)
            }
        }
    }

    fun checkNow() {
        if (!on) return
        scope.launch { check(manual = true) }
    }

    /**
     * One check. Nothing escapes it: an error here would reach the app's crash guard and restart Requestify, maybe
     * during a stream; it counts as a failed check instead.
     */
    private suspend fun check(manual: Boolean) = checking.withLock {
        val before = _state.value
        _checkFailedNote.value = false
        try {
            checkOnce(manual, before)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed(manual, before)
        }
    }

    /**
     * A check that failed: an update that is ready (or failed) stays, with a note when the check was asked for by
     * hand; otherwise a check by hand says it failed, and an automatic one stays quiet.
     */
    private fun failed(manual: Boolean, before: UpdateState) {
        val kept = settled(before)
        _state.value = kept ?: UpdateState.CheckFailed(manual)
        if (kept != null && manual) _checkFailedNote.value = true
    }

    private suspend fun checkOnce(manual: Boolean, before: UpdateState) {
        if (manual) _state.value = UpdateState.Checking(manual = true)
        when (val answer = latestRelease()) {
            is ReleaseCheck.Failed -> failed(manual, before)
            ReleaseCheck.NoneUsable -> _state.value = settled(before) ?: UpdateState.UpToDate(clock())
            is ReleaseCheck.Found -> onRelease(answer.release, before, manual)
        }
    }

    /** A Ready or InstallFailed state survives a check that found nothing newer. */
    private fun settled(state: UpdateState): UpdateState? = (state as? UpdateState.Ready) ?: (state as? UpdateState.InstallFailed)

    private suspend fun onRelease(release: Release, before: UpdateState, manual: Boolean) {
        val running = running ?: return
        if (release.version <= running) {
            // Nothing newer is published (any more): an update downloaded earlier was pulled, or made a pre-release.
            // Lean towards not installing it.
            if (store.pending() != null) store.clearPending()
            _state.value = UpdateState.UpToDate(clock())
            return
        }
        if (before is UpdateState.InstallFailed && before.version == release.version) {
            _state.value = before // two attempts failed: by hand only
            return
        }
        // Never ~90 MB during a stream: it downloads once the stream is offline.
        if (gate.value.live) {
            _state.value = UpdateState.WaitingForOffline(release.version)
            return
        }
        // A download of this version failed less than a day ago (a bad checksum, say): not fetched again yet.
        val failure = store.failedDownload()?.takeIf { it.version == release.version.toString() }
        if (failure != null && clock() - failure.atEpochMs < DOWNLOAD_RETRY_MS) {
            _state.value = settled(before) ?: UpdateState.CheckFailed(manual)
            return
        }
        when (val result = download(release)) {
            is DownloadResult.Ready -> {
                store.clearFailedDownload()
                _state.value = UpdateState.Ready(release.version, release.notes, release.pageUrl)
            }
            is DownloadResult.Failed -> {
                // One Log row per version, also across runs: a retry of the same version that fails again is quiet.
                if (failure == null) _events.tryEmit(UpdateEvent.DownloadFailed(release.version, result.reason))
                store.saveFailedDownload(FailedDownload(release.version.toString(), clock()))
                _state.value = settled(before) ?: UpdateState.CheckFailed(manual)
            }
        }
    }

    /** "Nu bijwerken": only while offline with requests closed and an update ready; the shell does the rest. */
    fun installNow() {
        if (_state.value !is UpdateState.Ready || !canInstallNow(gate.value)) return
        if (!installRequested.compareAndSet(false, true)) return // asked already: one attempt per run
        val pending = prepareInstallAtStart()
        if (pending == null) {
            installRequested.set(false)
            return
        }
        _installRequests.tryEmit(pending)
    }

    /** The shell could not start the install: "Nu bijwerken" may be asked again. */
    fun installRequestFailed() = installRequested.set(false)

    /** Counts the attempt (and which version starts it) before the install; null when nothing is pending. */
    fun prepareInstallAtStart(): PendingUpdate? = store.markAttempt(from = running?.toString())

    fun dismissJustUpdated() {
        store.clearJustUpdated()
        _justUpdated.value = null
    }
}

/** A version whose download failed is fetched again after this long (F-5), unless a newer release appears. */
private const val DOWNLOAD_RETRY_MS = 86_400_000L
