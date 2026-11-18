# Requestify plan 4 — running in the background — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Requestify lives in the system tray, starts with Windows, runs once, restarts itself after a crash, notifies only when the streamer must act, never blocks the window on disk I/O, and the parked plan-1/2/3 follow-ups are fixed.

**Architecture:** One `BackgroundWriter` gives every file the queue's model (apply at once, save off the UI thread, retry, report); a `SaveHealth` collects their problems for one banner, the Log, the tray and a notification. A new `desktop/` package holds the shell around the existing `AppGraph`: single instance, launch mode and installation, AWT tray, notifier, Run-key autostart and crash guard; `Main.kt` wires them. The tray and notifier *listen* to one `AppGraph.problems` flow (the chip's list, moved to core), as the Log listens in plan 3.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Material 3 Expressive), JetBrains Runtime 21, kotlinx coroutines + serialization, JNA 5.19.1 platform (registry, DPAPI, message box), AWT `SystemTray`/`TrayIcon`, Skia (WebP decode), JUnit/kotlin-test, coroutines-test virtual time, `runComposeUiTest`.

**Spec:** `docs/superpowers/specs/2026-11-18-requestify-plan-4-background-design.md` (parents: `2026-11-16-requestify-design.md`, `2026-11-17-requestify-plan-2-rules-twitch-design.md`, `2026-11-18-requestify-plan-3-donations-log-design.md`).

## Global Constraints

