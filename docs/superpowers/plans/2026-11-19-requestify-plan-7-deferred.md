# Requestify plan 7 — the deferred points of plan 6

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 12 minor points the plan-6 final review deferred (numbered 5–16 as in the plan-6 ledger), test-first, so 0.3.x has none of them.

**Why now:** the user, 2026-11-19: "Pak de uitgestelde punten van plan 6 ook aan … Ga je gang, maar wacht wel met pushen en mergen tot ik er weer ben." The user is away while this runs, so every choice below is the builder's call and is listed for the user to check; nothing is pushed or merged.

**Spec:** this file (a small change: each point states its problem, its fix and its test). Background: `docs/superpowers/specs/2026-11-19-requestify-plan-6-bot-and-fixes-design.md` and the plan-6 ledger's "Final: minor (deferred)" lines (session notes, `plan-6-ledger.md`).

**Approaches:** each point has one sensible fix (named below). Two alternatives were set aside:
- For 6, stop answering before exit: rejected, because a start in that gap would step aside and nothing would run.
- For 13, a new "restart Windows" Log record: rejected as a saved-format addition for a rare case. A line on Over Requestify says it instead.

## Global Constraints

- **Paths:** relative to `src/jvmMain/kotlin/nl/diments/requestify/` and `src/jvmTest/kotlin/nl/diments/requestify/`.
- **Strings:** every new string goes in both `strings.xml` files with identical keys. Dutch says "Kanaalpunten", never "Channel Points".
- **Saved formats:** no new saved fields or enum values (none are needed).
- **Commits:** no `Co-Authored-By` trailer and no mention of Claude. Work on branch `plan-7-deferred` only; no push or merge until the user says so.
- **Parallel work:** a parallel session (`ui-audit`) edits UI layout on its own branch. Keep the UI changes here to text and logic.
- **Test command:** `./gradlew jvmTest --tests "<pattern>"`. Run the whole suite at the end.

## Review Focus

1. A start during Quit still waits and runs; a start during an update's shutdown steps aside, and never runs the old version while msiexec replaces it.
2. A shutdown that Windows kills during the hook's save is not reported as an unexpected end at the next start.
3. The bot section never says replies come from the bot while they come from the streamer.

---

### Task 1: Start, exit and the crash marker (points 5, 6, 7)

**Files:** `desktop/SingleInstance.kt`, `desktop/CrashGuard.kt`, `Main.kt`; tests `desktop/SingleInstanceTest.kt`, `desktop/CrashGuardTest.kt`.

**Point 5: a start that waited for a quitting instance does not re-check whether an update is installing.**
- Fix: `SingleInstance.start(..., mayRunAfterWaiting: () -> Boolean = { true })`.
  - After a wait for a quitting instance has got the lock, a false answer releases the lock again and gives `AlreadyRunning`.
  - Main passes `{ !updateInProgress(UpdateStore(paths.updatesDir)) }`.
- Fix, also: the two update paths in Main no longer call `markQuitting()`. During an update's shutdown a later start is told "shown" and steps aside, as in 0.2.0; the helper starts Requestify again after the install.
- Test: `aStartThatWaitedStepsAsideWhenTheCheckSaysSo`. The first instance quits; `mayRunAfterWaiting = { false }`; the result is `AlreadyRunning` and the lock is free again.

**Point 6: `instance.close()` releases the lock before the shutdown hook's save.**
- Fix: Main's exit paths (Quit, install at start, "Nu bijwerken") no longer close the instance before `exitProcess`. The OS releases the lock and the socket when the process ends, after the hook. A start meanwhile is told "quitting" (Quit) and waits for that.
- No unit test: Main has no harness. The final review reads it. The leftover port file is already handled (`aStalePortFileDoesNotBlock`).

**Point 7: the hook saves before it removes the crash marker.**
- Fix: the shutdown hook calls `markOrderlyEnd()` first, then `flushOnShutdown()`. It is an orderly end whatever the save does.
- Test: `theShutdownHookEndsInOrderBeforeItSaves` replaces `theShutdownHookSavesBeforeItEnds`. The marker is already gone while the flush runs.

### Task 2: The bot and the Log (points 9, 10, 11, 12)

**Files:** `core/log/Logbook.kt`, `core/log/LogRecords.kt`, `core/twitch/chat/DropTracker.kt`, `core/twitch/bot/TwitchBot.kt`, `core/twitch/chat/ChatReplySender.kt`; tests `core/log/LogbookTest.kt`, `core/twitch/chat/DropTrackerTest.kt`, `core/twitch/bot/TwitchBotTest.kt`, `core/twitch/chat/ChatReplySenderTest.kt`.

