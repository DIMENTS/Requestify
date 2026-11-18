# Requestify plan 5 — release to friends — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A friend downloads one file from a GitHub release, installs Requestify per user without questions, is welcomed by a playful one-card-at-a-time wizard, and from then on Requestify updates itself from GitHub Releases without ever interrupting a stream.

**Architecture:**
- **Updater:** a new `core/update/` package. `AppVersion`, a GitHub `releases/latest` client, a verifying downloader with a small on-disk store, the install policy (pure functions) and an `UpdateService` (state and events). A `desktop/UpdateLauncher` hands the downloaded MSI to a detached PowerShell helper, which waits for the app to exit, runs `msiexec`, writes the exit code and starts Requestify again.
- **Packaging:** packaging moves to a version from a tag (or a fresh local one), per-user install and a custom WiX `main.wxs`. Its custom actions run `Requestify.exe --uninstall-cleanup` on a real uninstall, and start Requestify after a friend's install.
- **Wizard:** a `WelcomeWizardViewModel` over the existing setup view models. Its UI shows the Connections cards' content one card at a time, with Compose motion that respects Windows' animation setting.

**Tech Stack:**
- Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Material 3 Expressive), JetBrains Runtime 21, Gradle 8.14.4.
- Ktor client (the existing CIO setup, `MockEngine` in tests), kotlinx serialization, JNA 5.19.1.
- jpackage + WiX through Compose's native distributions, GitHub Actions (Windows runner), PowerShell (the update helper).
- JUnit/kotlin-test, coroutines-test virtual time, `runComposeUiTest`.

**Spec:** `docs/superpowers/specs/2026-11-18-requestify-plan-5-release-design.md` (parents: `2026-11-16-requestify-design.md`, `2026-11-18-requestify-plan-4-background-design.md`). Wizard mockups: `docs/superpowers/specs/assets/plan-5-wizard/wizard-b-playful.html`.

## Global Constraints

- **Paths:** relative to `src/jvmMain/kotlin/nl/diments/requestify/` (main) and `src/jvmTest/kotlin/nl/diments/requestify/` (tests), unless they start with `src/`, `docs/`, `packaging/`, `.github/` or a root file name.
- **Strings:** every user-facing string exists in both `src/commonMain/composeResources/values/strings.xml` and `values-nl/strings.xml`, with identical keys and placeholders (`StringsTest`).
  - Dutch app words: "Koppelingen", "Instellingen", "Logboek", "systeemvak", "Nu bijwerken", "Zoeken naar updates", "Over Requestify", "Welkomstwizard".
- **Persisted names are stable forever:**
  - Every new `@SerialName` and persisted field gets a pin in `core/storage/SavedFormatCompatTest.kt`.
  - New settings fields have defaults.
  - Files under `%APPDATA%\Requestify` keep their names.
- **Versions:**
  - A release version is its tag `v<MAJOR>.<MINOR>.<BUILD>`: digits only, MAJOR ≤ 255, MINOR ≤ 255, BUILD ≤ 65535.
  - Local builds are `0.0.<BUILD>`, a different BUILD every build.
  - `AppVersion.isLocalBuild` ⇔ MAJOR = 0 and MINOR = 0.
  - The running version is `System.getProperty("jpackage.app-version")` (absent under `gradlew run`).
- **GitHub:** repository `DIMENTS/Requestify`.
  - Release assets: `Requestify-Setup-<version>.exe`, `Requestify-<version>.msi` and `SHA256SUMS.txt` (lines `<64 lowercase hex>  <file name>`).
  - API: `GET https://api.github.com/repos/DIMENTS/Requestify/releases/latest`, headers `Accept: application/vnd.github+json` and `User-Agent: Requestify-updater` (nothing personal).
- **Update timing:**
  - The first check comes 2 minutes after start, then every 6 hours, plus "Zoeken naar updates".
  - Install only at a start or on "Nu bijwerken". **A start installs only when:**
    - it is not `--after-crash`;
    - `stream.json` is readable;
    - the stream was not last seen live within the gate's grace of 600 000 ms (≤ counts as live);
    - the update has had fewer than 2 attempts.
  - **"Nu bijwerken" is allowed only when `!status.live && !status.open`.**
- **Update stays quiet:** no notification, no problem chip or banner, no `ConnectionProblem`. The Log gets "updated" / "update failed" rows only.
- **Per-user install:**
  - `%LOCALAPPDATA%\Requestify`;
  - fixed `upgradeUuid = "5df66373-1641-4856-80f2-f089f5d7fc7c"` (never change it);
  - vendor `DIMENTS`;
  - a Start menu entry and a desktop shortcut.
- **Update files** live in `%LOCALAPPDATA%\Requestify-updates` (`AppPaths.updatesDir`), never in roaming `%APPDATA%`.
- **New start arguments:** `--uninstall-cleanup` (run by the uninstaller only; exits at once).
  - The relaunch after an update uses `--autostart` when the old copy was hidden, otherwise no argument.
  - The MSI property `REQUESTIFY_UPDATER=1` marks an updater install.
- **Every process Requestify starts** (crash restart, update helper) is detached: stdout/stderr discarded, no inherited pipes (M-13).
- **Plan 4 stays:** Run-key autostart (`HKCU\…\Run`, value `Requestify`, plus `…\Explorer\StartupApproved\Run`), crash guard, single instance and tray. "Never re-enable what the streamer turned off" still holds, except for restoring an entry the uninstaller itself removed (the marker).
- **Icons:** Phosphor Icons (MIT), as `ImageVector`s in `ui/common/Icons.kt`. Each one used is listed in `NOTICE`.
- **Never drive the user's desktop with scripted clicks.** UI checks are offscreen renders (`REQUESTIFY_RENDERS`). Installing, updating and uninstalling are checked by the user (Task 13's checklist).
- **Nothing outward without the user's go-ahead at that moment:** no `git push`, no remote, no GitHub release, no history rewrite. Task 13 stops and asks.
- **Tests:** `./gradlew.bat jvmTest` (Git Bash) or `.\gradlew.bat jvmTest` (PowerShell); one class with `--tests "*ClassName"`.

## Review Focus

