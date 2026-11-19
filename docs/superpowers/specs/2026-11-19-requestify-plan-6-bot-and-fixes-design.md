# Requestify plan 6 — a Twitch bot account, and every deferred point (release 0.3.0)

- **Date:** 2026-11-19
- **Status:** design, awaiting the user's approval
- **Previous plan:** `2026-11-18-requestify-plan-5-release-design.md` (installer, self-update, welcome wizard). Plans 1–5 are on `main` (`3470acb`); `v0.2.0` is published on GitHub as the latest release.
- **Deferred points:** the "Final: minor (deferred)" lines of the plan-4 and plan-5 ledgers (session notes, `plan-4-ledger.md`, `plan-5-ledger.md`); each is restated below with its behaviour, so this document stands on its own.
- **Twitch API notes:** `2026-11-17-twitch-api-notes.md`; StreamElements: `2026-11-18-streamelements-api-notes.md`.

## Intent

Requestify 0.2.0 went out on 2026-11-19. Before friends get it, the user tests updating (checklist part E), which needs a second release. The user asked to make that release worthwhile:

1. **"Voeg een mogelijkheid toe om voor Twitch een bot-account erbij te koppelen om de berichten te laten versturen. Neem dit ook mee in de onboarding als optionele optie."** Streamers commonly have a separate bot account, so chat sees "Requestify-bot: Toegevoegd: …" instead of the streamer talking to themself.
2. **"Verder mag je alle uitgestelde punten etc ook gelijk aanpakken!"** — the 12 open plan-4 minors, the 18 plan-5 minors, and one quirk found while making the README screenshots.

Success: a streamer can connect a bot in Settings or in the welcome wizard in a minute, chat replies then come from the bot, and nothing they relied on gets worse when the bot is gone. The release also passes checklist part E as the "0.2.0 → newer" update.

## Goals

1. An optional Twitch **bot account** that sends Requestify's chat replies (Decisions 1–8).
2. The bot in the **welcome wizard** as an optional extra on the Twitch card (Decision 7).
3. Every deferred point fixed (section "Deferred points").
4. "Alles verbonden" no longer shows when nothing is connected (F-31).
5. Release **0.3.0**, and the manual checklist updated for it (Decision 9).

## Non-goals

