# Requestify plan 5 — release to friends: installer, self-update, welcome wizard

- **Date:** 2026-11-18
- **Status:** design, awaiting the user's approval
- **Parent design:** `2026-11-16-requestify-design.md` (Distribution, Setup and reliability, "Left to the builder": updater/packaging and signing)
- **Previous plan:** `2026-11-18-requestify-plan-4-background-design.md` (tray, autostart, single instance, crash restart; plan 4 is on `main` at `1217d1f`)
- **Research behind the packaging choice:** a web research pass on 2026-11-18 (sources and dates in the session notes, `plan-5-research.md`); the facts this design relies on are repeated under Constraints.
- **Wizard mockups:** `assets/plan-5-wizard/wizard-b-playful.html` (the chosen direction, playful, with its elements switchable) and `assets/plan-5-wizard/wizard-directions.html` (the three directions compared). Open them in a browser; the "connect" buttons are simulated.

## Intent

The user wants to give Requestify to **a few streamer friends first** (a public release comes later), and **"installeren moet makkelijk gaan"**. Requestify is free and open source (MIT, `LICENSE` exists) and will live on GitHub.

Success looks like this:
- A friend gets one link (the GitHub release page), downloads one file, installs it without an admin prompt and without questions.
- Requestify opens on a playful welcome wizard that gets Spotify, Twitch and donations connected.
- From then on it keeps itself up to date, and **never interrupts a stream** to do so.

## Goals

1. A public GitHub repository and releases built automatically from a version tag.
2. A per-user installer: no admin prompt, no questions, the app starts when it is done, and uninstalling cleans up "start with Windows".
3. Self-update from GitHub Releases, never during a stream.
4. A welcome wizard for new streamers: direction **B (one card at a time), playful**.
5. Settings → **Over Requestify** (About): version, update check, links.
6. Three deferred plan-4 minors that touch start and exit: M-5, M-11, M-13.

## Non-goals

- **Code signing.** Friends get an unsigned build; the release flow keeps one empty slot for a signing step (Decision 6).
- Microsoft Store, MSIX, Conveyor (Approaches).
- Delta updates, update channels, rollback to an older version.
- Installing an update while the app keeps running offline. It installs only at a start or on the streamer's click (Decision 5).
- Crash-report upload or any telemetry.
- Signing the update metadata with a key of our own. Trust rests on the GitHub account (multi-factor authentication is recommended).
- Phosphor icons for the whole app. They are used in the wizard and for the platform logos (Decision 4).
- The remaining deferred plan-4 minors (M-1–M-4, M-8–M-10, M-12, M-14–M-17).
- macOS and Linux.

## Anti-goals (a failure even if everything "works")

- An update that stops Requestify during a stream, pausing requests or Channel Points mid-stream.
- A friend stuck at installing with nothing on screen. That happened to the user on 2026-11-18: a same-version reinstall made Windows Installer error 1638 and the installer hung without a window.
- Two copies of Requestify installed side by side, or a reinstall or update that loses connections, settings or the queue.
- A wizard with its own copy of the setup logic that drifts from the Connections page.
- A notification for an update. Plan 4's rule stands: notifications are only for "action needed".

## Constraints and facts

**Installer (jpackage through Compose):**
- jpackage derives the MSI ProductCode from a hash of vendor, name and version. A rebuilt installer with the same version therefore cannot install over an installed copy (error 1638). **Every installer needs a new version.** Windows limits the parts to MAJOR ≤ 255, MINOR ≤ 255, BUILD ≤ 65535.
- jpackage's MSI always does a major upgrade, and allows downgrades. It removes the old version completely before installing the new one (`RemoveExistingProducts` before `CostInitialize`).
- `perUserInstall` installs to `%LOCALAPPDATA%\Requestify` with no admin prompt. Per-user and per-machine installs are separate to Windows Installer, so the choice must not change between releases.
- Compose 1.12.2's Windows DSL has `perUserInstall`, `upgradeUuid`, `menu`, `menuGroup`, `shortcut`, `dirChooser`, `console`, `iconFile`, `msiPackageVersion` and `exePackageVersion`.
  - Its jpackage task has a `jpackageResources` input that it passes as `--resource-dir`. This was seen in the plugin's classes.
  - **Unverified:** whether a build script can put a custom `main.wxs` there. The fallback is to run jpackage ourselves on the app image Compose builds.