1. **A restart mid-stream with an update waiting:** no install. Exactly 10 minutes counts as live; an unreadable `stream.json` counts as live; a missing one does not block. (Task 6 `aStartWithinTheGraceDoesNotInstall`, `anUnreadableStreamFileDoesNotInstall`, `aMissingStreamFileDoesNotBlock`, `aCrashRestartNeverInstalls`.)
2. **A broken or partial release** (no MSI, no `SHA256SUMS.txt`, a wrong checksum, an odd tag): no install, no loop, at most one Log row per version. (Task 4 `aReleaseWithoutTheMsiIsNotUsable`, `anOddTagIsNotUsable`; Task 5 `aWrongChecksumDeletesTheFile`; Task 6 `aFailedDownloadIsLoggedOncePerVersion`.)
3. **A failing `msiexec`:** the old version comes back by itself, the Log says why, and there are at most 2 attempts per version. (Task 6 `aFailedInstallIsReportedAndRetriedOnce`; Task 7 `theHelperRelaunchesWhateverIsInstalled`; Task 13 manual check.)
4. **Upgrade vs uninstall:** an upgrade keeps the autostart choice; only a real uninstall removes it and leaves the marker; a reinstall restores it exactly. (Task 3 `cleanupRemembersAnEnabledEntry`, `cleanupRemembersADisabledEntry`, `restoreBringsBackExactlyWhatWasRemoved`, `noMarkerNoRestore`; Task 13 manual check.)
5. **The wizard and existing users:** never shown unasked to someone with connections; a half-finished one resumes; a connected platform is skipped. (Task 10 `anExistingUserNeverSeesItUnasked`, `aHalfFinishedWizardResumes`, `aConnectedPlatformIsSkipped`.)

---

## Phase A — the shell around the update

### Task 1: Crash-guard minors M-5, M-11, M-13

**Files:**
- Create: `desktop/Processes.kt`
- Modify: `desktop/CrashGuard.kt`, `Shell.kt` (`quitApp`), `Main.kt` (the relaunch lambda)
- Test: `desktop/CrashGuardTest.kt`, `AppStartTest.kt`, `desktop/ProcessesTest.kt` (create)

**Interfaces:**
- Produces:
  - `fun detachedProcess(command: List<String>): ProcessBuilder`, in `desktop/Processes.kt`. stdin comes from `NUL`; stdout and stderr go to `ProcessBuilder.Redirect.DISCARD`.
  - `CrashGuard.shuttingDown: Boolean`, set first thing in `shutdownHook`.
  - `quitApp(...)`: same signature; it never lets an exception from `stopGracefully` escape.

- [ ] **Step 1: Failing tests.**
  - `ProcessesTest.aDetachedProcessInheritsNoStdio`: `redirectOutput()` and `redirectError()` are `DISCARD`; `redirectInput().file()` is `NUL`.
  - `CrashGuardTest.anErrorDuringShutdownIsNoCrash`: run the hook's `markOrderlyEnd` path via `shutdownHook.run()`, then `onUncaught(e)`. Expect no `relaunch`, no `showFatal`, the marker removed (orderly), and a crash file still written for diagnosis.
  - `AppStartTest.aFailingGracefulStopStillQuits`: `stopGracefully` throws; `markOrderlyEnd` and `exit` are still called; nothing is rethrown.
- [ ] **Step 2:** Run the three classes → FAIL.
- [ ] **Step 3:** Implement.
  - `Main.kt`'s relaunch becomes `{ command -> detachedProcess(command).start() }`.
  - `shutdownHook` sets `shuttingDown = true` before `markOrderlyEnd()`.
  - `onUncaught` with `shuttingDown`: write the crash file, then `exit(1)`, with no relaunch and no message.
  - `quitApp` wraps `runBlocking { stopGracefully() }` in `try { } catch (_: Throwable) { }`.
- [ ] **Step 4:** Run the three classes, then the whole suite → PASS.
- [ ] **Step 5:** Commit `fix: shutdown and Quit errors are no crashes, and started processes inherit no stdio`.

### Task 2: Versions and packaging

**Files:**
- Create: `core/update/AppVersion.kt`, `packaging/requestify.ico` (generated), `src/jvmTest/kotlin/nl/diments/requestify/desktop/IconExport.kt` (a generator gated by `REQUESTIFY_ICON_OUT`, like `Renders`)
- Modify: `build.gradle.kts`, `core/storage/AppPaths.kt` (`updatesDir`), `Main.kt` (`APP_VERSION` from `AppVersion.current()`), `.gitignore` if needed
- Test: `core/update/AppVersionTest.kt`, `core/storage/AppPathsTest.kt` (extend or create)

**Interfaces:**
- Produces:
  - `data class AppVersion(val major: Int, val minor: Int, val build: Int) : Comparable<AppVersion>`, with:
    - `val isLocalBuild: Boolean` (major = 0 && minor = 0);
    - `toString()` = `"M.m.b"`;
    - `companion fun parse(text: String): AppVersion?`: accepts `"0.2.0"` and `"v0.2.0"`; null for `"dev"`, suffixes (`"0.2.0-beta"`), missing parts and parts out of range;
    - `companion fun current(): AppVersion?` from `jpackage.app-version`.
  - `AppPaths.updatesDir: Path`, which is `%LOCALAPPDATA%\Requestify-updates`. It falls back the way `default()` falls back for `APPDATA`.
  - Gradle property `requestify.version`. When it is absent, the version is `0.0.<BUILD>`, where BUILD = `(minutes since epoch % 65535) + 1`, from a `ValueSource`. The configuration cache must not freeze it; an ordinary `val` at configuration time would freeze it.

- [ ] **Step 1: Failing tests.**
  - `AppVersionTest.parsesTagsAndVersions`: `"v1.2.3"` and `"1.2.3"` both give `AppVersion(1,2,3)`.
  - `rejectsWhatWindowsInstallerCannotTake`: `"256.0.0"`, `"0.256.0"`, `"0.0.65536"`, `"0.2.0-beta"`, `"dev"` and `""` all give null.
  - `comparesNumerically`: `0.10.0 > 0.9.9`.
  - `localBuildsAreZeroZero`.
  - `AppPathsTest.updatesAreNotRoaming`: with `LOCALAPPDATA=X` and `APPDATA=Y`, `updatesDir` is `X\Requestify-updates`. Inject the environment through a testable `AppPaths.default(env: (String) -> String?)` overload.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `AppVersion` and `AppPaths.updatesDir`. `Main.APP_VERSION` = `AppVersion.current()?.toString() ?: "dev"`.
