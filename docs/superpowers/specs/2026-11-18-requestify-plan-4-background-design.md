# Requestify plan 4 — running in the background (design)

Date: 2026-11-18
Status: approved by the user in chat on 2026-11-18 (after the builder check)

Parent design: `docs/superpowers/specs/2026-11-16-requestify-design.md` (sections "Setup and reliability" and
"Distribution"). Plan 2 design: `docs/superpowers/specs/2026-11-17-requestify-plan-2-rules-twitch-design.md`. Plan 3
design: `docs/superpowers/specs/2026-11-18-requestify-plan-3-donations-log-design.md`. Everything those documents
decided still holds unless this one says otherwise.

## Intent and why

The parent design's bar is "set it up once, and never have to look at it or fiddle with it again". Plans 1–3 built every
feature; the user live-tested them ("Alles werkt"). What is still missing is the part that lets the streamer *stop
looking*: Requestify must live quietly in the background, start with Windows, never run twice, tell the streamer only
when they must act, and never make the window stutter.

The parent design's plan 4 was "reliability + release". The user wants both, **background first, release after**. So
this plan is the background half; **plan 5** is the release (installer, automatic updates, signing, GitHub), with its own
brainstorm later.

The app is also for other streamers (free and open source), so everything here must work for a streamer who did not
build it.

## Goals