- The current installer has no vendor; the Windows event log shows "Fabrikant: Unknown".
- The user's own copy (0.1.0) is installed **per machine** in `C:\Program Files\Requestify`.

**Unsigned builds:**
- A browser download gets the Mark-of-the-Web, so the first run shows "Windows heeft uw pc beschermd" (More info → Run anyway).
- SmartScreen reputation is kept per file hash, and every new unsigned version starts from zero.
- **Smart App Control** on Windows 11 blocks unsigned files without reputation, with no override.
- A file the app downloads itself, over Java HTTP, carries no Mark-of-the-Web. So the **self-update gets no SmartScreen prompt** (Defender still scans it).

**GitHub:**
- `GET /repos/{owner}/{repo}/releases/latest` returns only the newest published release that is not a pre-release (drafts are excluded).
- Unauthenticated calls are limited to 60 an hour per IP.
- The `gh` CLI is not installed and the repository has no remote yet.

**Build:** JetBrains Runtime 21 comes through the Gradle toolchain and the foojay resolver, so it works on a CI runner too. Gradle is 8.14.4.

**Plan 4, which plan 5 must not break:**
- Autostart is the HKCU `Run` value `Requestify`, plus its `StartupApproved` value. `RunKeyAutostart.repairPath()` rewrites the path when the exe moves.
- The crash restart starts the launcher exe from `jpackage.app-path` with `--after-crash`.
- App data lives in `%APPDATA%\Requestify`.
- A second start waits for the single-instance lock or hands over to the running copy.
- Notifications are only for "action needed".
- `RequestsGate` exposes whether requests are open and whether the stream is live.
- The version shown in crash files is `jpackage.app-version` (`"dev"` under `gradlew run`).

## Approaches considered

**Packaging and updates** (the user chose **1**):

1. **Keep jpackage, add a small updater of our own (chosen).**
   - A per-user MSI, plus an updater that downloads the next MSI from GitHub and installs it when Requestify is not streaming.
   - Plan 4 stays exactly as it is. It costs nothing.
   - The streamer controls *when* an update installs.
   - Costs: we write the updater and a custom installer script, and an unsigned build shows SmartScreen once, at the first install.
2. **Conveyor (MSIX, Windows updates the app).**
   - Free for open source, with background delta updates.
   - Without real signing there is no MSIX, and self-signed means an admin PowerShell step for every friend. The signing services Conveyor supports are paid for a Dutch individual (Azure Artifact Signing is for US and Canada individuals only).
   - MSIX would also change plan 4: autostart becomes a StartupTask, AppData is virtualised, and the install path changes per version.
3. **Microsoft Store.**
   - Easiest for friends: no warning, a free individual account, and the Store updates the app.
   - But every release goes through Microsoft's review, plan 4 changes as in 2, and the Store decides when to update. "Never during a stream" cannot be guaranteed.

**Wizard** (the user chose **B, playful**):
- Three directions were mocked up and clicked through: A (route on the left, step on the right), B (one card at a time) and C (everything on one page, folding shut).
- Then B was made more playful with eight elements that could be switched on and off; the user kept seven.

## Decisions (the user's)