- [ ] **Step 4:** `build.gradle.kts`:
  - `packageVersion` from `requestify.version` or the local `ValueSource`;
  - fail the build if `requestify.version` does not parse;
  - `vendor = "DIMENTS"`;
  - `windows { perUserInstall = true; upgradeUuid = "5df66373-1641-4856-80f2-f089f5d7fc7c"; menu = true; menuGroup = "Requestify"; shortcut = true; iconFile.set(project.file("packaging/requestify.ico")) }`.
- [ ] **Step 5:** `IconExport` writes `packaging/requestify.ico` from `/icon/requestify-icon.webp` (Skia decode, as in `AppIconImages`): PNG-compressed entries at 16, 20, 24, 32, 40, 48, 64 and 256. Run it once with `REQUESTIFY_ICON_OUT=packaging`, then check the file (non-empty, ICO header `00 00 01 00`, 8 entries).
- [ ] **Step 6: Verify the build.** Run `./gradlew.bat packageMsi` twice, a minute apart. Expected: two MSI files with different `0.0.<n>` names under `build/compose/binaries/main/msi/`. Then `./gradlew.bat packageMsi -Prequestify.version=0.2.0` → `Requestify-0.2.0.msi`; and `-Prequestify.version=0.2.0-x` fails the build.
- [ ] **Step 7:** Whole suite → PASS. Commit `build: versions from a tag or a fresh local number, per-user install, vendor and installer icon`.

### Task 3: Uninstall cleanup, the reinstall marker, and starting after install

**Files:**
- Create: `desktop/UninstallCleanup.kt`, `packaging/wix/main.wxs` (copied from jpackage 21's generated one, plus the additions below), `packaging/check-msi.ps1` (lists the MSI's `CustomAction` and `InstallExecuteSequence` rows through the `WindowsInstaller.Installer` COM object)
- Modify: `desktop/Autostart.kt` (`restore`), `desktop/Launch.kt` (`UNINSTALL_CLEANUP_ARG`), `Main.kt` (the cleanup branch, the restore at start), `build.gradle.kts` (the custom `main.wxs` reaches jpackage)
- Test: `desktop/UninstallCleanupTest.kt` (create), `desktop/AutostartTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: `RegistryPort`, `RunKeyAutostart`, `AppPaths.dataDir` (plan 4); `AppVersion` (Task 2).
- Produces:
  - `const val UNINSTALL_CLEANUP_ARG = "--uninstall-cleanup"`.
  - `@Serializable enum class RemovedAutostart { @SerialName("on") On, @SerialName("disabled_in_task_manager") DisabledInTaskManager }`.
  - `@Serializable data class AutostartRemovedMarker(val state: RemovedAutostart)`, kept in the file `autostart-removed.json` in the data dir.
  - `fun cleanUpForUninstall(registry: RegistryPort, dataDir: Path)`. If the Run value exists:
    - write the marker (`DisabledInTaskManager` when the StartupApproved first byte is odd, otherwise `On`);
    - then delete both values.
    
    It never throws.
  - `fun restoreAutostartAfterReinstall(dataDir: Path, autostart: Autostart)`. With a marker and an installed build, it calls `autostart.restore(disabledInTaskManager)`, then deletes the marker. It never throws.
  - `Autostart.restore(disabledInTaskManager: Boolean): Boolean` writes the Run value, plus StartupApproved: Task Manager's enabled bytes, or `03 00 …` for disabled. `NoAutostart.restore` returns false.

- [ ] **Step 1: Failing tests** (fake `RegistryPort`, temp dir):
  - `UninstallCleanupTest.cleanupRemembersAnEnabledEntry`;
  - `cleanupRemembersADisabledEntry`;
  - `cleanupWithoutAnEntryLeavesNoMarker`;
  - `restoreBringsBackExactlyWhatWasRemoved`: enabled → `AutostartState.On`; disabled → `AutostartState.DisabledInTaskManager`;
  - `noMarkerNoRestore`: a streamer-deleted entry is not re-created;
  - `aBrokenMarkerIsDeletedAndIgnored`;
  - `SavedFormatCompatTest`: pins for `on` and `disabled_in_task_manager`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
  - `main()` starts with: `if (UNINSTALL_CLEANUP_ARG in args) { cleanUpForUninstall(JnaRegistry, AppPaths.default().dataDir); return }`. No single instance, no crash guard, no window.
  - Call `restoreAutostartAfterReinstall` right before `applyAutostartAtStart`.
- [ ] **Step 4: The custom `main.wxs`.**
  - Get jpackage 21's generated `main.wxs`: build once with jpackage's `--temp` output, or take it from the JDK's `jdk.jpackage` resources.
  - Copy it to `packaging/wix/main.wxs`, then add:
    - A deferred custom action, `Impersonate="yes"`, `Return="ignore"`. It runs `"[INSTALLDIR]Requestify.exe" --uninstall-cleanup`, is sequenced before `RemoveFiles`, with the condition `REMOVE~="ALL" AND NOT UPGRADINGPRODUCTCODE`.
    - An immediate custom action, `Return="asyncNoWait"`, that runs `"[INSTALLDIR]Requestify.exe"` after `InstallFinalize`, with the condition `NOT Installed AND NOT REQUESTIFY_UPDATER`.
  - Get it into the build:
    - First try Compose's jpackage task input `jpackageResources`, which Compose passes as `--resource-dir`.
    - If a build script cannot add to it, add a `packageRequestifyMsi`/`packageRequestifyExe` pair. That pair runs the toolchain's `jpackage` on `createDistributable`'s app image, with `--resource-dir packaging/wix` and the same name, version, vendor, upgrade UUID, per-user, menu, shortcut and icon options.
    - Ledger the choice as a Ruling.
- [ ] **Step 5: Verify.** Build the MSI, then run `powershell -File packaging/check-msi.ps1 <msi>`. Expected: both custom actions present, with their conditions and sequence positions. (The real uninstall, upgrade and launch behaviour is Task 13's manual check.)
- [ ] **Step 6:** Whole suite → PASS. Commit `feat: uninstalling removes autostart and remembers it for a reinstall, and a fresh install starts Requestify`.

## Phase B — the updater

### Task 4: GitHub releases client

**Files:**
- Create: `core/update/GitHubReleases.kt`, `src/jvmTest/resources/update/release-latest.json`, `release-prerelease-list.json` (fixtures shaped like GitHub's REST answers: `tag_name`, `draft`, `prerelease`, `body`, `html_url`, `assets[{name, browser_download_url, size}]`)
- Test: `core/update/GitHubReleasesTest.kt`

**Interfaces:**
- Consumes: `AppVersion` (Task 2).
- Produces:
  - `data class ReleaseAsset(val name: String, val url: String, val size: Long)`.
  - `data class Release(val version: AppVersion, val notes: String, val pageUrl: String, val msi: ReleaseAsset, val checksums: ReleaseAsset)`.
  - `sealed interface ReleaseCheck`, with three cases:
    - `data class Found(val release: Release)`;
    - `data object NoneUsable`: no release, an odd tag, or the MSI or `SHA256SUMS.txt` missing;
    - `data class Failed(val reason: String)`: network, HTTP status or parse error.
  - `class GitHubReleases(private val http: HttpClient, private val repo: String = "DIMENTS/Requestify", private val includePrereleases: Boolean = false)`, with `suspend fun latest(): ReleaseCheck`.
    - `includePrereleases` reads `GET /releases?per_page=10` and takes the highest-version non-draft.
    - Main sets it from the environment variable `REQUESTIFY_UPDATE_PRERELEASES=1` (the user's own testing only).

- [ ] **Step 1: Failing tests** (`MockEngine`):
  - `findsTheMsiAndChecksumsOfTheLatestRelease`;
  - `sendsTheGitHubHeadersAndNothingPersonal`;
  - `aReleaseWithoutTheMsiIsNotUsable`;
  - `anOddTagIsNotUsable` (`v1.2`, `v1.2.3-beta`);
  - `noReleasesYetIsNotUsable` (404);
  - `rateLimitedOrDownIsAFailureNotACrash` (403/500/timeout);
  - `prereleasesOnlyWhenAsked`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement with the existing `HttpClient`. Parse with a lenient `Json { ignoreUnknownKeys = true }`. The MSI asset is `Requestify-<version>.msi`, matching the tag's version.
- [ ] **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: read the latest Requestify release from GitHub`.