- Paths below are relative to `src/jvmMain/kotlin/nl/diments/requestify/` (main) and `src/jvmTest/kotlin/nl/diments/requestify/` (tests) unless they start with `src/` or `docs/`.
- Every user-facing string exists in both `src/commonMain/composeResources/values/strings.xml` and `values-nl/strings.xml` with identical keys and placeholders (`StringsTest`). Dutch: "systeemvak", "Koppelingen", "Logboek", "Afsluiten", "Openen".
- Persisted names are stable forever: every new `@SerialName` and every new persisted field gets a pin in `core/storage/SavedFormatCompatTest.kt`; new fields have defaults (old files must read).
- Never write a default over a file that could not be read (plan 1's rule). A change to such a store is refused until a read succeeds.
- Nothing on the UI thread (`Dispatchers.Main`) writes a file, fsyncs, or calls DPAPI. Reads at construction in `main()` (before the window) are allowed.
- A donation's cursor reaches disk only after the requests it covers have reached disk.
- Notifications only for: Spotify login needed, Spotify Premium missing, Twitch login needed, donation key/token refused, DonoLink key ≤ 7 days and ≤ 1 day before expiry (once each per key), Twitch reward removed, saving failing for 2 minutes, restarted after a crash. Never for anything else.
- Crash restart: once; a second crash within 10 minutes of a crash-restart stops the app with a message; the newest 10 crash files are kept.
- Start arguments: `--autostart` (started by Windows), `--after-crash` (crash restart). Both start hidden in the tray.
- Autostart: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, value `Requestify` = `"<launcher exe>" --autostart`; Task Manager's state in `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run`. Only when installed (`jpackage.app-path` points at an existing file). Never re-enabled after the streamer turned it off.
- The icon source is `src/jvmMain/resources/icon/requestify-icon.webp` (the spec's `assets/plan-4-icon/requestify-icon-1024.webp`); the title-bar glyph is the spec's `music-notes-plus.svg` path. Phosphor Icons (MIT) is credited in `NOTICE`.
- Packaging-dependent pieces (installed?, launcher path, autostart, relaunch) sit behind `Installation` and `Autostart` only, so plan 5 can swap them.
- Never drive the user's desktop with scripted clicks; UI checks are offscreen renders (`renders/` in the SDD workspace); the tray, notifications, autostart and crash restart are checked by the user in a locally installed build (Task 21's checklist).

## Review Focus

1. **The install path has spaces** (`C:\Program Files\Requestify\Requestify.exe`): the Run entry and the crash relaunch must quote it, or Windows starts nothing. (Task 12 `theRunEntryQuotesThePathAndMarksAutostart`, Task 13 `theRelaunchPassesThePathAsOneArgument`.)
2. **Two starts at once** (Windows' autostart and a double-click at sign-in, or a crash restart while the old process is still exiting): exactly one instance; the second's "show" is not lost when it arrives before the window exists. (Task 8 `twoStartsAtOnceGiveOneFirst`, `afterCrashWaitsForTheOldLock`; Task 15 `aShowRequestBeforeTheWindowIsKept`.)
3. **Windows shuts down or the user logs off** with Requestify hidden: not logged as a crash, no restart. (Task 13 `theShutdownHookEndsOrderly`; Task 21 manual check.)
4. **A disk that stays full** while the streamer quits: Quit waits at most 3 s, never hangs, and the Log/banner said so beforehand. (Task 6 `quitGivesUpOnAFailingDiskWithinTheBudget`.)
5. **The PC sleeps for days** with a DonoLink key counting down: after waking, the chip, card and notifications show the right day count, and each expiry step is notified once, not in a burst. (Task 11 `aLongSleepSendsEachExpiryStepOnce`, Task 10 `theProblemsFollowTheClock`.)

---

## Phase A — saving off the UI thread

### Task 1: BackgroundWriter

**Files:**
- Create: `core/storage/BackgroundWriter.kt`
- Test: `core/storage/BackgroundWriterTest.kt`

**Interfaces:**
- Produces:
  - `data class SaveProblem(val file: String, val reason: String, val sinceEpochMs: Long)`
  - `class SaveContext(val scope: CoroutineScope, val clock: () -> Long, val io: CoroutineDispatcher = Dispatchers.IO)`
  - `class BackgroundWriter<T>(val fileName: String, private val write: (T) -> Unit, private val context: SaveContext, private val backoffMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000))` with
    - `fun save(value: T)` — never blocks or suspends; the newest value wins (values given while a write runs are coalesced into one next write)
    - `val problem: StateFlow<SaveProblem?>` — set on the first failed write (`sinceEpochMs` = that moment, `reason` = the exception's message), kept while retrying (then the last backoff value repeats), cleared after a successful write
    - `suspend fun awaitSaved()` — returns once every value given to `save` before the call is written
    - `suspend fun flush(timeoutMs: Long): Boolean` — like `awaitSaved` with a deadline; true when everything was written in time

- [ ] **Step 1: Failing tests** (virtual time; `io` = a `StandardTestDispatcher` of the test scheduler): `saveReturnsBeforeTheWrite` (the write lambda has not run when `save` returns); `quickChangesAreCoalescedIntoTheNewest` (save 1,2,3 while write of 1 blocks → writes `[1, 3]`); `anOlderValueIsNeverWrittenAfterANewer`; `aFailedWriteIsRetriedWithBackoffAndReported` (fail twice: `problem` = `SaveProblem("x.json", reason, t0)` after the first failure, retries at +1 s and +2 s, `null` after the success); `awaitSavedWaitsForTheWriteBeforeIt`; `flushGivesUpAfterTheTimeout` (always failing → `false` after `timeoutMs`, no exception).
- [ ] **Step 2:** `./gradlew jvmTest --tests "*BackgroundWriterTest"` → FAIL (unresolved).
- [ ] **Step 3:** Implement with one writer coroutine per instance on `context.scope` + `context.io` (a conflated signal plus a version counter: `awaitSaved` waits for `writtenVersion >= versionAtCall`).
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat: a background writer that saves the newest value and reports failures`.

### Task 2: PersistedState and SettingsStore apply at once

**Files:**
- Modify: `core/storage/PersistedState.kt`, `core/storage/JsonFileStore.kt` (corrupt-copy dedupe), `core/settings/Settings.kt` (`SettingsStore`), `core/blacklist/BlacklistStore.kt`, `core/intake/RejectedStore.kt`, `core/intake/PendingResolveStore.kt`, `core/donations/DonationCursorStore.kt`, `AppGraph.kt` (construction), every test that builds these stores
- Test: `core/storage/PersistedStateTest.kt` (create), `core/storage/JsonFileStoreTest.kt`, `core/settings/SettingsStoreTest.kt` (create or extend)

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `class PersistedState<T : Any>(file: JsonFileStore<T>, fileName: String, context: SaveContext, rereadEveryMs: Long = 5_000, default: () -> T)` with `value: StateFlow<T>`, `loadFailed: StateFlow<Boolean>`, `saveProblem: StateFlow<SaveProblem?>`, `fun change(f: (T) -> T): Boolean` (applies in memory and hands the result to the writer; **false only while `loadFailed`**, value unchanged), `suspend fun awaitSaved()`, `suspend fun flush(timeoutMs: Long): Boolean`. While `loadFailed`, it re-reads the file every `rereadEveryMs` on `context.io`; the first successful read replaces the default and clears `loadFailed`.
  - Stores take a `SaveContext`: `BlacklistStore(file, context)`, `RejectedStore(file, context)`, `PendingResolveStore(file, context)`, `DonationCursorStore(file, context)`; each exposes `saveProblem`, `awaitSaved()`, `flush(timeoutMs)`.
  - `SettingsStore(file: JsonFileStore<Settings>, context: SaveContext)` built on `PersistedState<Settings>`: `settings`, `loadFailed`, `saveProblem`, `awaitSaved()`, `flush()`; `tryUpdate(f): Boolean` false only while unreadable; `update(f)` throws `StoreReadException` while unreadable.

- [ ] **Step 1: Failing tests.** `PersistedStateTest.aChangeIsVisibleBeforeItIsWritten`; `aFailingWriteKeepsTheChangeAndReportsIt`; `anUnreadableFileRefusesChangesAndIsNeverOverwritten` (file held unreadable → `change` false, file bytes unchanged); `anUnreadableFileIsReadAgainInTheBackground` (becomes readable → `value` = saved content, `loadFailed` false, no write of the default ever happened); `SettingsStoreTest.tryUpdateIsFalseOnlyWhileUnreadable`; `JsonFileStoreTest.aHeldCorruptFileIsCopiedOnlyOnce` (corrupt file that cannot be moved, read twice → exactly one `.corrupt-*` copy).
- [ ] **Step 2:** Run the three classes → FAIL.
- [ ] **Step 3:** Implement. `JsonFileStore.quarantined`: before copying, skip when a sibling `<name>.corrupt-*` with identical bytes exists. `AppGraph` builds one `SaveContext(scope, System::currentTimeMillis)` and passes it. Callers that branched on a false `change`/`tryUpdate` keep their code (it now means "unreadable"); `RequestIntake.saveRejectionLater` stays (it covers the unreadable case).
- [ ] **Step 4:** Run the three classes, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: settings and stores apply at once and save in the background`.

### Task 3: The gate and the redemption outbox save in the background

**Files:**
- Modify: `core/stream/RequestsGate.kt`, `core/twitch/points/RedemptionOutbox.kt`, `AppGraph.kt`
- Test: `core/stream/RequestsGateTest.kt`, `core/twitch/points/RedemptionOutboxTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: both take a `SaveContext` and expose `saveProblem: StateFlow<SaveProblem?>`, `awaitSaved()`, `flush(timeoutMs)`. Their writes go through a `BackgroundWriter` (their own `save`/`saveLater` retry loops go); their merge-on-reload logic for a file unreadable at start stays as it is.

- [ ] **Step 1: Failing tests.** `RequestsGateTest.aChangeDoesNotWaitForTheFile` (io paused: the change and its `GateEvent` happen, `stream.json` not yet written; after advancing, it is); `RequestsGateTest.theHeartbeatWritesOffTheCallerThread`; `RedemptionOutboxTest.anEntryIsKeptWhileItsFileCannotBeWritten` (reported in `saveProblem`, written once the file works).
- [ ] **Step 2:** Run both classes → FAIL. **Step 3:** Implement. **Step 4:** Both classes, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: the gate and the redemption outbox save in the background`.

### Task 4: Secrets in memory, DPAPI off the UI thread

**Files:**
- Modify: `core/secrets/SecretStore.kt`, `Main.kt` (construction), `AppGraph.kt`
- Test: `core/secrets/DpapiSecretStoreTest.kt` (create or extend)

**Interfaces:**
- Consumes: Task 1.
- Produces: `class DpapiSecretStore(file: Path, context: SaveContext, protect: (ByteArray) -> ByteArray = Crypt32Util::cryptProtectData, unprotect: (ByteArray) -> ByteArray = Crypt32Util::cryptUnprotectData) : SecretStore` — reads and decrypts every value once in its constructor (in `main()`, before the window); `get` answers from memory; `put`/`remove` change memory at once and hand the map to a `BackgroundWriter` whose write encrypts on `context.io`; `put`/`remove` throw `StoreReadException` while the file was unreadable at start (re-read in the background as `PersistedState` does); exposes `saveProblem`, `awaitSaved()`, `flush()`.

- [ ] **Step 1: Failing tests.** `DpapiSecretStoreTest.getNeverDecryptsAgain` (counting `unprotect`: N gets after start → still the start count); `putIsVisibleAtOnceAndEncryptedOffTheCaller` (`protect` not called on the caller; file holds no plain text); `aRestartReadsWhatWasPut`; `anUnreadableFileRefusesPutAndIsNeverOverwritten`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: secrets are decrypted once and saved off the UI thread`.

### Task 5: A donation's cursor is saved after its request

**Files:**
- Modify: `core/intake/RequestIntake.kt`, `core/playback/PlaybackController.kt` (only if `enqueue` saves fire-and-forget), `core/donations/DonationHandler.kt`
- Test: `core/donations/DonationHandlerTest.kt`, `core/donations/donolink/DonoLinkSourceTest.kt`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces: `suspend fun RequestIntake.awaitSaved()` — returns once the queue, Rejected and pending state as of the call are on disk (awaits `rejected.awaitSaved()`, `pending.awaitSaved()` and, if needed, a new `suspend fun PlaybackController.awaitSaved()`). `DonationHandler.handle` calls it before returning, so both sources save their cursor only after it.

- [ ] **Step 1: Failing tests.** `DonationHandlerTest.handleReturnsOnlyAfterTheRejectionIsOnDisk` (io paused → `handle` does not return until `rejected.json` holds the entry); `DonoLinkSourceTest.theCursorIsSavedOnlyAfterTheRequest` (temp files: whenever `donations.json` holds a cursor past a donation, `queue.json` or `rejected.json` already holds that donation).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. Check the redemption outbox vs the queue the same way (a FULFILLED entry must not be written before the hand-over it marks); fix it if it can be. **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `fix: a donation's cursor never reaches disk before its request`.

### Task 6: One save health — banner, Log, quit flush

**Files:**
- Create: `core/storage/SaveHealth.kt`
- Modify: `AppGraph.kt` (build `saveHealth`; `stopGracefully` flushes), `core/log/LogRecords.kt` (`LogService.Files`), `core/log/Logbook.kt` (input), `ui/AppScaffold.kt` (global banner), `ui/home/QueuePane.kt` + `ui/home/HomeViewModel.kt` (drop the queue-only "can't save" banner), both `strings.xml`
- Test: `core/storage/SaveHealthTest.kt`, `core/log/LogbookTest.kt`, `AppGraphTest.kt`, `ui/AppScaffoldTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4, `PlaybackController.persistError: StateFlow<String?>`.
- Produces:
  - `class SaveHealth(problems: List<StateFlow<SaveProblem?>>, queueError: StateFlow<String?>, scope: CoroutineScope, clock: () -> Long)` with `val problems: StateFlow<List<SaveProblem>>` (the queue's error becomes `SaveProblem("queue.json", it, since)`)
  - `AppGraph.saveHealth: SaveHealth`; `AppGraph.flushAll(timeoutMs: Long): Boolean` (every writer, in parallel)
  - `LogService.Files` `@SerialName("files")`: one `ConnectionChanged` outage row while `problems` is non-empty (`detail` = file names and reason), "Restored" when it empties
  - `stopGracefully()` runs the reward pause and `flushAll` in parallel, both within `EXIT_PAUSE_TIMEOUT_MS` (3 s)
  - String `save_problem` = "Kan niet opslaan: %1$s — %2$s" / "Can't save: %1$s — %2$s"

- [ ] **Step 1: Failing tests.** `SaveHealthTest.problemsOfEveryFileAreCollected`; `LogbookTest.aSaveOutageIsOneRowUntilItWorks`; `AppGraphTest.quitFlushesAChangeMadeJustBefore`; `AppGraphTest.quitGivesUpOnAFailingDiskWithinTheBudget` (always-failing writer → `stopGracefully` returns within 3 s of virtual time); `AppScaffoldTest.aSaveProblemShowsOneBanner`; `SavedFormatCompatTest.filesServiceNameIsPinned`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. Audit the UI thread for remaining file, fsync or DPAPI calls (grep `Files.`, `JsonFileStore`, `Crypt32Util`, `.write(` reached from `Dispatchers.Main` code) and move any left. **Step 4:** Run, then the whole suite → PASS; render the app with the save banner (wide + narrow, dark + light) for the user.
- [ ] **Step 5:** Commit `feat: one save problem banner, a Log row, and quit waits for pending saves`.

### Task 7: Home's banners leave the queue visible in the narrow layout

**Files:**
- Modify: `ui/common/ProblemBanner.kt`, `ui/home/QueuePane.kt`
- Test: `ui/home/QueuePaneTest.kt`

**Interfaces:**
- Produces: `ProblemBanner(text: String, modifier: Modifier = Modifier, compact: Boolean = false)` — compact: one line with ellipsis, a click expands it.

- [ ] **Step 1: Failing test.** `QueuePaneTest.bannersLeaveARowVisibleInTheNarrowLayout` (window at `MinimumWindowSize` 720×640, narrow layout, two banners → at least one queue row is displayed).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (QueuePane passes `compact = true` in the narrow layout). **Step 4:** Run → PASS; render Home narrow with two banners (dark + light) for the user.
- [ ] **Step 5:** Commit `fix: problem banners no longer squeeze the narrow queue`.

## Phase B — the desktop shell

### Task 8: One instance, launch mode, installation

**Files:**
- Create: `desktop/SingleInstance.kt`, `desktop/Launch.kt`
- Test: `desktop/SingleInstanceTest.kt`, `desktop/LaunchTest.kt`

**Interfaces:**
- Produces:
  - `enum class LaunchMode { Normal, Autostart, AfterCrash }`; `fun launchMode(args: Array<String>): LaunchMode` (`--autostart`, `--after-crash`; anything else Normal)
  - `data class Installation(val launcherExe: Path?) { val isInstalled: Boolean }` with `companion fun current(): Installation` (`jpackage.app-path` when that file exists)
  - `sealed interface InstanceStart { class First(val instance: RunningInstance) : InstanceStart; data object AlreadyRunning : InstanceStart }`
  - `class SingleInstance(dir: Path)` with `fun start(onShowRequested: () -> Unit, askToShow: Boolean = true, waitForLockMs: Long = 0): InstanceStart` — holds an OS lock on `instance.lock` (an `OverlappingFileLockException` counts as held); the first serves a loopback socket on `127.0.0.1`, writing `"<port> <random token>"` to `instance.port`; a later start sends `show <token>` and gets `ok` (then returns `AlreadyRunning`); `askToShow = false` (an autostart) returns `AlreadyRunning` without asking; `waitForLockMs > 0` retries the lock until it is free instead of asking (crash restart)
  - `class RunningInstance : AutoCloseable` (releases the lock, closes the socket, deletes `instance.port`)

- [ ] **Step 1: Failing tests.** `LaunchTest.argumentsGiveTheMode`; `LaunchTest.notInstalledWithoutJpackagePath`; `SingleInstanceTest.aSecondStartAsksTheFirstToShow` (onShow called once, second gets `AlreadyRunning`); `twoStartsAtOnceGiveOneFirst` (two threads → exactly one `First`); `anAutostartDoesNotAskToShow`; `aStalePortFileDoesNotBlock` (port file without lock holder → `First`); `aWrongTokenIsIgnored`; `afterCrashWaitsForTheOldLock` (lock held, released after 300 ms, `waitForLockMs = 5_000` → `First`, onShow of the old one never called).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: one Requestify at a time, and how it was started`.

### Task 9: The icon and the title-bar glyph

**Files:**
- Create: `src/jvmMain/resources/icon/requestify-icon.webp` (copy of the spec asset), `desktop/AppIconImages.kt`
- Modify: `ui/common/Icons.kt` (`AppIcons.RequestifyGlyph`), `ui/window/TitleBar.kt` (`AppMark` uses the glyph), `NOTICE`
- Test: `desktop/AppIconImagesTest.kt`, `ui/window/TitleBarTest.kt`

**Interfaces:**
- Produces:
  - `object AppIconImages { fun awt(sizePx: Int, warning: Boolean = false): BufferedImage; fun windowIcons(): List<BufferedImage> }` — decodes the WebP once with Skia (`org.jetbrains.skia.Image.makeFromEncoded`), downscales with a high-quality sampling mode, caches per size; `windowIcons()` = sizes 16, 20, 24, 32, 40, 48, 64, 256. `warning = true` draws an amber dot with a dark ring in the bottom-right quarter (≈ 45 % of the size). If Skia cannot decode WebP, convert the asset once to PNG with the same test utility and load the PNG instead (one source file either way).
  - `AppIcons.RequestifyGlyph: ImageVector` — 24 dp, viewport 256, the SVG's path data (`addPathNodes`), solid fill (tinted by `Icon`).
  - `AppMark` shows the glyph instead of `AppIcons.QueueMusic`, keeping its `primaryContainer` square, so it follows the album colours.
  - `NOTICE`: "Phosphor Icons — the glyph path in `ui/common/Icons.kt` (`RequestifyGlyph`) comes from Phosphor Icons (https://github.com/phosphor-icons/core), Copyright (c) 2023 Phosphor Icons, MIT License."

- [ ] **Step 1: Failing tests.** `AppIconImagesTest.theSourceDecodes`; `everySizeIsSquareAndTheRightSize`; `theWarningMarkChangesOnlyTheBottomRight` (pixels outside the bottom-right quarter equal the plain image); `TitleBarTest.theMarkShowsTheGlyph`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; render the tray images at 16/20/24/32 px with and without the mark (scaled up 8× for viewing) and the title bar under two album colours and a custom colour, for the user.
- [ ] **Step 5:** Commit `feat: the Requestify icon and its title-bar glyph`.

### Task 10: The problem list in core, following the clock

**Files:**
- Create: `core/health/ConnectionProblems.kt` (moved from `ui/common/ConnectionChip.kt`)
- Modify: `ui/common/ConnectionChip.kt` (keeps only the look), `ui/AppScaffold.kt` (uses `graph.problems`), `AppGraph.kt`
- Test: `core/health/ConnectionProblemsTest.kt` (move the logic cases from `ConnectionChipTest`), `AppGraphTest.kt`

**Interfaces:**
- Produces:
  - `ConnectionProblem`, `DonationProblem`, `TwitchHealth`, `connectionProblems(spotify, twitch, donations, nowMs)` in `core.health`, unchanged except `ConnectionProblem.Donation(kind, problem, keyExpiresAtEpochMs: Long? = null)` (set for `KeyExpiring`)
  - `fun clockTicks(clock: () -> Long, zone: ZoneId, everyMs: Long = 3_600_000): Flow<Long>` — emits now, every hour and at each local midnight
  - `AppGraph.problems: StateFlow<List<ConnectionProblem>>` = `connectionProblems` over Spotify, Twitch and donation health and `clockTicks`

- [ ] **Step 1: Failing tests.** `ConnectionProblemsTest` (the moved cases pass unchanged); `ClockTicksTest.ticksHourlyAndAtMidnight`; `AppGraphTest.theProblemsFollowTheClock` (a key expiring in 8 days: no problem; advance 1 day without any health change → `KeyExpiring` with its expiry).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `refactor: the problem list lives in core and follows the clock`.

### Task 11: The notifier

**Files:**
- Create: `desktop/Notifier.kt`
- Modify: `core/settings/Settings.kt` (`KeyExpiryNotice`), both `strings.xml`
- Test: `desktop/NotifierTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Task 10 `problems`, Task 6 `saveHealth.problems`, Task 2 `SettingsStore`.
- Produces:
  - `sealed interface Notice { val target: NoticeTarget }` with `SpotifyLoginNeeded`, `SpotifyPremiumMissing`, `TwitchLoginNeeded`, `KeyRefused(kind: DonationSourceKind)`, `KeyExpiring(daysLeft: Int)`, `RewardRemoved`, `SavingFails(files: List<String>)` (target Connections, except `SavingFails` → Home), `RestartedAfterCrash`, `CloseHint` (target Home); `enum class NoticeTarget { Home, Connections }`
  - `@Serializable data class KeyExpiryNotice(val expiresAtEpochMs: Long, val stage: Int)` (`stage` 7 or 1); `Settings.keyExpiryNotified: KeyExpiryNotice? = null`
  - `class Notifier(problems: StateFlow<List<ConnectionProblem>>, saveProblems: StateFlow<List<SaveProblem>>, settings: SettingsStore, scope: CoroutineScope, clock: () -> Long, send: (Notice) -> Unit, savingNoticeAfterMs: Long = 120_000)` with `fun start()`
  - `daysLeft` = whole days until expiry, rounded down (0 = today, 1 = tomorrow); stage 7 when ≤ 7 days, stage 1 when ≤ 24 h; a key with another expiry starts over
  - `suspend fun noticeText(n: Notice): Pair<String, String>` (title, text) in the app language

- [ ] **Step 1: Failing tests.** `aProblemPresentAtStartIsNotifiedOnce`; `aProblemIsNotifiedAgainOnlyAfterItWentAway`; `deliberateAndSelfHealingStatesAreNeverNotified` (LoggedOut, NoKey, Disconnected, NoActiveDevice, Unreachable, RateLimited, Twitch disconnected); `expiryNotifiesAtSevenDaysAndOnTheLastDay`; `expiryStepsSurviveARestart` (new Notifier on the same settings → nothing); `aNewKeyStartsTheExpiryStepsOver`; `aLongSleepSendsEachExpiryStepOnce` (jump from 10 days to 12 h left → only stage 1); `aSaveOutageNotifiesAfterTwoMinutesOnly`; `aShortSaveOutageIsSilent`; `SavedFormatCompatTest.keyExpiryNoticeIsPinned`; `noticeTextsExistInBothLanguages`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: notifications only when the streamer must act`.

### Task 12: Start with Windows

**Files:**
- Create: `desktop/Autostart.kt`
- Modify: `core/settings/Settings.kt` (`startWithWindows`)
- Test: `desktop/AutostartTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Task 8 `Installation`.
- Produces:
  - `enum class AutostartState { On, Off, DisabledInTaskManager, Unavailable }`
  - `interface Autostart { fun state(): AutostartState; fun enable(); fun disable() }`
  - `interface RegistryPort { fun getString(key: String, name: String): String?; fun setString(key: String, name: String, value: String); fun delete(key: String, name: String); fun getBinary(key: String, name: String): ByteArray?; fun setBinary(key: String, name: String, value: ByteArray) }` + `JnaRegistry : RegistryPort` (HKCU via `Advapi32Util`; not unit-tested)
  - `class RunKeyAutostart(registry: RegistryPort, installation: Installation) : Autostart` — command `"<exe>" --autostart`; `DisabledInTaskManager` when the StartupApproved value's first byte is odd; `enable()` writes the Run value and StartupApproved `02 00 00 00 00 00 00 00 00 00 00 00`; `disable()` deletes the Run value; all no-ops and `Unavailable` when not installed
  - `Settings.startWithWindows: Boolean? = null` (null = never chosen)
  - `fun applyAutostartAtStart(settings: SettingsStore, autostart: Autostart)` — installed and null → `enable()` and save `true`; `true` and the Run value points elsewhere → rewrite it; `false` → nothing

- [ ] **Step 1: Failing tests** (fake `RegistryPort`): `theRunEntryQuotesThePathAndMarksAutostart` (`"C:\Program Files\Requestify\Requestify.exe" --autostart`); `firstInstalledStartTurnsItOn`; `switchedOffStaysOff`; `taskManagerDisabledIsReported`; `enablingClearsTaskManagersDisable`; `notInstalledIsUnavailableAndWritesNothing`; `aMovedInstallUpdatesTheEntry`; `SavedFormatCompatTest.startWithWindowsIsPinned`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: start with Windows through the per-user Run key`.

### Task 13: The crash guard

**Files:**
- Create: `desktop/CrashGuard.kt`
- Modify: `core/log/LogRecords.kt` (`AppStopped`, `LogKind.App`), `core/log/LogRows.kt`, `core/log/Logbook.kt` (one-shot input), `ui/log/LogScreen.kt` (row + details text), both `strings.xml`
- Test: `desktop/CrashGuardTest.kt`, `core/log/LogRowsTest.kt`, `core/log/LogbookTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Task 8 `LaunchMode`, `Installation`.
- Produces:
  - `@Serializable data class RunMarker(val startedAtEpochMs: Long, val lastAliveEpochMs: Long, val afterCrash: Boolean = false, val crashFile: String? = null)` in `running.json`
  - `data class PreviousRun(val lastAliveEpochMs: Long, val crashLine: String?, val restarted: Boolean)`
  - `class CrashGuard(dir: Path, clock: () -> Long, mode: LaunchMode, installation: Installation, version: String, relaunch: (List<String>) -> Unit, showFatal: (String) -> Unit, exit: (Int) -> Unit, restartWindowMs: Long = 600_000, keepFiles: Int = 10)` with
    - `fun previousRun(): PreviousRun?` — a leftover marker from the run before (read before `markRunning`)
    - `fun markRunning()`, `fun touch()` (updates `lastAliveEpochMs`; Main calls it every 5 minutes, off the UI thread), `fun markOrderlyEnd()` (deletes the marker; skipped once a crash is being handled)
    - `fun onUncaught(t: Throwable)` — writes `crash/<yyyyMMdd-HHmmss>.txt` (time, version, stack trace), keeps the newest 10, records `crashFile` in the marker, then: installed and not (`mode == AfterCrash` and now − `startedAt` < 10 min) → `relaunch(listOf(exe, "--after-crash"))`; otherwise `showFatal("Requestify is gestopt door een fout. Start het opnieuw.")` (app language); then `exit(1)`. Re-entrant calls (a second error while handling) go straight to `exit(1)`.
    - `fun install(): CoroutineExceptionHandler` — sets the default uncaught handler (covers the AWT event thread) and a shutdown hook calling `markOrderlyEnd()`; the returned handler goes into the app scope
  - `fun windowsMessageBox(text: String)` via JNA `User32` (`MB_OK | MB_ICONERROR | MB_TOPMOST`); not unit-tested
  - `LogRecord.AppStopped(rowId, atEpochMs, lastAliveEpochMs: Long, crashLine: String?, restarted: Boolean)` `@SerialName("app_stopped")`, `LogKind.App`, always a problem; row "Requestify is onverwacht gestopt (laatst actief 21:14)" + "· herstart"
  - `AppGraph(…, previousRun: PreviousRun? = null)` hands it to the `Logbook` (`LogInputs.previousRun`), which writes one `AppStopped` row at start
  - Test hook: environment variable `REQUESTIFY_TEST_CRASH_AFTER_S` makes Main throw on the UI thread after that many seconds (no visible control)

- [ ] **Step 1: Failing tests.** `aCrashWritesAFileAndKeepsTheNewestTen`; `aCrashRestartsOnceWithAfterCrash`; `theRelaunchPassesThePathAsOneArgument` (a path with spaces is one list element); `aSecondCrashWithinTenMinutesStopsWithTheMessage`; `aCrashLongAfterARestartRestartsAgain`; `notInstalledShowsTheMessageInsteadOfRestarting`; `aLeftoverMarkerIsReportedWithItsCrashLine`; `anOrderlyEndLeavesNoMarker`; `theShutdownHookEndsOrderly` (hook runs → no marker → next `previousRun()` null); `theShutdownHookDoesNotEraseACrash`; `anExceptionOnTheEventThreadReachesTheGuard` (thrown inside `EventQueue.invokeLater` → `onUncaught` called; confirms how AWT/Compose deliver it); `LogRowsTest.appStoppedIsAProblemRow`; `LogbookTest.aPreviousRunBecomesOneRow`; `SavedFormatCompatTest.runMarkerAndAppStoppedArePinned`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: a crash restarts Requestify once and leaves a trace`.

### Task 14: The tray

**Files:**
- Create: `desktop/Tray.kt`
- Modify: `core/settings/Settings.kt` (`closeHintShown`), both `strings.xml`
- Test: `desktop/TrayTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Task 9 `AppIconImages`, Task 10 `problems`, Task 6 `saveHealth.problems`, Task 11 `Notice`.
- Produces:
  - `data class TrayLook(val warning: Boolean, val tooltip: String, val statusLine: String)`; `suspend fun trayLook(problems: List<ConnectionProblem>, saveProblems: List<SaveProblem>): TrayLook` — status "Alles gekoppeld" / "All connected" or the top problem's chip text (a save problem when there is no connection problem); tooltip "Requestify — <status>"
  - `interface TrayPort { fun show(look: TrayLook); fun notify(title: String, text: String); fun remove() }`
  - `class AwtTray(labels: TrayLabels, onOpen: () -> Unit, onQuit: () -> Unit, onMessageClick: () -> Unit) : TrayPort` — AWT `TrayIcon` with `AppIconImages.awt(SystemTray.trayIconSize, warning)`; a single left click opens (mouse listener), the action event (a click on a notification) calls `onMessageClick`; `PopupMenu`: disabled status line, Openen, Afsluiten; not unit-tested beyond construction
  - `Settings.closeHintShown: Boolean = false`; `fun closeWindow(settings: SettingsStore, hide: () -> Unit, notify: (Notice) -> Unit)` — hides; the first time ever also sends `Notice.CloseHint` and saves `closeHintShown = true`

- [ ] **Step 1: Failing tests.** `TrayTest.nothingWrongIsAllConnectedWithoutAMark`; `aProblemShowsTheMarkAndItsText`; `aSaveProblemAloneShowsTheMark`; `theCloseHintIsShownOnceEver`; `SavedFormatCompatTest.closeHintShownIsPinned`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: Requestify lives in the system tray`.

### Task 15: Main wires the shell

**Files:**
- Modify: `Main.kt`, `AppGraph.kt` (expose what Main needs), `ui/AppScaffold.kt` (open on a given destination)
- Test: `AppCloseTest.kt`, `AppStartTest.kt` (create)

**Interfaces:**
- Consumes: Tasks 6, 8–14.
- Produces, in `Main.kt`:
  - Start order: `launchMode(args)` → `Installation.current()` → `SingleInstance(dataDir).start(onShowRequested, askToShow = mode == Normal, waitForLockMs = if AfterCrash 10_000 else 0)` (`AlreadyRunning` → exit 0 without touching any other file) → `CrashGuard` (`previousRun()`, `install()`, `markRunning()`) → `AppGraph` (app scope gets the crash handler) with `previousRun` for the Log → `applyAutostartAtStart` → `graph.start()` → `Notifier.start()` → `application { … }`.
  - `class ShellState { var visible: Boolean; var openOn: Destination? }` (Compose state): `fun initiallyVisible(mode: LaunchMode): Boolean` = `mode == Normal`; `onShowRequested` and the tray's Open set `visible = true` (kept until the window composes); a notice click sets `openOn` from `Notice.target`.
  - `Window(visible = shell.visible, icon = …windowIcons, onCloseRequest = { closeWindow(…) })`; the tray lives for the whole `application`.
  - Quit (tray) = `quitApp(hideTray, hideWindow, stopGracefully, markOrderlyEnd, exit)`: hide both, `stopGracefully()` (reward pause + flush, ≤ 3 s), `markOrderlyEnd()`, exit.
  - The tray follows `combine(graph.problems, graph.saveHealth.problems)` → `tray.show(trayLook(…))`. The notifier's `send` = `noticeText(n)` → `tray.notify(title, text)` and remembers `n.target`; `onMessageClick` opens the window on the last notice's target.
  - `AfterCrash` → `Notice.RestartedAfterCrash` at start; `crashGuard.touch()` every 5 minutes on `Dispatchers.IO`.

- [ ] **Step 1: Failing tests.** `AppCloseTest.quitHidesStopsMarksAndExitsInOrder`; `AppStartTest.onlyANormalStartShowsTheWindow`; `AppStartTest.aShowRequestBeforeTheWindowIsKept`; `AppStartTest.aNoticeClickOpensItsTarget`; `AppStartTest.anotherInstanceExitsWithoutTouchingFiles` (data dir unchanged).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run, then the whole suite → PASS; `./gradlew run` starts with the window and a tray icon; closing the window keeps the process running.
- [ ] **Step 5:** Commit `feat: the app starts once, hides in the tray and quits from it`.

### Task 16: Settings — start with Windows

**Files:**
- Modify: `ui/settings/SettingsScreen.kt`, `ui/AppScaffold.kt` (pass `graph`'s `Autostart`), `AppGraph.kt` or `Main.kt` (provide it), both `strings.xml`
- Test: `ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: Task 12 `Autostart`, `AutostartState`.
- Produces: a "Starten met Windows" / "Start with Windows" switch with hint "Start verborgen in het systeemvak" / "Starts hidden in the system tray"; `Unavailable` → disabled, "Alleen in de geïnstalleerde app"; `DisabledInTaskManager` → off, "Uitgezet in Taakbeheer"; switching saves `startWithWindows` and calls `enable()`/`disable()` off the UI thread. The language hint says a restart means Afsluiten in the systeemvak and starting again.

- [ ] **Step 1: Failing tests.** `SettingsScreenTest.theSwitchFollowsWindows`; `unavailableIsDisabledWithItsHint`; `switchingOffDisablesAndSaves`; `taskManagerDisabledShowsOffWithItsHint`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; render Settings (switch on, unavailable, disabled in Task Manager; dark + light) for the user.
- [ ] **Step 5:** Commit `feat: a Start with Windows switch in Settings`.

## Phase C — the parked follow-ups

### Task 17: Texts and the Log say what happened

**Files:**
- Modify: `ui/common/ConnectionChip.kt`, `ui/setup/DonationsCard.kt`, `core/log/LogRows.kt` + `ui/log/LogScreen.kt`, `core/twitch/chat/ChatReplies.kt` + `core/twitch/chat/TwitchChat.kt`, `ui/home/HomeViewModel.kt` + `ui/home/AddTrackField.kt`, `ui/blacklist/BlacklistViewModel.kt` + `BlacklistScreen.kt`, `core/log/Logbook.kt`, `core/donations/streamelements/StreamElementsParse.kt` + `StreamElementsSource.kt`, `core/donations/DonationHandler.kt`, both `strings.xml`
- Test: the matching test classes

**Interfaces:**
- Consumes: Task 10 `ConnectionProblem.Donation.keyExpiresAtEpochMs`.
- Produces: `LogStep.Ignored(reason: IgnoredReason = NoLink)` with `enum IgnoredReason { NoLink, ModerationRejected, TipFailed }` (serial names pinned); `RequestReceived.occurredAtEpochMs: Long? = null`.

- [ ] **Step 1: Failing tests.**
  - `ConnectionChipTest.keyExpiringSaysTheDays` ("DonoLink-sleutel verloopt over 6 dagen", "… morgen", "… vandaag")
  - `DonationsCardTest.aRefusedKeySaysRefused` ("Sleutel geweigerd" / "Token geweigerd", never "Login verlopen")
  - `LogRowsTest.positionsAreNeverZero` (a request that is next says so; others 1-based)
  - `TwitchChatTest.blacklistWithoutASkipSaysBlockedOnly` (nothing playing / not a request → "Geblokkeerd: …", no "overgeslagen")
  - `HomeViewModelTest.aShortLinkToAnAlbumSaysNotATrack`, `BlacklistViewModelTest.aShortLinkToAnAlbumSaysNotATrack`
  - `LogbookTest.switchingAwayClosesTheOutageRow` ("bron gewijzigd"), `LogbookTest.theFirstConnectIsNotRestored`
  - `StreamElementsSourceTest.moderatedAndFailedTipsReachTheLog`, `LogbookTest.donationDetailsShowWhenItWasMade`, `SavedFormatCompatTest.ignoredReasonsArePinned`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement each. **Step 4:** Run, then the whole suite → PASS; render the Donations card badge states and the chip with a day count for the user.
- [ ] **Step 5:** Commit `fix: texts and the Log say exactly what happened`.

### Task 18: Live-status edge cases

**Files:**
- Modify: `core/stream/RequestsGate.kt`, `core/twitch/StreamWatcher.kt`, `core/log/BitsGapWatcher.kt`, `core/stream/StreamState` (if a field is needed), `AppGraph.kt`
- Test: `core/stream/RequestsGateTest.kt`, `core/twitch/StreamWatcherTest.kt`, `core/log/BitsGapWatcherTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Produces: `StreamState.streamStartedAtEpochMs: Long? = null` (Helix `started_at` of the stream the gate follows; pinned); the gate's heartbeat input becomes `twitchSessionConnected: StateFlow<Boolean>`.

- [ ] **Step 1: Failing tests.**
  - `StreamWatcherTest.aRestartDuringANewStreamEndsTheOldOne` (saved live with `started_at` A; Helix says live with B > A → `StreamEnded` for A (free requests cleared and refunded, Log rows), then `WentLive` for B)
  - `StreamWatcherTest.aContradictingReadIsReadAgainAfterTheWindow` (a read contradicting a < 5-min-old event is re-read once the 5 minutes have passed, and applied then)
  - `RequestsGateTest.theHeartbeatTicksOnlyWhileTheSessionIsConnected`
  - `BitsGapWatcherTest.aStreamThatEndedWhileOffLeavesAGap` (from `lastSeenLiveMs` to the end; "until the stream ended" when unknown)
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `fix: live status survives restarts mid-stream and contradicting reads`.

### Task 19: Under the hood

**Files:**
- Modify: `core/log/LogStore.kt`, `core/donations/donolink/DonoLinkSource.kt`, `core/donations/streamelements/AstroClient.kt`, `ui/setup/TwitchSetupViewModel.kt` + `core/twitch/points/RewardManager.kt`, the two flaky tests' classes
- Test: `core/log/LogStoreTest.kt`, `core/donations/donolink/DonoLinkSourceTest.kt`, `core/donations/streamelements/AstroClientTest.kt`, `ui/setup/TwitchSetupViewModelTest.kt`, `core/storage/SavedFormatCompatTest.kt`, `core/twitch/chat/ChatReplySenderTest.kt`, `core/twitch/points/RewardManagerTest.kt`

**Interfaces:**
- Produces: `DonoLinkSource(maxPagesPerPoll: Int = 10)` (continues at the next poll).

- [ ] **Step 1: Failing tests.**
  - `LogStoreTest.stopWhileWritingWritesEachRecordOnce` (stop races a write → no duplicate lines)
  - `DonoLinkSourceTest.catchUpReadsAtMostTenPagesPerPoll` (25 pages → 10, 10, 5 over three polls, in order, nothing twice)
  - `AstroClientTest.theResumeTokenIsUrlEncoded` (a token with `+/=&` round-trips)
  - `TwitchSetupViewModelTest.logoutLeavesTheRewardConsistent` (reward and settings agree after logout, also when the save is slow)
  - `SavedFormatCompatTest.everyPersistedTypeHasAFixture` (a v1 JSON fixture for `DonationCursors`, `LogRecord` (each kind), `StreamState`, `OutboxEntry`, `RejectedEntry`, `IncomingRequest`, `Blacklist`, `RunMarker`, read without moving anything aside)
  - `ChatReplySenderTest.rateLimitedWaitsAndRetries` and `RewardManagerTest.aNewerWantedStateWinsOverAPendingRetry` rewritten on virtual time (no wall-clock waits); run each 50× (`--rerun-tasks` loop or a repeat rule) without a failure
- [ ] **Step 2:** Run → the new tests FAIL. **Step 3:** Implement. **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `fix: no duplicate Log lines, bounded catch-up, safe tokens, steady tests`.

### Task 20: StreamElements channel currency

**Files:**
- Modify: `core/donations/streamelements/StreamElementsApi.kt`, `core/donations/DonationHandler.kt`, `ui/setup/DonationsSetupViewModel.kt` + `DonationsCard.kt`, `core/settings/DonationSettings.kt` (uses the existing `streamElementsCurrency`), `docs/superpowers/specs/2026-11-18-streamelements-api-notes.md`, both `strings.xml`
- Test: `core/donations/streamelements/StreamElementsApiTest.kt`, `core/donations/DonationHandlerTest.kt`, `ui/setup/DonationsSetupViewModelTest.kt`

**Interfaces:**
- Produces: the channel fetch also returns the channel currency (field per SE's docs — record it in the API notes; if SE's docs show no such field, record that and keep today's behaviour); the currency is saved in `DonationSettings.streamElementsCurrency` when known.

- [ ] **Step 1: Failing tests.** `StreamElementsApiTest.theChannelGivesItsCurrency` (fixture); `DonationHandlerTest.aTipInAnotherCurrencyThanTheChannelsIsOtherCurrency` (channel EUR, tip USD → rejected `OtherCurrency("USD")`, listed with its track); `DonationHandlerTest.anUnknownChannelCurrencyComparesInTheTipsCurrency` (today's behaviour); `DonationsSetupViewModelTest.anUnknownCurrencyIsSaid`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `fix: the StreamElements minimum is in the channel's currency`.

## Phase D — finish

### Task 21: Whole suite, renders, manual checklist

**Files:**
- Create: `docs/superpowers/plans/2026-11-18-requestify-plan-4-manual-checks.md`
- Modify: `README.md` (start with Windows only in the installed app; `.\gradlew.bat packageExe` to try it)

- [ ] **Step 1:** `./gradlew jvmTest` → all green (≥ 871 tests plus this plan's); paste the summary line.
- [ ] **Step 2:** Collect the renders of Tasks 7, 9, 16 and 17 for the user.
- [ ] **Step 3:** Write the manual checklist for a locally installed build (`.\gradlew.bat packageExe`, install, start the installed app): tray icon (sharp at the PC's scale) and menu; close hides, the hint once; Quit pauses the reward; a second start shows the first window; notifications' look and sender name, a click opens Koppelingen; sign out/in of Windows → starts hidden; Task Manager → Startup off → Settings shows it, never back on; a Windows restart not logged as a crash; `REQUESTIFY_TEST_CRASH_AFTER_S=30` → restarts once with a notification and a Log row; a second forced crash within 10 minutes → the message, no restart.
- [ ] **Step 4:** Commit `docs: plan 4 manual checks`.