1. **System tray.** Requestify always has a tray icon; closing the window hides it, everything keeps running.
2. **Windows notifications only when the streamer must act** (user's decision): a dead login, a refused key or token, a
   DonoLink key that is about to expire.
3. **Start with Windows**, on by default, hidden in the tray.
4. **One instance.** Starting Requestify again shows the window that is already running.
5. **A crash restarts the app once and leaves a trace**: a crash file, a notification, and a Log row.
6. **Saving never blocks the window** (user's decision: approach A, below).
7. **The plan-1 reliability leftovers** and **all parked plan-2 and plan-3 minors** (user's decision: "Alles maar doen").

## Non-goals

- Everything of plan 5: installer polish, automatic updates, code signing, the GitHub repository and releases, the
  welcome / first-run wizard for new streamers, removing the autostart entry on uninstall.
- Designing the app icon: **the user supplied it** (decision 9).
- A watchdog process that restarts a **hung** or hard-killed app (too much for what it brings — *builder's call*). A
  crash from a programming error does restart the app once (decision 5).
- Notifications for problems the app fixes itself or that only need watching: short connection drops, Spotify without an
  active device, a rejected paid request (user's decision: only "action needed").
- Native Windows toasts with buttons (WinRT): they need an AppUserModelID that only exists once installed (*builder's
  call*, see Approaches).
- A choice in Settings for what the close button does; it always hides (*builder's call*).
- The known plan-1 limitation stays: tracks queued in Spotify itself play before requests but show under "Then in
  Spotify".

## Anti-goals (failure even if it technically works)

- The streamer thinks Requestify is running while it is not (crashed or never started), or the other way round.
- Two Requestify processes at once (both read and write `%APPDATA%\Requestify`: the files would be corrupted or a
  request handled twice).
- Notifications that nag: the same problem notified again and again, or notifications for things that fix themselves.
- A saved file that is overwritten with a default because it could not be read (plan 1's rule stays).
- A paid donation lost because its cursor reached disk before the request did.
- The window freezing for a disk write, a DPAPI call or an `fsync`.
- An autostart entry that the streamer turned off (in Settings or in Task Manager) coming back on its own.

## Constraints

- Stack as in plans 1–3: Kotlin 2.4, Compose Multiplatform 1.12 on JetBrains Runtime 21, coroutines; the app-lifetime
  scope runs on `Dispatchers.Main` (the AWT event thread) and the code relies on that confinement. Moving the whole app
  off Main is not on the table.
- Today: the queue (`PlaybackController` → `QueueStore`, on `ioDispatcher`) and the Log (`LogStore`) already save off
  the UI thread. Everything else saves synchronously on Main through `JsonFileStore.write` (tmp file, `force(true)`,
  atomic move): `SettingsStore`, `PersistedState` (blacklist, rejected, pending resolves, donation cursors),
  `RequestsGate` (`stream.json`), `RedemptionOutbox` (`redemptions.json`), `DpapiSecretStore` (`secrets.json`). DonoLink
  reads and DPAPI-decrypts its key on every 5-s poll; the StreamElements heartbeat fsyncs.
- `PersistedState.change` / `SettingsStore` return whether the change was saved, and callers rely on "a change that
  cannot be saved is not applied". Approach A changes that rule (see Decisions → Saving).
- `closeApp` (Main.kt) today hides the window, runs `graph.stopGracefully` (pauses the Channel Points reward, at most
  3 s) and exits. That stays the meaning of **Quit**.
- The user runs the app with `.\gradlew.bat run`. Start-with-Windows needs a stable exe path, which only an installed
  build has; it can be tested with a locally built installer (`.\gradlew.bat packageExe`), which Windows does not flag
  with SmartScreen because it was not downloaded.
- **Packaging is plan 5's choice** (parent design: "Conveyor … or jpackage + own updater"). Plan 4 builds on what exists
  today, Compose's jpackage `packageExe` (launcher exe, `jpackage.app-path`, `%APPDATA%\Requestify` shared with
  `.\gradlew.bat run`). *builder's call* after the builder check: "is this an installed build, and where is its
  launcher", autostart, and the crash-restart sit behind **one small interface**, so plan 5 can swap them if it picks
  MSIX/Conveyor (there autostart is a manifest `StartupTask`, the install path changes with every version, and AppData
  writes may be redirected — the last one concerns the whole app, not only plan 4, and is plan 5's to settle).
- `connectionProblems()` (`ui/common/ConnectionChip.kt`) is the one list of "what is wrong right now" behind the title-bar
  chip. The tray icon and the notifications follow the same list (see Approaches).
- No app icon existed in the repo; the user supplied one during the brainstorm (decision 9).

## Approaches considered

### Saving off the UI thread (the user picked A)

| | Approach | Trade-off |
|---|---|---|
| **A** (chosen) | **Apply at once, save in the background.** A change is applied in memory immediately; a writer per file saves the newest value off the UI thread, retries when it fails, and shows the problem (banner + Log) until it works again. This is how the queue already works. | The window never waits for the disk; one pattern for every file. Cost: "not applied when not saved" becomes "applied, saving retried, problem visible", and the order between files must be guarded where it matters (donation cursor after its request). |
| B | Save first (off the UI thread), then apply. Keeps today's rule. | Every caller becomes asynchronous; a change shows a fraction of a second late; more code changes. |
| C | Only move the hot spots (DonoLink key read, cursor saves, SE heartbeat). | Least work, but the rest of the stores still block; does not meet goal 6. Dropped. |

### Tray and notifications (*builder's call*)

- **Compose's own `Tray` / `TrayState.sendNotification`** (AWT `TrayIcon` underneath), chosen: works from
  `.\gradlew.bat run` and installed alike, no extra dependency. Its notifications are plain (title + text, a click on
  it activates the tray icon's action); fine for "action needed".
- Native WinRT toasts (via JNA/COM): buttons and richer layout, but need an AppUserModelID registered through a Start-menu
  shortcut, i.e. only after installing. Dropped for now.
- **What triggers a notification:** the tray layer *listens* to the same problem list as the connection chip (as the Log
  listens in plan 3), rather than each service raising notifications itself. One place decides; a new connection gets
  notifications automatically.

### Start with Windows (*builder's call*)

- **The per-user Run key** (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`), chosen: no admin rights; Windows
  shows it in Task Manager → Startup, where the streamer can switch it off (Windows records that under
  `…\Explorer\StartupApproved\Run`, which the app must respect).
- A shortcut in the Startup folder or a scheduled task: work too, but the Run key is the standard and the easiest to
  keep in step with Task Manager.

### One instance (*builder's call*)

- **A lock in `%APPDATA%\Requestify`** (an OS file lock held for the app's life) plus a small way for a second start to
  ask the first to show its window (e.g. a loopback socket on `127.0.0.1` whose port is in a file next to the lock),
  chosen: plain Java, testable, no JNA.
- A Windows named mutex + window message via JNA: works, but more native code and harder to test.

## Decisions

### 1. System tray (user accepted the proposal)

- Requestify always shows a tray icon while it runs, from the moment the app starts.
- **Closing the window (X) hides it**; Spotify polling, Twitch, donations, the gate and the Log keep running.
- **Tray menu:** a first, disabled line with the status ("Alles gekoppeld" / the chip's top problem), then **Openen**
  (Open) and **Afsluiten** (Quit). *builder's call* on the status line.
- **A click** on the tray icon opens the window (shows it, restores it if minimised, brings it to the front).
- **Afsluiten** = today's close: hide, graceful stop (reward paused, at most 3 s), exit.
- **First close hint:** the first time ever the streamer closes the window, one notification "Requestify draait door in
  het systeemvak" / "Requestify keeps running in the system tray"; never again (remembered in settings). The user was
  told and did not object.
- **The icon shows trouble:** when the problem list is not empty (anything the chip would show), the tray icon gets a
  small warning mark and its tooltip names the top problem. The mark is drawn over whatever icon is in use, so the
  user's icon works without extra variants.
- Hiding the window must not stop anything that lives in the app graph; only composition stops.
- Settings' hints that say a change applies "after a restart" (language) must make clear that a restart now means
  *Afsluiten* in the tray menu and starting again.

### 2. Notifications (user's decision: only "action needed")

A Windows notification appears when one of these **starts**:

| Problem (from the chip's list) | Notification |
|---|---|
| Spotify login stopped working (`SpotifyHealth.LoginNeeded`) | "Spotify-login verlopen — log opnieuw in" |
| Twitch login stopped working (`TwitchLoginNeeded`) | "Twitch-login verlopen — log opnieuw in" |
| DonoLink key / StreamElements token refused (`KeyRefused`) | "DonoLink-sleutel geweigerd — maak een nieuwe" / SE equivalent |
| DonoLink key expires within 7 days (`KeyExpiring`) | "DonoLink-sleutel verloopt over N dagen" |
| … and again on its last day (≤ 1 day left) | "DonoLink-sleutel verloopt morgen/vandaag" |
| Spotify Premium missing (`PremiumRequired`) — *builder's call* | "Requestify heeft Spotify Premium nodig" |
| Channel Points reward removed on Twitch (`RewardRemoved`) — *builder's call* | "De Requestify-beloning is verwijderd op Twitch" |

| Saving a file keeps failing for **2 minutes** (decision 6) — *builder's call* | "Requestify kan niet opslaan — controleer de schijf" |
| Requestify restarted after a crash (decision 5) | "Requestify is herstart na een fout" |

Premium, the removed reward and the save outage are *builder's call*, applying the user's rule "only what the streamer
must fix": only the streamer can fix them. The save outage waits 2 minutes so a short file lock (virus scanner, backup)
that fixes itself never notifies. The crash-restart notification is the user's decision 5.

- **Once per problem:** a problem notifies once when it starts; it notifies again only after it went away and came
  back. The two expiry notifications are each sent once per key (a new key starts over). What was already notified
  survives a restart where it matters (the expiry notifications: so a streamer is not told "6 days" at every start);
  a login or key problem that is present at start is notified once at that start, because the app may have started
  hidden.
- **Not notified:** `LoggedOut`, `NoKey` (deliberate states), connection drops, no active device, Spotify unreachable,
  rejected requests, gaps, a save outage shorter than 2 minutes — they show in the tray icon, the chip, the banner and
  the Log only.
- **Click** on a notification opens the window on **Koppelingen** (Connections).
- **While live** notifications show as well (that is when acting matters); Windows' own Do Not Disturb rules apply.
- Text in the app language (nl/en). Exact texts are the builder's.
- **Key expiry is re-evaluated as time passes** (today it is only computed when health changes — a parked plan-3 minor):
  at least hourly and at local midnight, so the chip, the card and the notifications count down by themselves.

### 3. Start with Windows (user accepted the proposal)

- **On by default**: the first time an *installed* build starts and the streamer never chose, it registers itself.
  If the streamer later switches it off (Settings or Task Manager), it stays off; nothing turns it back on.
- **Started by Windows → hidden in the tray** (a start argument such as `--autostart` tells the two apart). Started by
  the streamer → the window shows.
- **Settings → a switch "Starten met Windows"** with a hint. Its state reflects Windows' reality (Run key present and
  not disabled in Task Manager). Switching it on while Task Manager disabled it re-enables it in both places.
- **When not installed** (`.\gradlew.bat run`, no stable exe path; jpackage sets `jpackage.app-path` only in the installed
  launcher) the switch is shown disabled with "Alleen in de geïnstalleerde app" and nothing is registered.
- The Run entry points at the installed launcher exe; when that path changes (reinstall elsewhere), the next start of the
  installed app updates the entry if autostart is on.

### 4. One instance (user accepted the proposal)

- A second start (by the streamer, a shortcut, or Windows' autostart while the app already runs) finds the lock held,
  asks the running instance to **show its window**, and exits without touching any file. An autostart that finds the
  app already running does nothing visible.
- The lock and the "show" channel live in `%APPDATA%\Requestify`, so a `.\gradlew.bat run` and an installed build can
  never run at the same time either (they share the data directory).
- A lock left by a crashed process must not block the next start (an OS file lock is released when the process dies;
  a stale port file is ignored when nothing answers).

### 5. A crash restarts the app once and leaves a trace (user's decision after the builder check)

Why: the app runs hidden, and Windows 11 often puts tray icons in the overflow area, so a crash mid-stream would
otherwise make Requestify vanish unnoticed (the first anti-goal).

- An uncaught error (any thread, including the UI thread and the app scope's coroutines) is written to
  `%APPDATA%\Requestify\crash\<time>.txt` (stack trace, app version, time); the newest 10 files are kept.
- **Restart once** (user's decision): after writing the crash file the app quits and **starts itself again** (the
  installed launcher exe, with an argument such as `--after-crash`). The restarted app:
  - starts **hidden in the tray**, like an autostart, and shows one notification "Requestify is herstart na een fout" /
    "Requestify restarted after an error" (click: opens the window);
  - waits for the crashed process's single-instance lock to be released instead of handing over to it (decision 4);
  - catches up as after any restart (Channel Points' unfulfilled redemptions, DonoLink's cursor; a Bits gap is logged if
    the stream was live, by the existing gap rules).
- **No restart loop:** if the app crashes again within 10 minutes of a crash-restart, it does **not** restart. It shows
  a message that stays visible after the app is gone — "Requestify is gestopt door een fout. Start het opnieuw." — and
  quits. The builder picks a mechanism that works when the UI thread is the part that failed (*suggestion*: a native
  Windows message box via JNA, already a dependency, shown from the crashing thread).
- **Not installed** (`.\gradlew.bat run`): no restart (no launcher exe); crash file, the same "stopped" message, quit.
- Crash-restart does not cover a hang or a hard kill (Task Manager, power loss); those are only noticed at the next start
  through the unclean-exit marker below. A watchdog process stays a non-goal.
- The crash path does not try to pause the Channel Points reward: the app is back within seconds, and unfulfilled
  redemptions are settled at the next start anyway.
- **Unclean-exit marker:** a "running" marker is written at start and removed on every orderly end. At the next start, a
  leftover marker means the previous run did not end properly: the Log gets one Problems row "Requestify is onverwacht
  gestopt" with the time of the last sign of life and, if a crash file was written, its first line (and "herstart" when
  this start is the crash-restart).
- **An orderly end includes Windows shutting down or the user logging off**: the builder must make sure that is not
  logged as a crash (shutdown hook / end-session handling), or every PC shutdown would produce a false row.
- An uncaught error should not leave a half-alive app: after writing the crash file the app quits (and restarts, above;
  the music keeps playing in Spotify — parent design). *Builder to confirm* what Compose/AWT do with an exception on the
  UI thread and choose "quit" over "carry on in an unknown state".

### 6. Saving off the UI thread (user's decision: approach A)

- **One pattern for every file** that is still saved on Main: settings, blacklist, rejected, pending resolves, donation
  cursors, `stream.json`, `redemptions.json`, `secrets.json`.
- A change is **applied in memory at once** and visible immediately. A writer per file (off the UI thread) saves the
  newest value, coalescing quick successive changes, with the same atomic + durable write as today.
- **A save that fails** is retried with backoff. While a file cannot be saved:
  - a banner (the existing `ProblemBanner` style) says "Kan niet opslaan" with the file and reason;
  - the Log gets one Problems row per outage, updated to "opgeslagen na N s" when it works again (one row per outage,
    as connection outages in plan 3);
  - the tray icon shows its warning mark;
  - after 2 minutes without success, one Windows notification (decision 2).
- **Order where money is involved:** a donation source's cursor/seen-set reaches disk **only after** the requests it
  covers have reached disk (queue, pending resolves or rejected). If the app dies in between, the donation is fetched
  again and deduplicated, never skipped. The builder applies the same rule wherever a "this was handled" marker is
  saved after the thing it marks (e.g. the redemption outbox vs the queue).
- **Quit waits for pending saves** (bounded, e.g. a few seconds, alongside the reward pause) so a change made just
  before Afsluiten is on disk.
- **A file that could not be read at start** keeps plan 1's rule: the default is never written over it. While it is
  unreadable, the store retries the read in the background (plan-1 leftover "settings re-read when unreadable at
  startup"); changes to that store are refused with the existing "couldn't read" banner until a read succeeds, and the
  read value then replaces the default.
- **Secrets:** DPAPI calls and `secrets.json` I/O happen off the UI thread; the DonoLink key and SE token are decrypted
  once and kept in memory until they change, instead of on every poll.
- **Other blocking I/O on Main** (the plan-3 list: DonoLink key read + DPAPI every 5 s, cursor saves, SE heartbeat fsync)
  is covered by the above. The builder checks for any other file, DPAPI or network call still made on the UI thread.
- Callers that today branch on the `Boolean` "was it saved" adapt to the new rule; where a caller showed "couldn't save"
  for its own action, the global banner replaces it.

### 7. Plan-1 reliability leftovers

- **Corrupt-file copy dedupe:** when a corrupt file cannot be moved aside and is copied instead, the next start must not
  make yet another copy of the same file (e.g. skip when an identical `.corrupt-*` copy exists).
- **Settings re-read when unreadable at startup:** covered in decision 6.
- **The error banner squeezing the narrow list:** in Home's narrow layout a problem banner must not push the queue to
  less than one visible row; it compacts (e.g. one line, expandable) instead.
- **Store I/O off the UI thread:** decision 6.

### 8. Parked plan-2 and plan-3 minors (user's decision: all of them)

Expected behaviour after the fix; the builder finds the cause in the code.

**Texts and the Log**
- The chip and the card say how many days a DonoLink key has left ("verloopt over 6 dagen"), not just "binnenkort".
- The Donations card's badge says "Sleutel geweigerd" / "Token geweigerd" for a refused key, not "Login verlopen".
- A Log row never says "In wachtrij, plek 0": positions are 1-based, and a request that is next / already handed over
  says so.
- `!blacklist` replies "blocked and skipped" only when a skip happened; otherwise "blocked" (with why nothing was
  skipped, e.g. nothing playing or not a request).
- Home's add field and the Blacklist screen given a short link that resolves to an album/artist say "that is not a
  track" (as the chat path does), not "No results".
- Switching away from a donation source that was in an outage closes its open outage row in the Log ("bron
  gewijzigd").
- The first connect at startup is not logged as "Restored" (a `Connecting` state at start is not an outage).
- StreamElements tips that SE rejected by moderation or that failed appear in the Log as ignored donations with that
  reason.
- Donation details in the Log show when the donation was made (`Donation.occurredAtEpochMs`), which differs from when
  it arrived after a catch-up.

**Live status edge cases**
- A restart during a **new** stream does not continue the old one: when Helix's `started_at` is later than the saved
  stream's start, the old stream is treated as ended while the app was off (end-of-stream clear of free requests with
  refunds, Log rows) and the new one starts.
- A live-status read that contradicts a stream event less than 5 minutes old is not dropped for good: it is read again
  once the window has passed.
- The gate's heartbeat (`lastSeenLiveMs`) ticks only while the Twitch **session is connected**, not merely while an
  account is set, so gaps are not under-reported.
- When the app starts after a stream that ended while it was off, a Bits gap is logged from the last time it saw the
  stream live to that stream's end (or "until the stream ended" when the end time is unknown).

**Under the hood**
- `LogStore.stop()` cannot race the writer: no duplicate Log lines on quit.
- DonoLink catch-up reads a bounded number of pages per poll and continues on the next poll (no unbounded loop after a
  long time off).
- The Astro resume token is URL-encoded.
- A logout whose settings change fails no longer leaves the own reward paused (with approach A the change applies at
  once; the reward and settings stay consistent).
- File formats are pinned by fixture tests: `DonationCursor`, `LogRecord` (and the other persisted types that lack one),
  so a rename can never silently break an existing user's files — this matters before plan 5 ships updates.
- The flaky timing tests (`ChatReplySenderTest.rateLimitedWaitsAndRetries`,
  `RewardManagerTest.aNewerWantedStateWinsOverAPendingRetry`) become deterministic (virtual time, no wall-clock waits).

**StreamElements currency**
- The SE channel's currency is fetched (with the channel, through SE's REST API — builder to verify the field) and the
  minimum is in that currency, as plan 3 intended: a tip in another currency is a rejected paid request "other
  currency", listed with its track. While the channel currency is unknown (fetch failed, field absent), today's
  behaviour stays (minimum compared in the tip's currency), and the card says the currency is unknown.
- This cannot be live-tested (the user's viewers use DonoLink): built and tested against SE's docs and fixtures, as in
  plan 3.

### 9. Icon (user's decision: the user supplies it — and did, on 2026-11-18)

Two files, saved with this design in `docs/superpowers/specs/assets/plan-4-icon/`:

- **`requestify-icon-1024.webp`** — the app icon *with* its background (dark green square, gold diagonal waves, a
  cream "music notes +" glyph; the CastIRL family style), 1024 × 1024. Used for the **tray icon, the window icon and the
  taskbar** (user's words: "het logo met background voor de tray en taskbar").
- **`music-notes-plus.svg`** — the bare glyph (one path, 256 × 256 viewBox, black fill). Used **in the app's title bar**,
  **tinted with the app's colour scheme**, so it follows the album-art colours like the rest of the app (user's words:
  "de SVG die ook met de kleuren mee beweegt in de app toolbar").

Rules:
- The icon is loaded from **one place** for tray, window and taskbar; replacing it is replacing that file. The warning
  mark (decision 1) is drawn in code over it.
- Java's ImageIO cannot read WebP: the builder converts it once to PNG at the sizes Windows asks for (tray 16/20/24/32,
  window/taskbar up to 256) and checks they stay legible at 16 px. If the user has the original PNG export, that is used
  instead. Plan 5 derives the installer's `.ico` from the same source.
- The glyph becomes an `ImageVector` (like the Material icons in `ui/common/Icons.kt`) or a Compose vector resource, so
  it can be tinted; which theme role tints it (e.g. `primary`) is the builder's call, checked in the offscreen renders.
- The glyph's path appears to be **Phosphor Icons' "music-notes-plus"** (MIT licence). The builder adds it to `NOTICE`
  next to the Material Icons credit (*builder's assumption*; the user can correct the source).

## Verification

- Unit tests for every decision that has logic: notification de-duplication and expiry steps, the autostart state
  machine (never re-enabling what the streamer turned off), the single-instance handshake, the unclean-exit marker
  (including orderly shutdown), the crash-restart (restart once, no loop within 10 minutes, waiting for the old lock),
  the write-behind store (coalescing, retry, banner state, refusal while unreadable, the
  cursor-after-request order, quit flush), and each minor's fixed behaviour.
- **Offscreen renders** (as in plans 2 and 3) for the user to check before UI tasks are reviewed: Settings with the
  "Starten met Windows" switch (enabled and "only in the installed app"), the "Kan niet opslaan" banner, Home's narrow
  layout with a compact banner, the Donations card badge texts, the title bar with the tinted glyph under two different
  album colours (and a custom colour), and the tray icon PNGs at 16/24/32 px with and without the warning mark.
- **Manual checks in a locally installed build** (`.\gradlew.bat packageExe`, install, run the installed app): tray icon
  and menu, notifications (their look and sender name in the installed app), a second start showing the first window,
  autostart after a Windows sign-in, Task Manager's Startup switch reflected in Settings, a Windows restart not logged
  as a crash, a forced crash restarting the app once and a second one stopping it with the message. The user does these;
  the builder writes a short checklist and provides a way to force a crash in a test (e.g. an environment variable that
  only a test run sets; never a visible control). The desktop is never driven by scripted clicks.

## Left to the builder

- Package structure and class names (e.g. a `background/` or `desktop/` package for tray, autostart, single instance and
  crash trace); where `connectionProblems()` moves so the tray can use it outside the UI package.
- The single-instance channel's exact mechanism, port/lock file names, and the start argument's name.
- Write-behind details: coalescing window, backoff values, how ordering between stores is expressed (e.g. awaiting the
  queue's save of a given version), the quit flush timeout.
- Exact texts (nl + en) of notifications, the tray menu, the Settings switch, the save banner and Log rows; icons;
  motion.
- How the warning mark is drawn on the tray icon and how it looks at Windows' scale factors.
- Crash file format and what counts as "last sign of life" for the unclean-exit row (e.g. a timestamp the app updates
  now and then).
- The SE REST field for the channel currency (record findings in the existing
  `docs/superpowers/specs/2026-11-18-streamelements-api-notes.md`).