### Task 5: Downloading and the update store

**Files:**
- Create: `core/update/UpdateStore.kt`, `core/update/UpdateDownloader.kt`
- Test: `core/update/UpdateStoreTest.kt`, `core/update/UpdateDownloaderTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Task 4.
- Produces:
  - `@Serializable data class PendingUpdate(val version: String, val msiFile: String, val sha256: String, val notes: String, val pageUrl: String, val attempts: Int = 0)`, with stable `@SerialName`s.
  - `@Serializable data class JustUpdated(val from: String, val to: String, val notes: String)`.
  - `class UpdateStore(val dir: Path)`. Its files in `dir` are `pending.json`, `just-updated.json` and `install-exit-code.txt`. It has:
    - `fun pending(): PendingUpdate?`, `fun savePending(p: PendingUpdate)`, `fun clearPending()` (also deletes MSI files);
    - `fun markAttempt(): PendingUpdate?` (attempts + 1, saved);
    - `fun lastExitCode(): Int?`, `fun clearExitCode()`;
    - `fun justUpdated(): JustUpdated?`, `fun saveJustUpdated(j: JustUpdated)`, `fun clearJustUpdated()`.
    
    No method throws; an unreadable file reads as null.
  - `sealed interface DownloadResult { data class Ready(val pending: PendingUpdate); data class Failed(val reason: String) }`.
  - `class UpdateDownloader(private val http: HttpClient, private val store: UpdateStore)`, with `suspend fun download(release: Release): DownloadResult`:
    - fetches `SHA256SUMS.txt`, finds the MSI's line, and streams the MSI to `<name>.part` on `Dispatchers.IO`;
    - checks the SHA-256, renames the file and saves `PendingUpdate`;
    - replaces an older pending update (deletes its files);
    - returns the existing `Ready` when the same version is already pending and its file still matches.

- [ ] **Step 1: Failing tests:**
  - `UpdateStoreTest.roundTripsAndToleratesGarbage`, `markAttemptCounts`, `clearPendingDeletesTheMsi`;
  - `UpdateDownloaderTest.downloadsAndVerifies`;
  - `aWrongChecksumDeletesTheFile`;
  - `aMissingChecksumLineIsAFailure`;
  - `aNewerReleaseReplacesThePendingOne`;
  - `theSamePendingVersionIsNotDownloadedAgain`;
  - `aDiskErrorIsAFailureNotACrash` (read-only dir);
  - pins for `PendingUpdate` and `JustUpdated`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: download a release's MSI and verify its checksum`.

### Task 6: Install policy and UpdateService