1. **Audience:** a few streamer friends first; installing must be easy.
2. **Packaging:** approach 1, jpackage with our own updater.
3. **Wizard:** direction B, playful, with these elements: confetti, the flying logo, moving shapes, springy cards, the plopping Twitch code, dancing logos and more cheerful copy. **Not** the music notes: progress stays as dots.
4. **Icons:** Phosphor ("De icons mag je dus Phosphor voor gebruiken").
5. **Updates never during a stream.** The app checks at start and every 6 hours and downloads silently. It installs only at the next start, or when the streamer clicks "Nu bijwerken" while offline, and then restarts itself.
6. **Unsigned for friends.** Signing is added later, for the public release (SignPath Foundation or Certum Open Source, chosen then). The release flow is built so that signing is one extra step.
7. **Uninstalling** removes "start with Windows" and keeps the data, so a reinstall brings everything back.
8. **Plan-4 minors M-5, M-11 and M-13** are part of this plan; the others stay parked.
9. A release marked **pre-release** does not reach friends, so the user can test it first.
10. After an update, a one-time card "Bijgewerkt naar X" shows what is new. Settings gets an "Over Requestify" section.

11. **Smart App Control** (Windows 11) blocks an unsigned Requestify completely, both installing it and starting it, with no "Run anyway". Friends check it first. The README and release text explain how (Windows-beveiliging → App- en browserbeheer → Slimme app-beheer) and that Requestify does not run while it is on. Plan 5 stays unsigned; signing comes forward only if a friend has it on.

Decisions 5–10 were proposed in the conversation and approved by the user ("Klopt allemaal"). Decision 11 is the user's answer to a question from the builder check.

## Design

### 1. Repository and releases

- **The user** creates the public repository `DIMENTS/Requestify` (empty) and turns on multi-factor authentication: friends trust whatever that account publishes.
- **Before the first push**, the full git history is checked for secrets: tokens, keys, refresh tokens or personal data in fixtures or docs.
  - The Twitch Client ID `wu2hg6lvxw5lqb2qbs046ou19nupnp` is public by design and stays.
  - If anything must go, the history is rewritten only with the user's explicit consent, because that is irreversible for a published repo.
  - Pushing is an outward step and needs the user's go-ahead at that moment.
- **Release workflow (GitHub Actions, Windows runner)**, started by pushing a tag `v<MAJOR>.<MINOR>.<BUILD>` (numbers only):
  1. Run the tests.
  2. Build the MSI and the EXE with the tag's version.
  3. *(Slot: sign; empty for now.)*
  4. Compute SHA-256 checksums.
  5. Create a **draft** GitHub release named after the tag, with these assets:
     - `Requestify-Setup-<version>.exe`, the file friends download;
     - `Requestify-<version>.msi`, the file the updater uses;
     - a checksum file covering both.