- The bot does **not** read chat, run EventSub, or touch Channel Points/Bits: those stay on the streamer's account.
- No moderator actions through the bot (no timeouts, no announcements).
- No automatic "make the bot a moderator" (that needs a new scope on the streamer's login, which would force every streamer to log in again). A tip with a copy button instead (Decision 6).
- Code signing stays out (unchanged from plan 5).

## Constraints

- **Twitch Send Chat Message** (`POST /helix/chat/messages`): `broadcaster_id` = the channel, `sender_id` = the sending user. With a **user** access token the sender's token needs only `user:write:chat`. The response has `is_sent` and, when false, `drop_reason {code, message}` (e.g. held by AutoMod, followers-only, verified phone required, slow mode). (dev.twitch.tv, "Sending and Receiving Chat Messages", read 2026-11-19.)
- The app's Twitch application is a **public client** (no secret, device-code flow, `TWITCH_CLIENT_ID` in source). App access tokens need a client secret, so the bot must log in as a user, like the streamer does.
- The device-code page (`twitch.tv/activate`) approves for **whatever account is logged in in that browser** — usually the streamer's own. The bot login must guard against that.
- `TwitchAuth` today hard-codes its secret key (`twitch.refresh_token`) and `TWITCH_SCOPES`; `TwitchApi.sendChat` always sends `sender_id = broadcaster`, does not read `drop_reason`, and `ChatReplySender` drops any non-OK result silently.
- Saved formats stay readable both ways: `settings.json` is read with `ignoreUnknownKeys`, so new fields need defaults; **no new enum values in saved enums** that an older version would meet (a failed update can put 0.2.0 back on a newer settings file, and an unreadable settings file is moved aside). This rules out a new `WizardStep` value; the Log is a separate case, because its reader skips lines it cannot decode (Decision 8).
- Updates never during a stream; notifications only for "action needed" (plans 4–5, unchanged).

## Approaches

For the bot, three ways were considered:

- **A second user login for the bot (chosen).** The bot logs in with the same device-code flow as the streamer, with only the scopes it needs; replies are sent with the bot's token and `sender_id = bot`. Works with the public client, and the bot needs no special status from Twitch.
- **App access token with `user:bot` + `channel:bot`.** Twitch's route for "verified" chatbots; it needs the client secret, which cannot live in a downloadable app. Ruled out.
- **IRC chat.** Twitch is phasing it out in favour of EventSub + Helix, and it still needs the bot's user token. Ruled out.

For the deferred points there is no real choice of approach; each gets the fix described below.

## Decisions

### 1. Two Twitch logins

`TwitchAuth` gets its secret key and scopes injected (defaults keep today's values). The bot is a second instance: secret `twitch.bot_refresh_token`, scopes `user:write:chat` and `user:read:moderated_channels` (the latter only to show whether the bot is a moderator, Decision 6). The bot's account (`userId`, `login`, `displayName`) is saved in `TwitchSettings.bot: TwitchAccount? = null`.

### 2. Who sends a reply

`ChatReplySender` sends as **the bot when a bot is connected** (bot login OK and `TwitchSettings.bot` set), else as the streamer. `TwitchApi.sendChat` takes the sender: the token source and `sender_id` come from the chosen account; `broadcaster_id` stays the streamer.

### 3. When the bot cannot send: fall back to the streamer (user's decision)

The user chose **"Terugvallen op jou"**: viewers always get an answer.
- The bot's login is refused or gone (`LoginNeeded`, `LoggedOut`), or the bot is not allowed in the channel (`Forbidden`): the same reply is sent again **as the streamer**, at once; the bot's problem is reported (Decision 5).
- The message was **not sent** (`is_sent = false`): its `drop_reason` is recorded in the Log; the reply is sent again as the streamer **unless** the reason says it is held for review (AutoMod: it may still appear, and a second copy would be a duplicate).
- Rate limits keep today's behaviour (wait and retry, as the bot).
- The streamer's own sending keeps today's behaviour, plus: a not-sent message is now recorded in the Log too (it was silently ignored).

### 4. The bot's own messages are not commands

The bot's messages come back through the streamer's `channel.chat.message` subscription. They are already skipped by message id (`isOwnMessage`); in addition, messages whose chatter is the connected bot are skipped. (Reply texts never start with `!` anyway.)

### 5. The bot in health, notifications and the Log

Two bot states are **action needed** (builder's call, following plan 4's "notify only when the streamer must act"), each driving the title-bar chip, the tray text and one notification, like `TwitchLoginNeeded`:
- `ConnectionProblem.TwitchBotLoginNeeded` — the bot's login was refused: "De bot moet opnieuw inloggen — antwoorden komen nu van jou".
- `ConnectionProblem.TwitchBotBlocked` — the channel does not let the bot talk: a `Forbidden` (e.g. banned or timed out), or **3 messages in a row** not sent for a reason other than AutoMod (e.g. followers-only, verified phone): "Je chat houdt de bot tegen (reden) — antwoorden komen nu van jou". It clears at the bot's next sent message (it keeps trying the bot first for every reply; each failure falls back at once, so viewers lose nothing).

A bot that is merely not connected is not a problem. The Log gets these as connection rows of their own service (Decision 8): an outage row from the first failure to the next success.

**"Not sent" in the Log (builder's call).** Most replies belong to no request row (`!song`, Bits/Kanaalpunten/donation outcomes), so a dropped message is not a row per message: drops are folded into one connection-style row per sender and reason ("Berichten tegengehouden: alleen volgers"), open from the first drop to the next message that is sent. For the bot this is the `TwitchBotBlocked` row above; for the streamer's own account it is the same kind of row under `LogService.Twitch` — a problem row, without a notification (the streamer cannot be "fallen back" from, and Twitch's own chat shows them why).

### 6. Bot section in Connections → Twitch

Below the chat settings, a section **"Bot-account (optioneel)"**:
- **Not connected:** one sentence on what it does and the button **"Bot koppelen"**. The login shows the same code panel as the streamer's login, with an extra line: **"Open de link in een privévenster en log daar in met je bot-account"**, plus a copy-link button next to "open browser".
- **Your own account by mistake:** when the approved account is the streamer's (same user id), the login is undone and the section says so ("Dat is je eigen account. Log in met je bot, bijvoorbeeld in een privévenster.").
- **Connected:** "Antwoorden komen van **@botnaam**" with **Ontkoppelen**; whether the bot is a moderator in the channel ("Moderator ✓", or the tip "Maak je bot moderator, dan houdt Twitch zijn berichten minder snel tegen: `/mod botnaam`" with a copy button); and the last drop reason if a recent bot message was not sent.
- **Login refused:** the dead-login banner pattern of the streamer's section ("De bot moet opnieuw inloggen" + "Opnieuw inloggen").
- Logging the streamer out leaves the bot connected but unused (there is no channel to talk in); the bot section is only shown while the streamer is logged in.

### 7. The bot in the welcome wizard

The wizard's Twitch card already shows the whole logged-in Twitch content, so the bot section appears there too, in a compact wording. After the Twitch celebration the card **stays** (today it stays only when Channel Points can still be set up), so the streamer sees the optional bot (and points) and moves on with **"Verder"**. No new wizard step (saved-format constraint).

### 8. Saved formats

- New settings fields have defaults (`TwitchSettings.bot = null`), so 0.2.0 reads a 0.3.0 settings file.
- No new values in saved enums **of settings and queue files**. The Log is different: `LogStore.readAll` already skips any line it cannot decode, and the Log only appends, so 0.2.0 tolerates new Log names (it just leaves those rows out). So the bot gets its **own** `LogService.TwitchBot` (`"twitch_bot"`), and new `LogConnectionState` values may be added for "messages not sent" (e.g. `"messages_dropped"`, with the reason in `detail`). Its own service also keeps the Logbook's per-service outage tracking (`openOutage`, `connectionNow`) from mixing the bot's outage with the streamer's.
- Every new or changed saved field is pinned to literal JSON in `SavedFormatCompatTest`.

### 9. Version and checklist

The release is **0.3.0** (a new feature; the update test does not care about the number). The manual checklist (`2026-11-19-requestify-plan-5-manual-checks.md`) gets: part E renamed to "0.2.0 → 0.3.0" (and the pre-release to `v0.3.1`), the gaps from F-15, and a new part **G. Bot-account** (connect in a private window; own account refused; replies come from the bot; bot logged out → replies from you + warning; disconnect).

## Deferred points

Each line: what goes wrong today → what it does after. "Pn" are the plan-4 minors (M-numbers kept), "F" the plan-5 ones.

**Start, exit and crashes**
- **M-1** A second start during Quit steps aside while the quitting instance still answers "ok", so nothing ends up running → the quitting instance answers "quitting"; the new one waits (up to 15 s) for it to exit and then starts.
- **M-4** CrashGuard's marker races (a late `touch()` rewrites the marker after an orderly end; the hook could delete a new instance's marker) → the marker carries the run's id; touching stops at an orderly end and the hook removes only its own run's marker.
- **M-12** The fatal message box shows while the rest of the app keeps running until OK → the app stops its work (sources, playback hand-over, saves flushed) and hides its window before the box shows.
- **M-14** The shutdown hook does not flush pending saves (Windows shutting down right after a change) → the hook flushes with a short bound (2 s).
- **F-18 (M-5 order)** Recognising "the JVM is shutting down" depends on our hook running first → also recognised directly (registering a probe hook throws during shutdown).

**Saving and secrets**
- **M-2** `BackgroundWriter.flush` does not cut a running back-off (up to 30 s) short, so a Quit after a long outage may not retry → flush wakes the writer and tries at once.
- **M-3** The DPAPI secret store's background re-read holds its lock across the file read and decryption (UI-thread reads can wait ~350 ms, only while `secrets.json` was unreadable at start) → read and decrypt outside the lock, swap the result in under it.
- **M-9** A rejection saved "later" is outside `awaitSaved`/`flushAll`, and `PlaybackController.awaitSaved`'s KDoc overstates → it is part of the flush; the KDoc says what is true.
- **M-10** DonoLink's expiry step is re-sent every hour while `settings.json` is unreadable (the stage lives only in settings) → the stage is also kept in memory for the run.
- **M-17** Small: `reReadPending` survives a run change; `BlacklistStore` KDoc; `iconImages` set on every recomposition; `SettingsStore.update` throws `IllegalStateException` → reset per run; KDoc fixed; set once; a typed result/exception the callers handle.

**Requests and sources**
- **M-8** The sweep's snapshot is taken outside the lock, so a request handed over meanwhile stays listed under Rejected → the snapshot is taken under the lock.
- **M-15** StreamElements' tipping `currency` field is unverified, and a saved currency is never cleared → when the API returns no currency, the saved one is cleared (builder's call: the field itself stays unverified — the notes have no live response and the user's viewers use DonoLink; a checklist line asks to look at it once if a StreamElements account is ever at hand).
- **M-16** `RewardManagerTest`'s flaky test is not on virtual time → rewritten on virtual time; 50 clean runs.

**Updates**
- **F-1** The first failed install's Log row is lost when the immediate retry exits before the Log starts → the failure is written to the Log store directly (not only as an event) before the retry starts.
- **F-2** A failed check by hand replaces "Ready"/"InstallFailed" with "could not check" until the next check → those states stay; "could not check" shows as a short line next to them.
- **F-3** A double click on "Nu bijwerken" counts a second attempt → one install request per run.
- **F-4** The "Bijgewerkt" card can show an older pending update's notes after a newer version was installed by hand → reported only when the running version equals the pending one.
- **F-5** "One Log row per version" holds per run, and a bad checksum re-downloads ~90 MB every 6 h → a failed download of a version is remembered on disk; it is retried once a day, or when a newer release appears.
- **F-6** The ~90 MB download also runs while live → downloads wait until the stream is offline.
- **F-7** The helper waits for the JVM child, not jpackage's parent launcher; exit code 3010 (success, reboot needed) counts as a failure → it waits for the launcher process too; 3010 (and 1641) count as success.
- **F-8** The MSI's checksum is not re-checked right before installing → re-checked; a mismatch deletes it and downloads again later.
- **F-9** A hidden `-EncodedCommand` PowerShell from an unsigned exe may look suspicious to antivirus → the helper runs as a **readable** `-Command` script (same steps), not base64 (builder's call: a `.ps1` with `-File` would need `-ExecutionPolicy Bypass`, which antivirus flags just as much and a Group Policy can block; `-Command` is not subject to the execution policy). The script uses only single-quoted strings (quotes in paths doubled), so Java's Windows argument quoting cannot break it. Checklist step 16 still looks at Defender's history.
- **F-10** Uninstall leaves `%LOCALAPPDATA%\Requestify-updates` (up to ~90 MB) → the uninstall cleanup deletes it.
- **F-12** No standing link to the release page in "Over Requestify" → "Wat is er nieuw" always links the running version's release page.
- **F-13** The saved fields of `app_updated`/`update_failed` and `PendingUpdate.from` are round-tripped, not pinned → pinned to literal JSON.
- **F-14** Two local builds in the same minute get the same `0.0.n` → the build remembers its last number and always goes up.
- **F-17** `release.yml` puts `github.ref_name` straight into a script, and the placeholder release notes become the "Bijgewerkt" card text when not replaced → the tag goes through an environment variable; the card leaves out the placeholder line.

**Wizard and screens**
- **F-11** Donations celebrate as soon as a key is saved, before its test passed; choosing "Geen" does not move the wizard on → celebrate when the test passes; "Geen" moves on like "Later doen".
- **F-31 (new)** The title bar says "Alles verbonden" when nothing is connected yet → it says "Nog niets gekoppeld" (neutral) until Spotify or Twitch is connected.

**Docs**
- **F-15** Checklist gaps: install while signed in elsewhere / at sign-in, uninstall while Requestify runs, step 16 needs Quit first and leaves a pending pre-release behind → steps added or fixed (with Decision 9).
- **F-16** README: Smart App Control in "Evaluation" can switch itself on later → one sentence in both languages.

## Left to the builder

- Names, file layout, the exact Dutch/English copy beyond the lines quoted above (Dutch says "Kanaalpunten", never "Channel Points"; tone as in the rest of the app).
- How the sender choice is threaded through `ChatReplySender`/`TwitchApi` (an account parameter, two `TwitchApi`s, …).
- Whether the moderator check runs once after login, on opening the section, or hourly with the existing validation.
- The exact drop-reason codes that count as "held for review"; when unsure, a reason containing `automod` counts as held.
- How M-1's "quitting" answer is phrased in the single-instance protocol, keeping an older instance's "ok" understood.

## Review focus

1. The streamer logs in **as themself** on the bot's code page (the common mistake): refused, nothing half-saved, the streamer's own login untouched.
2. Bot login expires **mid-stream**: every reply still arrives (as the streamer), exactly one warning, and when the bot logs in again replies move back to it without a restart.
3. A bot message held by AutoMod is **not** sent a second time; one dropped for followers-only is.
4. 0.2.0 reading a 0.3.0 `settings.json` and Log folder (after a failed update put 0.2.0 back): nothing moved aside, nothing lost.
5. An update downloaded while offline, then the streamer goes live before the next start: still never installed during the stream; and a download that starts while live waits.