**Files:**
- Create: `core/update/UpdatePolicy.kt`, `core/update/UpdateService.kt`
- Modify: `core/stream/RequestsGate.kt` (expose `val loadedUnreadable: Boolean` = the existing private `loadFailed` as it was at construction)
- Test: `core/update/UpdatePolicyTest.kt`, `core/update/UpdateServiceTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 4, 5; `LaunchMode` and `GateStatus` (plan 2/4); `RequestsGate.loadedLastSeenLiveMs`.
- Produces:
  - `sealed interface StreamAtStart { data object Unreadable; data object NeverSeenLive; data class LastSeenLive(val atMs: Long) }`.
    - `fun streamAtStart(gate: RequestsGate): StreamAtStart` gives `Unreadable` when `loadedUnreadable`, `LastSeenLive` when `loadedLastSeenLiveMs != null`, and `NeverSeenLive` otherwise.
  - `fun installAtStart(mode: LaunchMode, running: AppVersion?, pending: PendingUpdate?, stream: StreamAtStart, now: Long, graceMs: Long = 600_000, maxAttempts: Int = 2): Boolean`. It is true only when **all** of these hold:
    - `running` is non-null and not local;
    - `AppVersion.parse(pending.version)` is newer than `running`, with `attempts < maxAttempts`;
    - `mode != AfterCrash`;
    - `stream` is not `Unreadable`;
    - `stream` is not `LastSeenLive` with `now - atMs <= graceMs`.
  - `fun canInstallNow(status: GateStatus): Boolean = !status.live && !status.open`.
  - `sealed interface UpdateState`:
    - `Off` (dev or local build), `Idle`;
    - `data class Checking(val manual: Boolean)`, `data class UpToDate(val checkedAtMs: Long)`;
    - `data class Ready(val version: AppVersion, val notes: String, val pageUrl: String)`;
    - `data class CheckFailed(val manual: Boolean)`;
    - `data class InstallFailed(val version: AppVersion, val pageUrl: String)`.
  - `sealed interface UpdateEvent`:
    - `data class Updated(val from: AppVersion, val to: AppVersion)`;
    - `data class InstallFailed(val version: AppVersion, val exitCode: Int?)`;
    - `data class DownloadFailed(val version: AppVersion, val reason: String)`.
  - `class UpdateService(running: AppVersion?, releases: GitHubReleases, downloader: UpdateDownloader, store: UpdateStore, gate: StateFlow<GateStatus>, scope: CoroutineScope, clock: () -> Long, firstCheckDelayMs: Long = 120_000, everyMs: Long = 21_600_000)`, with:
    - `val state: StateFlow<UpdateState>`;
    - `val events: SharedFlow<UpdateEvent>`, with replay big enough for the start-up events, so a Logbook that subscribes after construction still gets them;
    - `val justUpdated: StateFlow<JustUpdated?>`, `fun dismissJustUpdated()`;
    - `val canInstallNow: StateFlow<Boolean>`;
    - `val installRequests: SharedFlow<PendingUpdate>`;
    - `fun reconcile()`, called once at construction:
      - pending and `running >= pending` → `Updated`, `saveJustUpdated`, `clearPending`, `clearExitCode`;
      - pending with attempts > 0 and `running < pending` → `InstallFailed(exitCode)`, then `state = InstallFailed` when attempts ≥ 2;
    - `fun start()`, `fun checkNow()`;
    - `fun installNow()`: emits `installRequests` only if `canInstallNow` and Ready, after `store.markAttempt()`;
    - `fun prepareInstallAtStart(): PendingUpdate?` (`markAttempt` and return).
- **Rules:** a `CheckFailed` from an automatic check shows only as "not checked" (the Settings text), never as a problem. A `DownloadFailed` is emitted at most once per version per run.

- [ ] **Step 1: Failing tests.**
  - `UpdatePolicyTest` (one line per rule):
    - `aStartWithinTheGraceDoesNotInstall` (also exactly 600 000 ms);
    - `aStartAfterTheGraceInstalls`;
    - `anUnreadableStreamFileDoesNotInstall`;
    - `aMissingStreamFileDoesNotBlock`;
    - `aCrashRestartNeverInstalls`;
    - `aLocalOrDevBuildNeverInstalls`;
    - `twoFailedAttemptsStopAutomaticInstalls`;
    - `anOlderOrEqualPendingIsNotInstalled`;
    - `installNowNeedsOfflineAndClosed`.
  - `UpdateServiceTest` (virtual time, fakes for releases and downloader):
    - `checksTwoMinutesAfterStartThenEverySixHours`;
    - `aNewerReleaseBecomesReady`;
    - `upToDateStaysQuiet`;
    - `aFailedAutomaticCheckIsQuiet`;
    - `aFailedDownloadIsLoggedOncePerVersion`;
    - `afterASuccessfulInstallTheNextStartReportsUpdated`;
    - `aFailedInstallIsReportedAndRetriedOnce`;
    - `installNowIsRefusedWhileLiveOrOpen`;
    - `theDevBuildIsOff`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: decide when an update may install, and keep the update state`.

### Task 7: Launching the install, wiring it in, and the Log rows

**Files:**
- Create: `desktop/UpdateLauncher.kt`
- Modify: `Main.kt`, `AppGraph.kt` (construct the update pieces; `LogInputs.updates`), `core/log/LogRecords.kt`, `core/log/LogRows.kt`, `core/log/Logbook.kt`, `ui/log/LogScreen.kt`, both `strings.xml`
- Test: `desktop/UpdateLauncherTest.kt`, `core/log/LogbookTest.kt`, `core/log/LogRowsTest.kt`, `core/storage/SavedFormatCompatTest.kt`, `ui/log/LogScreenTest.kt` (if it exists; else in `LogRowsTest`)

**Interfaces:**
- Consumes: Task 1 (`detachedProcess`), Task 5 (`UpdateStore`), Task 6.
- Produces:
  - `fun updateHelperCommand(msi: Path, msiLog: Path, exitCodeFile: Path, waitForPid: Long, launcher: Path, hidden: Boolean): List<String>`. It returns `powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command <script>`. The script:
    1. `Wait-Process -Id <pid> -Timeout 60 -ErrorAction SilentlyContinue`;
    2. `$p = Start-Process msiexec.exe -ArgumentList '/i "<msi>" /qn /norestart REQUESTIFY_UPDATER=1 /l*v "<log>"' -Wait -PassThru`;
    3. `Set-Content -LiteralPath '<exitCodeFile>' -Value $p.ExitCode`;
    4. `Start-Process -FilePath '<launcher>'`, plus `-ArgumentList '--autostart'` when hidden.
    
    Every path is single-quoted, with `'` doubled. Paths inside the `-ArgumentList` string are double-quoted.
  - `class UpdateLauncher(private val start: (List<String>) -> Unit = { detachedProcess(it).start() })`, with `fun launch(pending: PendingUpdate, store: UpdateStore, launcher: Path, hidden: Boolean)`.
  - `LogRecord.AppUpdated(rowId, atEpochMs, from: String, to: String)` `@SerialName("app_updated")`. It is not a problem.
  - `LogRecord.UpdateFailed(rowId, atEpochMs, version: String, exitCode: Int?, reason: String?)` `@SerialName("update_failed")`. It is a problem.
  - Both fold to `LogKind.App`.
  - `LogInputs.updates: Flow<UpdateEvent> = emptyFlow()`. `DownloadFailed` and `InstallFailed` become `UpdateFailed` rows; `Updated` becomes an `AppUpdated` row.
  - `AppGraph.updates: UpdateService`. `AppGraph` gains `updatesDir: Path` through `AppPaths`.
  - The `Main` wiring:
    1. After `AppGraph` is built and before `graph.start()`: if `installAtStart(mode, …, streamAtStart(graph.gate), now)` → `prepareInstallAtStart()`, then `launcher.launch(…, hidden = !initiallyVisible(mode))`, `crashGuard.markOrderlyEnd()`, `instance.close()`, `exitProcess(0)`.
    2. Collect `graph.updates.installRequests` → `tray.remove()`, `shell.hideNow()`, `graph.stopGracefully()`, `launcher.launch(…, hidden = !shell.visible)`, `crashGuard.markOrderlyEnd()`, `instance.close()`, `exitProcess(0)`.
    3. `graph.updates.start()` after `graph.start()`.
    4. Under `gradlew run` (no launcher) nothing installs; the service is `Off`.