- **The user** writes the "what's new" text in the draft and publishes it, ticking **pre-release** for a test build. Pre-releases are invisible to friends (Decision 9).
- Every release, pre-release or not, has its own number. A test build `v0.3.0` that turns out fine is followed by `v0.3.1`; the same number is never released twice.
- Tests on every push to `main` are the builder's call (cheap for a public repository).
- **Local builds** (`.\gradlew.bat packageExe` on the user's PC):
  - They must never fall into the 1638 trap: every local build gets a version that has not been installed on that PC before.
  - They are recognisable as local, so their updater stays off and a local test build is not replaced by the latest release on its next start.
  - The scheme is the builder's call within those two rules; one example is `0.0.<BUILD>` with a BUILD that grows with every build.

### 2. Installer

- Built by Compose's jpackage tasks:
  - `perUserInstall = true`;
  - a fixed `upgradeUuid` (a new GUID, set once and never changed);
  - vendor `DIMENTS`;
  - a Start menu entry and a desktop shortcut;
  - the installer icon (`.ico`) from the same source as the tray and window icon (plan 4);
  - no folder chooser and no questions.
- **Friend's first install:** after installing, Requestify starts by itself, so the wizard appears. A start that the updater causes must not start a second copy (the single-instance lock would hand over, but no double start is better).
- **Uninstall** (Settings → Apps):
  - Removes the HKCU `Run` value `Requestify` and its `StartupApproved` value.
  - Does this **only on a real uninstall, never during an upgrade**; otherwise every update would wipe the streamer's autostart choice.
  - Keeps `%APPDATA%\Requestify`.
  - **A reinstall brings autostart back exactly as it was** (Decision 7):
    - The uninstall leaves a small marker in `%APPDATA%\Requestify` saying that it removed the entry, and in which state: on, or disabled in Task Manager.
    - The first start after a reinstall restores that state and deletes the marker.
    - Without a marker, plan 4's rule stands: an entry the streamer removed is never re-created unasked.
    - *builder's call*, after the builder check found that today's code would silently leave autostart off after a reinstall.
  - Mechanism: a custom action in a custom `main.wxs`, through Compose's `jpackageResources` if a build script can reach it, otherwise jpackage run on Compose's app image. The builder's call.
- **The SmartScreen step** ("Meer info → Toch uitvoeren") is explained in the README and in the release text, as plain words. So is the Smart App Control check (Decision 11), placed *before* the download link, so a friend whose PC would block Requestify knows before trying.
- **The user's own per-machine 0.1.0** is uninstalled once by hand. This is a step in the manual checklist. Friends never had it.

### 3. Self-update

**Active only in an installed release build.** It is off under `gradlew run` (`jpackage.app-version` absent) and in local builds (section 1).

**Checking:**
- When: shortly after start (so it does not compete with connecting), then every **6 hours**, and on "Zoeken naar updates".
- How: `releases/latest` of `DIMENTS/Requestify`, comparing versions numerically.
- Failures are quiet: no network, GitHub down, rate limited or an odd response is tried again at the next check. Update trouble is **not** a connection problem: no chip, no banner, no notification.

**Downloading:**
- Only a version newer than the running one, with its MSI and checksum assets.
- The download goes to a local, non-roaming folder outside the install folder and outside `%APPDATA%`. The exact folder is the builder's call; it is cleaned after an install.
- It is verified against the published SHA-256. A mismatch deletes the file and is logged, and the next check tries again.
- At most one pending update: a newer release replaces an older pending download.

**Installing, only at these two moments (Decision 5):**
1. **At a start**, if a verified update is waiting:
   - It happens before anything connects (no Twitch session, no Spotify hand-over).
   - Requestify starts the install and ends; that is an orderly end, not a crash.
   - A start by Windows (autostart) counts.
   - **A crash restart (`--after-crash`) never installs.** It must come back fast, and may be mid-stream.
   - **A start during a stream does not install.** `stream.json` says whether the stream was live and when Requestify last saw it live: the heartbeat writes that every minute while live, and Quit writes it too (`RequestsGate.loadedLastSeenLiveMs` exposes it at start, before anything connects). If the stream was live less than the gate's 10-minute grace ago, the install waits for a later start or for "Nu bijwerken".
   - *builder's call (after the builder check, from Decision 5):* a stream that started while Requestify was off is not visible at start. Installing then takes well under a minute, and Spotify keeps playing meanwhile.
2. **"Nu bijwerken"**, clicked by the streamer:
   - It is enabled only while the stream is not live and requests are not open (`RequestsGate`); while it is disabled, a short line says why.
   - It runs the same graceful stop as Quit (Channel Points reward paused, saves flushed), then installs.

**The install itself:**
- A silent per-user `msiexec` install of the downloaded MSI, with a log file. The MSI major upgrade removes the old version.
- After success **the new version starts by itself**, in the same visibility as before: tray-only if the old copy was started hidden (autostart or after a crash), otherwise with the window.
- The relaunch mechanism is the builder's call: a small helper that waits for `msiexec`, or a launch action in the MSI. It must respect:
  - the single-instance lock (the old process is gone first);
  - the crash guard (the update exit is orderly, and the relaunch is not `--after-crash`);
  - M-13 (no inherited stdio).
- **If the install fails:**
  - The old version stays (MSI rollback) and must start again by itself.
  - The failure goes to the Log (kind App, with the `msiexec` exit code).
  - The same version is retried automatically at most once.
  - After that, Settings → Over shows "Bijwerken mislukt", with a link to the release page for a manual download.

**What the streamer sees:**
- **While an update waits:** Settings → Over Requestify says so and offers "Nu bijwerken". A small, quiet indicator elsewhere is allowed (for example a dot on the Instellingen item in the navigation rail), but no problem banner, no entry in the problem chip and no notification. The builder decides the indicator and checks it in the renders.
- **After an update:** the first start of the new version writes "Bijgewerkt van X naar Y" to the Log, and shows **once** a dismissable card "Bijgewerkt naar Y" on Home with the release's text.
  - The release text is Markdown; showing it as clean plain text (headings and bullets readable) is enough.
  - The last shown version is kept in the settings.

### 4. Settings → Over Requestify

- The version.
- "Zoeken naar updates", with its result: up to date, ready, or failed.
- "Nu bijwerken" when an update waits, disabled while live (as in section 3).
- A link to the GitHub release page.
- The licence and the NOTICE credits.
- "Welkomstwizard openen".

### 5. Welcome wizard (direction B, playful)

**When it appears:**
- At the first time the window opens in an installation where the wizard is not done. "Done" is kept in the settings and set by finishing or skipping it.
- A start hidden in the tray (autostart) shows it when the window is opened.
- **Existing users:** if any connection already exists when a plan-5 version first starts, the wizard counts as done and is never shown unasked. This covers the user's own install.
- It can be opened again from Settings → Over.
- Closing the window halfway resumes at the same card next time, until it is finished or skipped.

**Layout** (see the mockup):
- One large card centred in the window. No navigation rail; the title bar stays.
- Above the card: the progress dots on the left, and the badges of connected platforms on the right.

**Cards:** Welcome → Spotify → Twitch → Donaties → Klaar.
- A platform that is already connected shows as a badge from the start, and its card is skipped.
- "Later doen" skips a card. "Overslaan" on the welcome card ends the wizard.
- **Spotify, Twitch and Donaties use the Connections page's logic**: the same view models (`SpotifySetupViewModel`, `TwitchSetupViewModel` with the Channel Points reward for Affiliates and Partners, `DonationsSetupViewModel`) and the same live tests.
  - There is no second copy of the setup logic.
  - The builder may split today's cards so that header and body are shared between the Connections page and the wizard.
- **Twitch:** the device code is shown large.
- **Donaties:** the choice StreamElements / DonoLink / Geen.
- **Klaar:**
  - What is connected (with account names) and what can be done later ("Kan later, in Koppelingen").
  - That Requestify starts with Windows and waits in the tray until the streamer goes live.
  - "Requestify openen".

**Playful elements (Decision 3):**
- **Confetti** when a platform connects and on the Klaar card.
- **Flying logo:** the platform's logo flies from the card to the badge row and wiggles when it lands.
- **Moving shapes:** soft colour shapes behind the card that slowly morph.
- **Springy cards:** cards tilt in, the card squashes when a platform connects, and buttons spring when pressed.
- **Plopping Twitch code:** its letters pop in one by one.
- **Dancing logos** on the Klaar card.
- **Cheerful copy** in Dutch and English. Dutch examples from the mockup:
  - "Hé! Welkom bij Requestify"
  - "Let's go"
  - "Yes! Spotify doet mee"
  - "Nog 2 te gaan" / "Dat was de laatste!"
  - "Klaar om te knallen!"
  
  English is the builder's call, in the same tone.
- The connected check pops in and morphs its shape, in the style of Material 3 Expressive.

**Reduced motion:** when Windows' animation effects are off, the wizard shows none of the above motion and crossfades between cards.

**Theme:** the app's theme roles, in light and dark. Before any album art exists, the default seed colour.

**Icons:** Phosphor (MIT; `NOTICE` already credits Phosphor for the app glyph), as `ImageVector`s:
- the Spotify and Twitch logos;
- a donation icon (for example "hand-heart");
- the check and the other wizard icons.

The same platform logos replace the platform marks on the Connections cards (*builder's call*, so a platform looks the same everywhere). The rest of the app keeps its Material icons.

The mockup's texts, colours and timings are guidance, not specification.

### 6. Plan-4 minors

- **M-5:** an exception while the JVM shuts down (Windows sign-out or shutdown) is not a crash: no crash restart, and it is not counted as a crash.
- **M-11:** an exception in the graceful stop during Quit ends the app as Quit intended (logged), without a crash restart.
- **M-13:** a process Requestify starts (the crash restart, and now the update relaunch) does not inherit the parent's stdio.

## Review Focus (inputs the tests are most likely to miss)

1. **Install at start while live.** The streamer quits and restarts Requestify mid-stream with an update waiting: `stream.json` shows the stream as live within the grace, so the install waits. A crash restart never installs. The boundary (exactly 10 minutes, a missing or unreadable `stream.json`) must lean towards *not* installing.
2. **A broken or partial release** (an asset missing, a wrong checksum, a draft published without the MSI): no install, no loop, at most one Log line per version.
3. **A failing `msiexec`** (disk full, an MSI error, the user cancelling a prompt): the old version is back by itself, the Log says why, and there is no endless retry.
4. **Upgrade versus uninstall:** an upgrade keeps the autostart choice; only a real uninstall removes it. A reinstall after uninstall restores every connection.
5. **The wizard and existing users:** someone who already has connections never sees it unasked. A half-finished wizard resumes. An already-connected platform is skipped.

## Testing and verification

- **Unit tests, test first:**
  - version parsing and comparison;
  - parsing the release JSON (fixtures);
  - choosing the assets;
  - checksum verification;
  - the update state machine, including "never while live" (the button), "not at a start within the stream's grace", "never on a crash restart", retry at most once and a newer release replacing a pending one;
  - restoring autostart from the uninstall marker (on, disabled in Task Manager, no marker);
  - the version for which the "Bijgewerkt" card shows;
  - the wizard's step logic (skip connected, later, resume, done, existing users);
  - the local-build rule (no updater).
- **UI tests** (`runComposeUiTest`): the wizard flow on simulated view models; Settings → Over in each update state.
- **Offscreen renders**, light and dark, wide and narrow: every wizard card, the reduced-motion variant, Settings → Over, the update indicator and the "Bijgewerkt" card. As in plan 4, the user's desktop is never driven by scripted clicks.
- **A Dutch manual checklist** for the installed build:
  - removing the user's old per-machine 0.1.0;
  - the README's Smart App Control check, followed on the user's own PC;
  - downloading the EXE from a GitHub release (SmartScreen) and the app starting after the install;
  - the wizard from scratch;
  - an update from release N to N+1 (two real releases), including a pre-release that must *not* be offered;
  - "Nu bijwerken" disabled while live;
  - uninstall removing autostart and keeping data, and a reinstall bringing it back.
  
  For testing on the user's PC, the builder may add a hidden switch (for example an environment variable) that also offers pre-releases.

## Left to the builder

- Package and class names, and the HTTP client (the existing Ktor setup).
- How the custom `main.wxs` gets into the build, and how the relaunch after `msiexec` works (within section 3's rules).
- The version scheme for local builds (within section 1's rules).
- The download folder, and whether `msiexec` shows a small progress window (`/passive`) or none (`/qn`).
- The quiet update indicator, and how the release text is shown.
- The Compose APIs, timings and easing of the wizard's motion, and its English copy.
- Whether a test workflow runs on every push.

Every *builder's call* marked above is open to the user's correction while they read this.