**Point 9: a new drop reason while messages are already held back is not logged.**
- Fix: `Logbook.onConnection` also records `MessagesDropped` again (same row) when its `detail` changed.
- Test: `aNewReasonIsAddedToTheSameRow`. Slow mode, then followers only, then Up gives one row: `[MessagesDropped(Slow mode), MessagesDropped(Followers only), Restored]`.

**Point 10: the streamer's held-back row stays open while the bot sends everything.**
- Fix: a reply the bot sends also calls `streamerDrops.sent()`. Replies get through again, so the streamer's row closes. Builder's call: the row is about replies reaching chat, not about which account.
- Test: `aReplyTheBotSendsClosesTheStreamersHeldBackRow`.

**Point 11: `blockedReason` can show an AutoMod hold's message.**
- Fix: `DropTracker.countedReason: StateFlow<String?>`, the reason of the latest drop that counts (not a moderation hold). `TwitchBot.blockedReason` uses it.
- Tests: `DropTrackerTest.aHoldDoesNotChangeTheCountedReason` and `TwitchBotTest.theBlockedReasonIsNeverAnAutoModHold`.

**Point 12: an orphaned KDoc.**
- Fix: `EndedWithApp`'s KDoc moves back above `EndedWithApp`.
- No test (a comment).

### Task 3: The bot section (points 8, 15a, 16)

**Files:** `ui/setup/TwitchBotSetupViewModel.kt`, `ui/setup/TwitchBotSection.kt`, both `strings.xml`; tests `ui/setup/TwitchCardTest.kt`.

**Point 8: "Antwoorden komen van @bot" while the bot cannot send.**
- Fix: the view model exposes `blockedReason` (from `TwitchBot.health`). While the login is dead or the bot is held back, the line reads `twitch_bot_replies_from_you`: "Antwoorden komen nu van jou; @%1$s kan even niet" / "Replies come from you for now; @%1$s can't send".
- Test: `theBotsDeadLoginSaysRepliesComeFromYou`. The dead-login card shows that line and not "Replies come from @MyBot".

**Point 16: an unknown moderator status hides both the tick and the tip.**
- Fix: unknown (`null`) shows the tip with "Opnieuw kijken", like `false`.
- Test: `anUnknownModeratorStatusStillShowsTheTip`.

**Point 15a: no UI test of the own-account text.**
- Test: `theBotSectionSaysWhenItIsYourOwnAccount`. A `FakeTwitch` whose user is the streamer, then "Connect bot", shows "That is your own account…".

### Task 4: An update that waits for a Windows restart; revoking a refused bot login (points 13, 14, 15b)

**Files:** `core/update/UpdateService.kt`, `ui/settings/AboutCard.kt`, `core/twitch/auth/TwitchAuth.kt`, both `strings.xml`; tests `core/update/UpdateServiceTest.kt`, `ui/settings/AboutCardTest.kt`, `core/twitch/auth/TwitchAuthTest.kt`, `AppGraphTest.kt`.

**Point 13: exit code 0 while the old version still runs stays Ready forever, with no hint.**
- Fix: `UpdateState.Ready(..., restartPending: Boolean = false)`.
  - `reconcile` with exit code 0 and `running < target` gives `Ready(restartPending = true)` below `maxAttempts`.
  - At `maxAttempts` it gives `InstallFailed`, as other failures do ("download it yourself"), so "Nu bijwerken" is not offered without end.
  - Over Requestify shows `about_restart_pending`: "Herstart Windows om de update af te maken." / "Restart Windows to finish the update."
- Tests:
  - `anInstallWaitingForARebootSaysRestartWindows`: attempts 1, exit code 0, gives `Ready(restartPending = true)`.
  - `anInstallThatKeepsWaitingForARebootGivesUp`: attempts 2, exit code 0, gives `InstallFailed`.
  - `AboutCardTest.aPendingRestartIsSaid`.

**Point 14: a refused bot login is discarded, not revoked.**
- Fix: on `WrongAccount`, `TwitchAuth` posts `client_id` and `token` to `/oauth2/revoke`. It is best effort, bounded by the request timeout; a failure changes nothing.
- Test: `aRefusedAccountIsRevokedAtTwitch`. The mock records `POST /oauth2/revoke` with `token=a1`.

**Point 15b: the bot end-to-end test does not check the bot's bearer token.**
- Test: `AppGraphTest.aConnectedBotSendsTheReplies` also asserts `Authorization: Bearer <the bot's access token>`. The fake id server answers the bot's refresh token (`b1`) with its own access token.