- [ ] **Step 1: Failing tests.**
  - `UpdateLauncherTest.theCommandWaitsInstallsRecordsAndRelaunches`: the script's order, `REQUESTIFY_UPDATER=1`, `/qn`, and the exit-code file.
  - `pathsWithSpacesAndQuotesSurvive` (`C:\Users\O'Neil Smith\…`).
  - `aHiddenAppComesBackHidden`.
  - `theHelperRelaunchesWhateverIsInstalled`: the relaunch does not depend on the exit code.
  - `itStartsDetached`: `start` receives the command; the default goes through `detachedProcess`.
  - `LogbookTest.updateEventsBecomeAppRows`.
  - `LogRowsTest.aFailedUpdateIsAProblemAnUpdateIsNot`.
  - Pins for `app_updated` and `update_failed`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Log texts:
  - NL: "Bijgewerkt van %1$s naar %2$s" / EN: "Updated from %1$s to %2$s";
  - NL: "Bijwerken naar %1$s mislukt" / EN: "Update to %1$s failed", with the exit code or reason as the detail.
- [ ] **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: install a ready update at a quiet start or on request, and log it`.

### Task 8: Settings → Over Requestify, the update dot, and the "Bijgewerkt" card

**Files:**
- Create: `ui/settings/AboutCard.kt`, `ui/settings/AboutViewModel.kt`, `ui/home/UpdatedCard.kt`
- Modify: `ui/settings/SettingsScreen.kt`, `ui/AppScaffold.kt` (rail dot; `SettingsScreen` arguments; `UpdatedCard` on Home), `ui/home/HomeScreen.kt`, `AppGraph.kt` (`about: AboutViewModel`), `core/settings/Settings.kt`, both `strings.xml`, `src/jvmTest/.../ui/Renders.kt`
- Test: `ui/settings/AboutCardTest.kt`, `ui/home/UpdatedCardTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes: Task 6 (`UpdateService.state`, `justUpdated`, `dismissJustUpdated`, `canInstallNow`, `checkNow`, `installNow`); `AppGraph.browse`.
- Produces:
  - `Settings.lastUpdateCardVersion: String? = null` (`@SerialName` pinned).
  - `class AboutViewModel(updates: UpdateService, version: String, openUrl: (String) -> Unit, openWizard: (() -> Unit)? = null)`. Its methods: `checkNow()`, `installNow()`, `openReleasePage()`, `openLicence()`, `openNotice()`, `openWizard()`.
    - "Welkomstwizard openen" shows only when `openWizard` is set; Task 11 sets it.
    - The licence and notice links go to `https://github.com/DIMENTS/Requestify/blob/main/LICENSE` and `…/NOTICE`.
  - `SettingsScreen(settings, autostart = NoAutostart, about: AboutViewModel? = null)`.
  - The `UpdatedCard` shows while `justUpdated != null && justUpdated.to != settings.lastUpdateCardVersion`. Dismissing it sets `lastUpdateCardVersion` and calls `dismissJustUpdated()`.
  - The release notes are shown as plain text: Markdown heading marks and emphasis stripped, bullets kept as "•".
- **Texts** (NL / EN):
  - "Over Requestify" / "About Requestify";
  - "Versie %1$s" / "Version %1$s";
  - "Zoeken naar updates" / "Check for updates";
  - "Je hebt de nieuwste versie" / "You're up to date";
  - "Kon niet zoeken naar updates" / "Couldn't check for updates" (only after a manual check);
  - "Versie %1$s staat klaar" / "Version %1$s is ready";
  - "Nu bijwerken" / "Update now";
  - "Bijwerken kan als je offline bent en de verzoeken dicht zijn" / "You can update while you're offline and requests are closed";
  - "Bijwerken mislukt" / "Update failed", with "Download hem zelf" / "Download it yourself";
  - "Bijgewerkt naar %1$s" / "Updated to %1$s";
  - "Licentie" / "Licence", "Credits" / "Credits";
  - "Welkomstwizard openen" / "Open the welcome wizard".

- [ ] **Step 1: Failing tests:**
  - `AboutCardTest.showsEachUpdateState` (Off: version only; UpToDate; Ready with "Nu bijwerken"; disabled with the explanation while `canInstallNow` is false; InstallFailed with the link; manual CheckFailed);
  - `theRailShowsADotWhileAnUpdateIsReady`;
  - `UpdatedCardTest.showsOnceAndDismisses`;
  - `markdownBecomesReadableText`;
  - the pin for `lastUpdateCardVersion`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Add renders, light and dark: `aboutStates`, `updatedCard`, `railDot`.
- [ ] **Step 4:** Run, then the whole suite → PASS. Run the renders with `REQUESTIFY_RENDERS=<workspace>/renders` and look at them.
- [ ] **Step 5:** Commit `feat: Over Requestify, an update dot and the updated card`.

## Phase C — the welcome wizard

### Task 9: Phosphor icons and platform logos

**Files:**
- Modify: `ui/common/Icons.kt`, `NOTICE`, `ui/setup/SpotifySetupScreen.kt` (`SpotifyCard` header), `ui/setup/TwitchCard.kt`, `ui/setup/DonationsCard.kt`
- Test: `ui/common/IconsTest.kt` (create or extend)

**Interfaces:**
- Produces, in `AppIcons` (`ImageVector`, 256-unit viewport like `RequestifyGlyph`):
  - `SpotifyLogo`, `TwitchLogo` (Phosphor "fill" weight);
  - `HandHeart`, `CheckBold` (Phosphor "bold" weight; `check` in bold).
  - Paths come from the official Phosphor SVGs (`github.com/phosphor-icons/core`, `assets/<weight>/<name>.svg`). Check the exact path in that repo and copy the `d` strings verbatim.

- [ ] **Step 1: Failing test:** `IconsTest.phosphorIconsParse`. Every new icon builds, has a 256×256 viewport and at least one path.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. `NOTICE` lists the four icon names under the existing Phosphor credit. Each Connections card shows its logo before its title: Spotify green `#1ED760`, Twitch purple `#9146FF`; the donations icon in the theme's tertiary colour.
- [ ] **Step 4:** Run, then the whole suite → PASS. Re-render the Connections screen and look at it.
- [ ] **Step 5:** Commit `feat: Phosphor logos for Spotify, Twitch and donations`.

### Task 10: Wizard logic

**Files:**
- Create: `ui/welcome/WelcomeWizardViewModel.kt`
- Modify: `core/settings/Settings.kt`, `AppGraph.kt` (`welcome: WelcomeWizardViewModel`; the existing-user check before `start()`)
- Test: `ui/welcome/WelcomeWizardViewModelTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Consumes:
  - `SettingsStore`;
  - `spotifySetup.state` (connected ⇔ `auth == SpotifyAuthStatus.LoggedIn`);
  - Twitch connected ⇔ `settings.twitch.account != null`;
  - donations connected ⇔ `donationsSetup.state`'s `kind != None && hasKey && test != DonationTest.Refused` (check the exact names in `DonationsSetupViewModel`).
- Produces:
  - `@Serializable enum class WizardStep { @SerialName("welcome") Welcome, @SerialName("spotify") Spotify, @SerialName("twitch") Twitch, @SerialName("donations") Donations, @SerialName("done") Done }`.
  - `enum class WizardPlatform { Spotify, Twitch, Donations }`.
  - `Settings.welcomeDone: Boolean = false`, `Settings.welcomeStep: WizardStep? = null`.
  - `data class WizardState(val visible: Boolean, val step: WizardStep, val connected: Set<WizardPlatform>, val later: Set<WizardPlatform>, val celebrating: WizardPlatform?, val remaining: Int)`.
  - `class WelcomeWizardViewModel(settings: SettingsStore, spotifyConnected: StateFlow<Boolean>, twitchConnected: StateFlow<Boolean>, donationsConnected: StateFlow<Boolean>, scope: CoroutineScope)`, with:
    - `val state: StateFlow<WizardState>`;
    - `fun begin()`, `fun later()` (also used for the donations choice "Geen"), `fun skipAll()`;
    - `fun celebrated()` (the UI calls it when the celebration ends; it advances to the next step that is not connected);
    - `fun finish()`, `fun reopen()`.
  - `companion fun markExistingUser(settings: SettingsStore, anyConnectionSaved: Boolean)`. It runs once at start: `!welcomeDone && welcomeStep == null && anyConnectionSaved` → `welcomeDone = true`.
    - `anyConnectionSaved` comes from what is saved: a valid Spotify Client ID with a stored Spotify token, a Twitch account, or a stored donation key.

- [ ] **Step 1: Failing tests:**
  - `aNewUserSeesItFromTheWelcomeCard`;
  - `anExistingUserNeverSeesItUnasked`;
  - `aConnectedPlatformIsSkipped`;
  - `connectingCelebratesThenAdvances`;
  - `laterMovesOnWithoutABadge`;
  - `aHalfFinishedWizardResumes` (`welcomeStep` saved; a new view model starts there);
  - `skipAllAndFinishEndIt`;
  - `reopenStartsAgainAtWelcome`;
  - `remainingCountsWhatIsLeft`;
  - pins for `welcome_done`, `welcome_step` and the step names.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: welcome wizard steps, resume and existing users`.

### Task 11: Wizard screens and copy

**Files:**
- Create: `ui/welcome/WelcomeWizard.kt` (layout, cards, progress dots, badge row)
- Modify:
  - `ui/setup/SpotifySetupScreen.kt`, `ui/setup/TwitchCard.kt`, `ui/setup/DonationsCard.kt`: extract each card's body into an `internal` content composable that the card and the wizard both use. The behaviour on the Connections page must not change.
  - `ui/AppScaffold.kt`: while `welcome.state.visible`, the wizard replaces the rail and the screens under the title bar.
  - `AppGraph.kt`: `AboutViewModel(…, openWizard = welcome::reopen)`.
  - Both `strings.xml`, `Renders.kt`.
- Test: `ui/welcome/WelcomeWizardTest.kt`. The existing `SpotifySetupScreenTest`, `TwitchCardTest` and `DonationsCardTest` must stay green unchanged.

**Interfaces:**
- Consumes: Tasks 9, 10; the setup view models.
- Produces:
  - `@Composable fun WelcomeWizard(vm: WelcomeWizardViewModel, spotify: SpotifySetupViewModel, twitch: TwitchSetupViewModel, donations: DonationsSetupViewModel)`;
  - the internal content composables `SpotifySetupContent`, `TwitchSetupContent(prominentCode: Boolean)` and `DonationsSetupContent`. `TwitchSetupContent` keeps the Channel Points section for Affiliates and Partners, as the card has it;
  - test tags `wizard`, `wizard-card-<step>`, `wizard-badge-<platform>`.
- **Layout** (spec section 5 and the mockup):
  - one card centred, at most 600 dp wide, scrolling inside;
  - progress dots top left, connected badges top right;
  - "Later doen" under each platform card; "Overslaan" on the welcome card;
  - the Twitch device code at `displaySmall` or larger;
  - the Klaar summary with account names and "Kan later, in Koppelingen", the tray and autostart line, and "Requestify openen" (`finish()`);
  - it must fit the window's minimum size.
- **Copy** (NL / EN; any line not listed is the builder's, in the same tone):
  - "Hé! Welkom bij Requestify" / "Hey! Welcome to Requestify";
  - "Let's go" / "Let's go";
  - "Overslaan" / "Skip";
  - "Later doen" / "Later";
  - "Yes! %1$s doet mee" / "Yes! %1$s is in";
  - "als %1$s" / "as %1$s";
  - "Nog %1$d te gaan" / "%1$d to go";
  - "Dat was de laatste!" / "That was the last one!";
  - "Klaar om te knallen!" / "Ready to rock!";
  - "Kan later, in Koppelingen" / "You can do this later, in Connections";
  - "Requestify start voortaan met Windows en wacht in het systeemvak tot je live gaat." / "Requestify now starts with Windows and waits in the system tray until you go live.";
  - "Requestify openen" / "Open Requestify".

- [ ] **Step 1: Failing tests** (`runComposeUiTest`, real view models over fakes as the existing setup tests do):
  - `theWelcomeCardLeadsToSpotify`;
  - `laterSkipsToTheNextCard`;
  - `aConnectedPlatformShowsAsABadge`;
  - `skipEndsTheWizardAndShowsTheApp`;
  - `theDoneCardSummarises`;
  - `itFitsTheMinimumWindow`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Extract the content composables first, and run the three existing setup test classes before going on.
- [ ] **Step 4:** Run, then the whole suite → PASS. Render every card (light, dark, narrow) and look at them.
- [ ] **Step 5:** Commit `feat: the welcome wizard, one card at a time`.

### Task 12: Wizard motion and reduced motion

**Files:**
- Create: `desktop/ReducedMotion.kt`, `ui/welcome/WizardMotion.kt` (confetti, flight, moving shapes, plop, dance, check pop)
- Modify: `ui/welcome/WelcomeWizard.kt`, `ui/theme/RequestifyTheme.kt` (`LocalReducedMotion`), `Main.kt` (provide it), `Renders.kt`
- Test: `ui/welcome/WizardMotionTest.kt`, `desktop/ReducedMotionTest.kt`

**Interfaces:**
- Consumes: Task 11's tags; `WizardState.celebrating`, `celebrated()`.
- Produces:
  - `fun windowsAnimationsOff(): Boolean`: `SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION = 0x1042)` through a small JNA `StdCallLibrary`, like `MessageBoxApi`. It is false when the call fails.
  - `val LocalReducedMotion = staticCompositionLocalOf { false }`.
  - Test tags `wizard-confetti`, `wizard-flyer`, `wizard-shapes`.
- **Motion** (spec Decision 3; the mockup's timings are guidance):
  - cards enter with a slight tilt and a spring;
  - the card squashes when a platform connects, and buttons spring when pressed;
  - the check pops and morphs its shape (`MaterialShapes`);
  - a confetti burst, about 1.3 s, on each connection and on Klaar;
  - the platform logo flies from the card header to its badge (the positions from `onGloballyPositioned`), and the badge wiggles when it lands;
  - soft shapes behind the card slowly morph (the theme's primary, tertiary and secondary at low alpha);
  - the Twitch code's characters pop in one by one;
  - the Klaar card's logos dance;
  - progress stays dots: no music notes;
  - `celebrated()` is called when the celebration ends (about 2 s).
- **Reduced motion:** with `LocalReducedMotion` true, none of the above runs. The cards crossfade, and `celebrated()` is called at once.

- [ ] **Step 1: Failing tests.**
  - `WizardMotionTest.aConnectionStartsTheCelebration`: confetti and flyer exist while celebrating, then disappear, and `celebrated()` is called (`mainClock.autoAdvance = false`, advance about 2.5 s).
  - `reducedMotionSkipsTheCelebration`: no confetti, flyer or shapes, and `celebrated()` is called at once.
  - `ReducedMotionTest.aFailingCallMeansAnimationsOn` (inject the native call).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Respect `LocalReducedMotion` everywhere in `WizardMotion.kt`.
- [ ] **Step 4:** Run, then the whole suite → PASS. Render key frames (a card entering, mid-confetti, the badge landing, Klaar) with a paused clock, light and dark, and look at them.
- [ ] **Step 5:** Commit `feat: the wizard's playful motion, and calm when Windows' animations are off`.

## Phase D — releasing

### Task 13: Release workflow, README, secret check, manual checklist

**Files:**
- Create: `.github/workflows/release.yml`, `.github/workflows/ci.yml`, `docs/superpowers/plans/2026-11-19-requestify-plan-5-manual-checks.md` (Dutch)
- Modify: `README.md`, `NOTICE` (if Task 9 did not finish it)

**Interfaces:**
- Consumes: Tasks 2 and 3 (versions, the custom WiX and the MSI/EXE tasks); the asset names in Global Constraints.

- [ ] **Step 1: `release.yml`.** On push of tags `v*`, on `windows-latest`:
  1. `actions/checkout`;
  2. `actions/setup-java` (Temurin 21, for running Gradle; the toolchain still fetches JBR 21 through foojay);
  3. validate the tag against `^v(\d{1,3})\.(\d{1,3})\.(\d{1,5})$` and the range limits;
  4. `./gradlew.bat jvmTest` plus the MSI and EXE tasks with `-Prequestify.version=<tag without v>`;
  5. rename the outputs to `Requestify-Setup-<v>.exe` and `Requestify-<v>.msi`;
  6. a commented-out **signing** step, marked `# Signing slot (plan 5 Decision 6)`;
  7. write `SHA256SUMS.txt` (lowercase hex, two spaces, file name);
  8. create a **draft** release named `Requestify <v>`, with the three assets (`gh release create --draft`, using `GITHUB_TOKEN`; `gh` is on the runner).
  
  `ci.yml`: `jvmTest` on pushes and pull requests to `main`.
- [ ] **Step 2: README.**
  - A Dutch section **"Installeren"** for friends:
    1. **First** the Smart App Control check (Windows-beveiliging → App- en browserbeheer → Slimme app-beheer; Requestify does not run while it is "Aan");
    2. then the download link (the latest release);
    3. then "Windows heeft uw pc beschermd → Meer info → Toch uitvoeren";
    4. then what happens next: the wizard, autostart and updates that never come during a stream.
  - An English section with the same content.
  - The existing build and test instructions stay.
  - A "Releasing" section for the user: tag, wait for the workflow, write the notes, publish (pre-release for tests), and every version only once.
- [ ] **Step 3: Secret check of the full history.** No tool downloads.
  - Search all commits for likely secrets: `git log -p --all`, grepped for `refresh_token`, `access_token`, `oauth:`, `eyJ` (JWTs), `Bearer `, `client_secret`, 32+-hex runs other than the known public Twitch Client ID `wu2hg6lvxw5lqb2qbs046ou19nupnp`, `.json` files with `"key"`, the user's e-mail address and the `%APPDATA%` file names.
  - Write the findings to the session notes folder (`plan-5-secret-check.md`).
  - **Do not rewrite history.**
- [ ] **Step 4: The manual checklist** (Dutch), covering:
  - uninstalling the old per-machine 0.1.0;
  - the README's Smart App Control check on the user's own PC;
  - a local build per user, without a prompt, starting after install;
  - the wizard from nothing, with a fresh `%APPDATA%` by renaming the old folder and putting it back afterwards;
  - the existing data not getting the wizard;
  - the first real release `v0.2.0` from a tag;
  - an update from `v0.2.0` to `v0.2.1` (both real releases), including a pre-release `v0.2.2` that is *not* offered (and *is* offered with `REQUESTIFY_UPDATE_PRERELEASES=1`);
  - "Nu bijwerken" disabled while live;
  - a start within 10 minutes of a stream not installing;
  - an uninstall removing autostart and keeping the data, and a reinstall bringing it back;
  - an update keeping autostart.
- [ ] **Step 5:** The whole suite → PASS. Commit `chore: release workflow, README install guide and the manual checklist`.
- [ ] **Step 6: STOP and ask the user.** Report the secret-check findings. Explain the outward steps, and do none of them until the user says so in that moment:
  1. create the empty public repository `DIMENTS/Requestify` and turn on MFA;
  2. `git remote add origin …` and `git push -u origin main`;
  3. push tag `v0.2.0`;
  4. write the notes and publish the draft.
