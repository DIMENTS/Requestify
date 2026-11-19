# Requestify plan 6 — a Twitch bot account, and every deferred point — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A streamer can connect an optional Twitch bot account (on Connections and in the welcome wizard) that sends Requestify's chat replies, falling back to the streamer when the bot cannot send. Every deferred plan-4/plan-5 point is fixed, and the result ships as 0.3.0.

**Architecture:**
- **Bot:** `TwitchAuth` is parameterised (secret key, scopes, an account check before saving), so the bot is a second `TwitchAuth` plus a second `TwitchApi` whose sender is the bot.
  - A new `core/twitch/bot/TwitchBot` owns the bot's login state, health, moderator check and hourly validation.
  - `ChatReplySender` chooses the sender per reply and falls back to the streamer.
  - A `DropTracker` per sender turns undelivered messages into one Log row and, for the bot, a "blocked" problem.
- **UI:** a `TwitchBotSetupViewModel` and a `TwitchBotSection` composable inside the Twitch content that Connections and the wizard already share.
- **Deferred points:** each fix lands in the file that owns the behaviour, grouped into tasks by area (start/exit, saving, requests, updates, wizard, docs).

**Tech Stack:**
- Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Material 3 Expressive), JetBrains Runtime 21, Gradle 8.14.4.
- Ktor client (`MockEngine` in tests), kotlinx serialization.
- JUnit/kotlin-test, coroutines-test virtual time, `runComposeUiTest`/`runDesktopComposeUiTest`.
- PowerShell (the update helper), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-19-requestify-plan-6-bot-and-fixes-design.md`. Its "Deferred points" section restates every point; the ledgers it cites are in the session notes, not needed here.

## Global Constraints

- **Paths:** relative to `src/jvmMain/kotlin/nl/diments/requestify/` (main) and `src/jvmTest/kotlin/nl/diments/requestify/` (tests), unless they start with `src/`, `docs/`, `packaging/`, `.github/` or a root file name.
- **Strings:**
  - Every user-facing string exists in both `src/commonMain/composeResources/values/strings.xml` (English) and `values-nl/strings.xml` (Dutch), with identical keys and placeholders (`StringsTest`).
  - Dutch says "Kanaalpunten", never "Channel Points". Dutch app words: "Koppelingen", "Logboek", "Verder", "Ontkoppelen".
  - The Dutch copy the spec quotes is used verbatim: "Bot-account (optioneel)", "Bot koppelen", "Open de link in een privévenster en log daar in met je bot-account", "Dat is je eigen account. Log in met je bot, bijvoorbeeld in een privévenster.", "Antwoorden komen van @%1$s", "Moderator ✓", "Maak je bot moderator, dan houdt Twitch zijn berichten minder snel tegen:", "De bot moet opnieuw inloggen", "Opnieuw inloggen", "Nog niets gekoppeld".
- **Saved formats** (settings.json, queue.json and the other files under `%APPDATA%\Requestify` must stay readable by 0.2.0):
  - New fields have defaults.
  - No new values in enums saved in settings or queue files (so no new `WizardStep`).
  - New Log names are allowed (`LogStore.readAll` skips lines it cannot decode).
  - Every new or changed `@SerialName`/saved field gets a literal-JSON pin in `core/storage/SavedFormatCompatTest.kt`.
- **Bot login:**
  - Secret key `twitch.bot_refresh_token`.
  - Scopes exactly `user:write:chat user:read:moderated_channels`.
  - The bot's account is `TwitchSettings.bot: TwitchAccount? = null`.
  - The streamer's scopes and secret key stay as they are (no forced re-login).
- **Notifications** only for "action needed": the bot's login refused, and the bot blocked by the channel.
- **Version:** 0.3.0 (tag `v0.3.0`; the pre-release in checklist part E is `v0.3.1`).
- **Commits:** no `Co-Authored-By` trailer and no mention of Claude (user rule); the repo-local author e-mail is already set.
- **Test command:** from the worktree root, in PowerShell: `.\gradlew.bat jvmTest --tests "<pattern>"`; the whole suite is `.\gradlew.bat jvmTest`. Long runs go in the background with output redirected to a file.

## Review Focus

1. **The streamer approves the bot's code as themself** (the common mistake: the browser is logged in as the streamer). The refusal, nothing saved, the streamer's own login and `TwitchSettings.account` untouched, and the section saying so. Test: `TwitchBotSetupViewModelTest.ownAccountIsRefusedAndTheStreamersLoginIsUntouched` (Task 7).
2. **The bot's login expires mid-stream.** Every reply still arrives (as the streamer), exactly one notification, and when the bot logs in again replies go back to it without a restart. Tests: `ChatReplySenderTest.aBotLoginThatDiesMidStreamFallsBackAndComesBack` (Task 5) and `NotifierTest.aBotThatNeedsALoginIsNotifiedOnce` (Task 6).
3. **AutoMod vs followers-only.** A bot message held by AutoMod is not sent a second time; one dropped for followers-only is. Tests: `ChatReplySenderTest.aMessageHeldByAutoModIsNotSentAgain` and `aFollowersOnlyDropIsSentAgainAsTheStreamer` (Task 5).
4. **0.2.0 reads 0.3.0 files** after a failed update put it back: settings.json with a `bot` field and Log lines with `twitch_bot`/`twitch_chat`/`messages_dropped`, nothing moved aside, nothing lost. Tests: `SavedFormatCompatTest.aSettingsFileFromANewerVersionIsRead` and `LogStoreTest.aLineWithANameThisVersionDoesNotKnowIsSkipped` (Task 3).
5. **Update timing.** An update downloaded while offline, then the streamer goes live before the next start: never installed during the stream. A download due while live waits until offline. Tests: Task 14's `downloadWaitsUntilTheStreamIsOffline`, plus the existing start-policy tests kept green.

---

## Part A — the bot account

### Task 1: TwitchAuth for a second account

**Files:**
- Modify: `core/twitch/auth/TwitchAuth.kt`, `core/secrets/SecretKeys.kt` (the file holding `object SecretKeys`)
- Test: `core/twitch/auth/TwitchAuthTest.kt`

**Interfaces:**
- Produces:
  - `const val SecretKeys.TWITCH_BOT_REFRESH_TOKEN = "twitch.bot_refresh_token"`
  - `val TWITCH_BOT_SCOPES: List<String> = listOf("user:write:chat", "user:read:moderated_channels")`
  - `TwitchAuth(http, secrets, clock, idBaseUrl = …, requestTimeoutMs = 15_000, secretKey: String = SecretKeys.TWITCH_REFRESH_TOKEN, scopes: List<String> = TWITCH_SCOPES)`
  - `suspend fun TwitchAuth.awaitDeviceLogin(code: DeviceCode, accept: (userId: String) -> Boolean = { true }): DeviceLoginResult`
  - `DeviceLoginResult.WrongAccount(val userId: String, val login: String)`: the approved account was refused by `accept`, and nothing was saved.

- [ ] **Step 1: Write the failing tests** in `TwitchAuthTest` (use the file's `auth`/`virtual` helpers and `InMemorySecretStore`):
  - `aBotLoginUsesItsOwnSecretAndScopes`
    - Build `TwitchAuth(..., secretKey = SecretKeys.TWITCH_BOT_REFRESH_TOKEN, scopes = TWITCH_BOT_SCOPES)`.
    - `startDeviceLogin()`: form `scopes` == `"user:write:chat user:read:moderated_channels"`.
    - After `awaitDeviceLogin`: `secrets.get(TWITCH_BOT_REFRESH_TOKEN) == "r1"` and `secrets.get(TWITCH_REFRESH_TOKEN) == null`.
  - `aRefusedAccountIsNotSaved`
    - `awaitDeviceLogin(code, accept = { it != "42" })` (validate answers user 42) == `DeviceLoginResult.WrongAccount("42", "diments")`.
    - The secret is null, `status.value == TwitchAuthStatus.LoggedOut` and `token() == TokenResult.LoggedOut`.
  - `aRefusedAccountLeavesAnEarlierLoginAlone`
    - The secret starts as "r0" (status LoggedIn).
    - After a refused login the secret is still "r0" and the status is LoggedIn.
  - `anAccountThatCannotBeReadSavesNothing`
    - `/oauth2/validate` answers 500, so the result is `Failed`.
    - The secret is null and the status is LoggedOut.
    - Today this saves first, then rolls back. Assert no `put` reached the store: a recording `SecretStore` counts puts == 0.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*TwitchAuthTest*"`. Expected: the four new tests fail to compile (no `secretKey`, `accept`, `WrongAccount`).

- [ ] **Step 3: Implement**
  - Replace every hard-coded `SecretKeys.TWITCH_REFRESH_TOKEN` in `TwitchAuth` with `secretKey`, and every `TWITCH_SCOPES` with `scopes`.
  - `finishLogin(startGeneration, access, expiresIn, refresh, accept)` now looks up the account **first**:
    - `whoAmI(access)` not `Known` → `Failed("could not read the Twitch account")`, nothing saved.
    - `!accept(userId)` → `WrongAccount`, nothing saved.
    - Then the existing synchronized block saves and switches state, and returns `Success`.
  - `rollBackLogin` becomes unused: delete it.
  - Update the class KDoc: "a Twitch login (the broadcaster's or the bot's)".

- [ ] **Step 4: Run** `.\gradlew.bat jvmTest --tests "*TwitchAuthTest*" --tests "*TwitchSetupViewModelTest*" --tests "*TwitchSessionTest*"`. Expected: PASS, the old tests included.

- [ ] **Step 5: Commit** `feat: a Twitch login can be the bot's, and refuses an account before saving it`

### Task 2: TwitchApi sends as a given sender, reads why a message was dropped, and checks moderators

**Files:**
- Modify: `core/twitch/TwitchApi.kt`
- Test: `core/twitch/TwitchApiTest.kt`, `core/twitch/chat/ChatTestSupport.kt` (test helper)

**Interfaces:**
- Produces:
  - `data class DropReason(val code: String, val message: String)`
  - `data class SentChat(val messageId: String, val isSent: Boolean, val dropReason: DropReason? = null)`
  - `suspend fun TwitchApi.sendChat(message: String, replyTo: String?, senderId: String? = null): TwitchResult<SentChat>`. `sender_id` is `senderId ?: broadcaster`; `broadcaster_id` stays the broadcaster.
  - `suspend fun TwitchApi.moderates(userId: String): TwitchResult<Boolean>`
    - `GET moderation/channels?user_id=<userId>&first=100`, following `pagination.cursor` with `after`, at most `MAX_PAGES` pages.
    - True when any `data[].broadcaster_id` equals the broadcaster; `LoggedOut` without a broadcaster.
  - Test helper `RecordingTwitchApi`, changed:
    - `RecordingTwitchApi(token: suspend (Boolean) -> TokenResult = { TokenResult.Ok("tok") })`.
    - `Sent(message, replyTo, senderId: String = BROADCASTER_ID)`, which records the body's `sender_id`.
    - `val drops = ConcurrentLinkedQueue<DropReason>()`: the next 200-answers become `is_sent:false` with that `drop_reason`.

- [ ] **Step 1: Write the failing tests** in `TwitchApiTest`:
  - `sendChatSendsAsTheGivenSender`: the body has `sender_id == "bot1"` and `broadcaster_id == "b1"` for `sendChat("hi", null, senderId = "bot1")`.
  - `sendChatReadsWhyAMessageWasNotSent`
    - Response: `{"data":[{"message_id":"","is_sent":false,"drop_reason":{"code":"followers_only_mode","message":"Followers only"}}]}`.
    - Expected: `Ok(SentChat("", false, DropReason("followers_only_mode", "Followers only")))`.
  - `moderatesFindsTheChannelOnALaterPage`
    - Page 1: `{"data":[{"broadcaster_id":"x"}],"pagination":{"cursor":"c1"}}`. Page 2 (asked with `after=c1`): `{"data":[{"broadcaster_id":"b1"}],"pagination":{}}`.
    - Expected: `Ok(true)`, and the query had `user_id=bot1`.
  - `moderatesIsFalseWhenTheChannelIsNotListed`: `Ok(false)`.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*TwitchApiTest*"`. Expected: compile failure (no `senderId`, `DropReason`, `moderates`).

- [ ] **Step 3: Implement** the signatures above.
  - `SentDto` gets `@SerialName("drop_reason") val dropReason: DropReasonDto? = null` and `message_id` with default `""`.
  - New DTOs `ModeratedChannelsDto(data, pagination)`. Paginate like `redemptions`.
  - Then update `RecordingTwitchApi` as listed in Interfaces.

- [ ] **Step 4: Run** `.\gradlew.bat jvmTest --tests "*TwitchApiTest*" --tests "*ChatReplySenderTest*" --tests "*TwitchChat*"`. Expected: PASS.

- [ ] **Step 5: Commit** `feat: Twitch chat can be sent as another account, and says why a message was dropped`

### Task 3: Saved formats for the bot (and the update pins, F-13)

**Files:**
- Modify: `core/settings/TwitchSettings.kt`, `core/log/LogRecords.kt`, `core/log/Logbook.kt` (`ConnectionSnapshot` only)
- Test: `core/storage/SavedFormatCompatTest.kt`, `core/log/LogStoreTest.kt`

**Interfaces:**
- Produces:
  - `TwitchSettings.bot: TwitchAccount? = null` (the last field).
  - `LogService.TwitchBot` (`@SerialName("twitch_bot")`).
  - `LogService.TwitchChat` (`@SerialName("twitch_chat")`): the streamer's own sending, kept apart from `Twitch` (the EventSub connection) so the Logbook's per-service outage tracking never closes one with the other.
  - `LogConnectionState.MessagesDropped` (`@SerialName("messages_dropped")`, does not close an outage).
  - `ConnectionSnapshot.MessagesDropped`.

- [ ] **Step 1: Write the failing tests:**
  - `SavedFormatCompatTest`, pinned to literal JSON in the file's existing style:
    - `aTwitchBotIsSavedAsAnAccount`: `TwitchSettings(bot = TwitchAccount("7", "mybot", "MyBot"))` encodes with `"bot":{"userId":"7","login":"mybot","displayName":"MyBot"}`, and decodes back.
    - `newLogNamesArePinned`: `twitch_bot`, `twitch_chat`, `messages_dropped`, in one `ConnectionChanged` line each.
    - **F-13:**
      - `appUpdatedAndUpdateFailedArePinned`: the literal lines of `AppUpdated` and `UpdateFailed`, with both `exitCode` and `reason`.
      - `aPendingUpdateIsPinned`: `PendingUpdate` with `from`, in literal JSON (use the class's real field names from `core/update/`).
  - `aSettingsFileFromANewerVersionIsRead`: `{"twitch":{"bot":null,"someFutureField":1},"anotherFutureField":"x"}` decodes with `AppJson.instance` into `Settings` without an exception.
  - `LogStoreTest.aLineWithANameThisVersionDoesNotKnowIsSkipped`
    - Append a valid `ConnectionChanged(..., LogService.Twitch, Lost)` line to a day file.
    - Add the same line with `"twitch"` replaced by `"some_future_service"`.
    - `readAll()` returns only the first.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*SavedFormatCompatTest*" --tests "*LogStoreTest*"`. Expected: compile failure for the new names. The F-13 and newer-version tests may already pass, which is fine: they pin today's behaviour.

- [ ] **Step 3: Implement** the fields and enum values.
  - Every exhaustive `when` that stops compiling gets the new value:
    - `LogScreen` service label: `TwitchBot` → `"Twitch-bot"`, `TwitchChat` → `"Twitch-chat"`.
    - State text: `log_conn_messages_dropped`, "Berichten tegengehouden: %1$s" / "Messages held back: %1$s", filled from the record's `detail`.
  - Logbook mapping: `ConnectionSnapshot.MessagesDropped` → `LogConnectionState.MessagesDropped`.

- [ ] **Step 4: Run** `.\gradlew.bat jvmTest --tests "*SavedFormatCompatTest*" --tests "*LogStoreTest*" --tests "*Log*"`. Expected: PASS.

- [ ] **Step 5: Commit** `feat: the bot account and its Log names are saved, and the update records are pinned`

### Task 4: DropTracker and TwitchBot

**Files:**
- Create: `core/twitch/chat/DropTracker.kt`, `core/twitch/bot/TwitchBot.kt`
- Test: `core/twitch/chat/DropTrackerTest.kt`, `core/twitch/bot/TwitchBotTest.kt`; extend `core/twitch/FakeTwitch.kt`.
  - `var userId = "42"`, `var login = "diments"` and `var displayName = "DIMENTS"`, used by `/oauth2/validate` and `/helix/users`.
  - `val moderatedChannels = mutableListOf<String>()`, served at `/helix/moderation/channels`.
  - `val sentChats = CopyOnWriteArrayList<String>()`, the raw bodies of `POST /helix/chat/messages`, answered `is_sent:true`.

**Interfaces:**
- Consumes:
  - `DropReason` and `TwitchApi.moderates` (Task 2).
  - `TwitchSettings.bot` (Task 3).
  - `TwitchAuth(secretKey, scopes)` (Task 1).
- Produces:
  - `fun isHeldByModeration(reason: DropReason): Boolean`
    - True when `code` contains `automod` (ignore case), or `code` starts with `msg_rejected`, or `message` contains `automod` (ignore case).
    - Ruling for the spec's "left to the builder": `msg_rejected*` is Twitch's "checked by mods" / "conflicts with moderation settings". Re-sending as the broadcaster would bypass the channel's own moderation.
  - `class DropTracker`:
    - `val dropping: StateFlow<String?>`: `reason.message.ifBlank { reason.code }` of the latest drop, from the first drop until `sent()`.
    - `val inARow: Int`: drops since the last `sent()` that are not `isHeldByModeration`. Moderation holds neither count nor reset it.
    - `fun sent()` and `fun dropped(reason: DropReason)`.
  - `data class TwitchBotHealth(val loginNeeded: Boolean = false, val blockedReason: String? = null)`
  - `class TwitchBot(val auth: TwitchAuth, val api: TwitchApi, private val settings: SettingsStore, private val scope: CoroutineScope, private val validateEveryMs: Long = 3_600_000, private val blockedAfter: Int = 3)`:
    - `fun sender(): TwitchAccount?`: `settings.twitch.bot`, but only while `auth.status.value == LoggedIn`, a streamer account is saved, and `bot.userId != account.userId`; else null.
    - `val drops: DropTracker`
    - `val health: StateFlow<TwitchBotHealth>`
      - `loginNeeded`: bot saved, streamer account saved, and `auth.status != LoggedIn`.
      - `blockedReason`: the reason of a `Forbidden` since the last sent message, else `drops.dropping` once `drops.inARow >= blockedAfter`, else null.
    - `val moderator: StateFlow<Boolean?>` (null while unknown).
    - `fun onSent()`, `fun onDropped(reason: DropReason)`, `fun onForbidden(message: String)` and `fun onLoginRefused()` (calls `auth.markLoginNeeded("Twitch refused the bot's login")`).
    - `suspend fun checkModerator()`: `api.moderates(botId)`; `Ok(v)` sets `moderator`, anything else leaves it.
    - `fun start()` and `fun stop()`: while the bot's auth is `LoggedIn` and a bot is saved, `auth.validate()` every `validateEveryMs`, and after each `Ok` a `checkModerator()`. Same pattern as `TwitchSession.start`.
    - `fun reset()`: clears drops, the Forbidden flag and `moderator`. Called on connect and disconnect.

- [ ] **Step 1: Write the failing tests**
  - `DropTrackerTest`:
    - `aDropOpensUntilAMessageIsSent`: `dropped(DropReason("followers_only_mode", "Followers only"))` gives `dropping == "Followers only"` and `inARow == 1`. After `sent()`, null and 0.
    - `moderationHoldsNeitherCountNorReset`: drop (followers), drop (`automod_held`), drop (followers) gives `inARow == 2`.
    - `isHeldByModerationCases`:
      - True for `automod_held`, `msg_rejected` and `msg_rejected_mandatory`, and for code `x` with message "Held by AutoMod".
      - False for `followers_only_mode` and `msg_duplicate`.
  - `TwitchBotTest` builds the bot auth on a `FakeTwitch` with `secretKey = TWITCH_BOT_REFRESH_TOKEN`, and `SettingsStore` like `TwitchSetupParts`:
    - `noSenderWithoutABot`
    - `theBotIsTheSenderWhileLoggedIn`
    - `aBotThatIsTheStreamerIsNeverTheSender`
    - `aRefusedBotLoginNeedsALogin`: `onLoginRefused()` gives `health.value.loginNeeded` and `sender() == null`.
    - `threeDropsInARowBlockTheBot`: the third followers-only drop gives `blockedReason == "Followers only"`; `onSent()` clears it.
    - `aForbiddenBotIsBlockedAtOnce`: `onForbidden("banned")` gives `blockedReason == "banned"`.
    - `theModeratorCheckReadsTwitch`: `FakeTwitch.moderatedChannels += "42"` and `checkModerator()` give `moderator.value == true`.
    - `theBotIsValidatedEveryHour` (virtual time, like `TwitchSessionTest`): `fake.validations` at 0 and 3_600_000.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*DropTrackerTest*" --tests "*TwitchBotTest*"`. Expected: compile failure.

- [ ] **Step 3: Implement** both classes to the interfaces. `health` is a `combine` of `auth.status`, `settings.settings`, a private `MutableStateFlow<String?>` for Forbidden and `drops.dropping` (with `inARow` read inside), `stateIn(scope, Eagerly, …)`.

- [ ] **Step 4: Run** the same command. Expected: PASS.

- [ ] **Step 5: Commit** `feat: the Twitch bot keeps its own login, health and moderator status`

### Task 5: Replies go out as the bot, and fall back to the streamer

**Files:**
- Modify: `core/twitch/chat/ChatReplySender.kt`, `core/twitch/chat/TwitchChat.kt`
- Test: `core/twitch/chat/ChatReplySenderTest.kt`, `core/twitch/chat/TwitchChatTest.kt`

**Interfaces:**
- Consumes: `TwitchBot.sender/onSent/onDropped/onForbidden/onLoginRefused/api` (Task 4), `SentChat.dropReason`, `sendChat(senderId)` (Task 2).
- Produces:
  - `ChatReplySender(api: TwitchApi, scope: CoroutineScope, maxTries: Int = 3, bot: TwitchBot? = null, val streamerDrops: DropTracker = DropTracker())`.
  - Delivery per reply:
    1. If `bot?.sender()` is an account, try as the bot: `bot.api.sendChat(text, replyTo, senderId = account.userId)`, with today's rate-limit retries.
       - `Ok` and sent: remember the id, `bot.onSent()`, done.
       - `Ok` and not sent: `bot.onDropped(reason ?: DropReason("unknown", ""))`. If `isHeldByModeration`, done; else continue to step 2.
       - `LoginNeeded`: `bot.onLoginRefused()`, continue to step 2. `LoggedOut`: continue to step 2.
       - `Forbidden(m)`: `bot.onForbidden(m)`, continue to step 2.
       - Anything else (still rate-limited after `maxTries`, `Failed`, `BadRequest`, `NotFound`): dropped, as today.
    2. As the streamer, with today's rate-limit retries:
       - `Ok` and sent: remember the id, `streamerDrops.sent()`.
       - `Ok` and not sent: `streamerDrops.dropped(reason ?: DropReason("unknown", ""))`.
       - Anything else: dropped, as today.
  - `TwitchChat.handle` skips a message whose `chatterId` equals `settings.twitch.bot?.userId`, before `isOwnMessage`.

- [ ] **Step 1: Write the failing tests** in `ChatReplySenderTest`. Use two `RecordingTwitchApi` (streamer, bot) and a `TwitchBot` whose auth has a saved bot secret, with settings account `42` and bot `7`. Use the file's `awaitUntil`/`settle`.
  - `repliesGoOutAsTheBotWhenOneIsConnected`: the bot api gets `Sent("hi", "m1", senderId = "7")` and the streamer api gets nothing.
  - `aForbiddenBotFallsBackToTheStreamerAtOnce`: the bot answers 403, the streamer api gets `Sent("hi", "m1")`, and `bot.health.value.blockedReason != null`.
  - `aMessageHeldByAutoModIsNotSentAgain`: the bot drops `DropReason("automod_held", "")`, and after `settle()` the streamer api has no sends.
  - `aFollowersOnlyDropIsSentAgainAsTheStreamer`: the bot drops `followers_only_mode`, and the streamer sends the same text and replyTo.
  - `aBotLoginThatDiesMidStreamFallsBackAndComesBack`
    - The bot answers 401 twice, so `LoginNeeded`. The streamer sends reply 1, and `bot.health.value.loginNeeded`.
    - Reply 2 goes straight to the streamer (no bot attempt).
    - Then save a new bot secret through `bot.auth`. Use a successful device login on the bot's `FakeTwitch`, or test-only `InMemorySecretStore.put` plus a new `TwitchAuth` if simpler; record which in the ledger.
    - Reply 3 goes out as the bot.
  - `theStreamersOwnDropIsRecorded`: no bot, the streamer drops `slow_mode`, so `streamerDrops.dropping.value != null`. A sent reply clears it.
  - `withoutABotNothingChanges`: the existing tests stay green unchanged.
- [ ] In `TwitchChatTest`, add `messagesOfTheBotAreNotCommands`: settings `bot = TwitchAccount("7", …)`, a `!sr` from chatter `7`, and no intake.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*ChatReplySenderTest*" --tests "*TwitchChatTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces. Extract today's retry loop into `private suspend fun attempt(api: TwitchApi, reply: Reply, senderId: String?): TwitchResult<SentChat>`, which returns the last result.

- [ ] **Step 4: Run** `.\gradlew.bat jvmTest --tests "*ChatReplySenderTest*" --tests "*TwitchChat*" --tests "*Bits*" --tests "*ChannelPoints*" --tests "*DonationHandler*"`. Expected: PASS.

- [ ] **Step 5: Commit** `feat: chat replies come from the bot when one is connected, and from you when it cannot send`

### Task 6: The bot in the chip, the tray, notifications and the Log

**Files:**
- Modify:
  - `core/health/ConnectionProblems.kt`, `ui/common/ConnectionChip.kt` and `desktop/Notifier.kt`.
  - `core/log/Logbook.kt`, `ui/log/LogScreen.kt`.
  - `AppGraph.kt` and both `strings.xml`.
- Test: `core/health/ConnectionProblemsTest.kt`, `desktop/NotifierTest.kt`, `core/log/LogbookTest.kt`, `AppGraphTest.kt`

**Interfaces:**
- Consumes: `TwitchBot.health`, `TwitchBot.drops`, `ChatReplySender.streamerDrops` (Tasks 4–5), `ConnectionSnapshot.MessagesDropped` (Task 3).
- Produces:
  - `ConnectionProblem.TwitchBotLoginNeeded` (data object) and `ConnectionProblem.TwitchBotBlocked(val reason: String)`.
  - `TwitchHealth(..., val botLoginNeeded: Boolean = false, val botBlockedReason: String? = null)`.
  - Order in `connectionProblems`: `TwitchBotLoginNeeded` right after `TwitchLoginNeeded`; `TwitchBotBlocked` right after `TwitchDisconnected`.
  - `Notice.TwitchBotLoginNeeded` and `Notice.TwitchBotBlocked` (data objects: a changed reason is no new notification).
  - `data class ConnectionReport(val service: LogService, val snapshot: ConnectionSnapshot, val detail: String? = null)`.
  - `LogInputs.reports: Flow<ConnectionReport> = emptyFlow()`, as the last parameter, handled like `connections` but with `detail`.
  - AppGraph:
    - `twitchBot` (the bot `TwitchAuth`, a bot `TwitchApi` with `token = botAuth::token` and `broadcasterId` = the streamer's id).
    - `chatSender` built with `bot = twitchBot`.
    - `problems` uses `combine(twitchSession.health, twitchBot.health) { t, b -> t.copy(botLoginNeeded = b.loginNeeded, botBlockedReason = b.blockedReason) }`.
    - `twitchBot.start()` after `twitchSession.start()`, and `stop()` before it.
    - Log `reports`, merged:
      - bot: `LoginExpired` when `loginNeeded`, else `MessagesDropped(detail)` while `drops.dropping != null`, else `Up`, under `LogService.TwitchBot`;
      - streamer: `MessagesDropped`/`Up` from `chatSender.streamerDrops.dropping` under `LogService.TwitchChat`.
- Strings:

  | Key | English | Dutch |
  |---|---|---|
  | `connection_twitch_bot_login_needed` | "Twitch bot: log in again" | "Twitch-bot: log opnieuw in" |
  | `connection_twitch_bot_blocked` | "Chat is holding the bot back" | "Chat houdt de bot tegen" |
  | `notice_twitch_bot_login_title` | "The bot needs to log in again" | "De bot moet opnieuw inloggen" |
  | `notice_twitch_bot_blocked_title` | "Your chat is holding the bot back" | "Je chat houdt de bot tegen" |
  | `notice_twitch_bot_text` | "Replies now come from you. Have a look at Connections." | "Antwoorden komen nu van jou. Kijk even bij Koppelingen." |

- [ ] **Step 1: Write the failing tests:**
  - `ConnectionProblemsTest`:
    - `aBotThatNeedsALoginComesRightAfterTheStreamersLogin`
    - `aBlockedBotIsAProblem`
    - `aBotThatIsNotConnectedIsNoProblem`
  - `NotifierTest`:
    - `aBotThatNeedsALoginIsNotifiedOnce`: the problem list emits it twice in a row plus once with an extra problem, and `sent` holds exactly one `Notice.TwitchBotLoginNeeded`.
    - `aBlockedBotIsNotifiedOnceWhateverTheReason`: reason "a", then "b", gives one notice.
  - `LogbookTest`:
    - `droppedMessagesAreOneRowUntilOneIsSent`: reports `TwitchChat MessagesDropped "Slow mode"`, again `"Slow mode"`, then `Up`. One row with states `[MessagesDropped, Restored]` and detail "Slow mode".
    - `theBotsOutageDoesNotCloseTheStreamersTwitchOutage`
      - Emit `Twitch Down` (connections), `TwitchBot LoginExpired` (reports), then `TwitchBot Up`.
      - The Twitch row is still open: last state `Lost`.
  - `AppGraphTest`, `aConnectedBotSendsTheReplies` (only if `AppGraphTest` already drives chat end to end; otherwise skip it and ledger why): with a saved bot secret and `TwitchSettings.bot`, a `!song` reply's request body has `sender_id` = the bot.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*ConnectionProblemsTest*" --tests "*NotifierTest*" --tests "*LogbookTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces. The chip and tray looks use `AppIcons.Warning` with `Tone.Problem`, and `noticeText` maps both new notices to their title plus `notice_twitch_bot_text`.

- [ ] **Step 4: Run** `.\gradlew.bat jvmTest`, the whole suite, in the background with output to the workspace. Expected: all green.

- [ ] **Step 5: Commit** `feat: a bot that cannot send shows in the title bar, the tray, a notification and the Log`

### Task 7: The bot section on Connections

**Files:**
- Create: `ui/setup/TwitchBotSetupViewModel.kt`, `ui/setup/TwitchBotSection.kt`
- Modify:
  - `ui/setup/TwitchSetupViewModel.kt` (only `TwitchLoginState`).
  - `ui/setup/TwitchCard.kt`: `CodePanel` takes callbacks; `TwitchCard`/`TwitchSetupContent` take the bot view model; the section sits below the chat row.
  - `ui/setup/SpotifySetupScreen.kt` (the `TwitchCard(...)` call), `AppGraph.kt` and both `strings.xml`.
  - `src/jvmTest/.../ui/Renders.kt`: the bot section's four states.
- Test: `ui/setup/TwitchBotSetupViewModelTest.kt`, `ui/setup/TwitchCardTest.kt`

**Interfaces:**
- Consumes: `TwitchBot` (Task 4), `awaitDeviceLogin(accept)` and `DeviceLoginResult.WrongAccount` (Task 1), `TwitchApi.me()` on the bot's api.
- Produces:
  - `TwitchLoginState.OwnAccount` (data object): the bot's code was approved by the streamer's own account.
  - `class TwitchBotSetupViewModel(private val bot: TwitchBot, private val settings: SettingsStore, private val scope: CoroutineScope, private val openBrowser: (String) -> Unit, private val copyToClipboard: (String) -> Unit)`:
    - State:
      - `login: StateFlow<TwitchLoginState>`
      - `account: StateFlow<TwitchAccount?>` (`settings.twitch.bot`)
      - `authStatus: StateFlow<TwitchAuthStatus>`
      - `moderator: StateFlow<Boolean?>`
      - `lastDrop: StateFlow<String?>` (`bot.drops.dropping`)
      - `saveFailed: StateFlow<Boolean>`
    - `onConnect()`, mirroring `TwitchSetupViewModel.onLogin`:
      - It uses `bot.auth.awaitDeviceLogin(code, accept = { it != settings.twitch.account?.userId })`.
      - `Success`: the display name comes from `bot.api.me()` (else the login). Save `twitch.bot = TwitchAccount(userId, login, displayName)`, then `bot.reset()` and `bot.checkModerator()`.
        - A failed save undoes the login (`bot.auth.logout()`) and gives `Failed`.
      - `WrongAccount` gives `OwnAccount`.
    - `onCancel()`, `onOpenBrowser()`, `onCopyCode()`, and `onCopyLink()` (copies `verificationUri`).
    - `onDisconnect()`: `twitch.bot = null` first; when that cannot be saved nothing changes and `saveFailed` is set. Then `bot.auth.logout()`; when that throws, the account comes back. Then `bot.reset()`.
    - `onCopyModCommand()` copies `"/mod ${account.login}"`; `onCheckModerator()`.
  - `@Composable internal fun TwitchBotSection(vm: TwitchBotSetupViewModel, compact: Boolean = false, code: @Composable (String) -> Unit = { TwitchCode(it) })`
  - `TwitchCard(vm: TwitchSetupViewModel, bot: TwitchBotSetupViewModel)` and `TwitchSetupContent(vm, bot: TwitchBotSetupViewModel, code = …, compactBot: Boolean = false)`. The section shows only in `LoggedInContent`, so only while the streamer has an account.
  - `CodePanel(login, onCopyCode, onOpenBrowser, onCancel, codeText, note: (@Composable () -> Unit)? = null, onCopyLink: (() -> Unit)? = null)`, made internal.
- Copy (English / Dutch); the spec's quoted Dutch lines are verbatim:

  | Key | English | Dutch |
  |---|---|---|
  | `twitch_bot_title` | "Bot account (optional)" | "Bot-account (optioneel)" |
  | `twitch_bot_intro` | "Let a separate Twitch account, your bot, send the replies in chat." | "Laat een apart Twitch-account, je bot, de antwoorden in de chat sturen." |
  | `twitch_bot_intro_compact` | "Optional: let your bot send the replies." | "Optioneel: laat je bot de antwoorden sturen." |
  | `twitch_bot_connect` | "Connect bot" | "Bot koppelen" |
  | `twitch_bot_private_window` | "Open the link in a private window and log in there with your bot account" | (spec) |
  | `twitch_bot_copy_link` | "Copy link" | "Link kopiëren" |
  | `twitch_bot_own_account` | "That is your own account. Log in with your bot, for example in a private window." | (spec) |
  | `twitch_bot_replies_from` | "Replies come from @%1$s" | "Antwoorden komen van @%1$s" |
  | `twitch_bot_disconnect` | "Disconnect" | "Ontkoppelen" |
  | `twitch_bot_moderator` | "Moderator ✓" | "Moderator ✓" |
  | `twitch_bot_mod_tip` | "Make your bot a moderator, so Twitch holds its messages back less often:" | (spec) |
  | `twitch_bot_check_again` | "Check again" | "Opnieuw kijken" |
  | `twitch_bot_last_drop` | "Last message not sent: %1$s" | "Laatste bericht niet verstuurd: %1$s" |
  | `twitch_bot_login_expired` | "The bot needs to log in again" | "De bot moet opnieuw inloggen" |
  | `twitch_bot_login_again` | "Log in again" | "Opnieuw inloggen" |

- [ ] **Step 1: Write the failing tests:**
  - `TwitchBotSetupViewModelTest`, with a `FakeTwitch` for the bot and settings account `42`:
    - `connectingSavesTheBotAccount`: the fake's user is `7`/`mybot`/`MyBot`. After `onConnect()` the settings bot is `TwitchAccount("7", "mybot", "MyBot")`, the login is `Idle`, and the bot secret is saved.
    - `ownAccountIsRefusedAndTheStreamersLoginIsUntouched`
      - The fake's user is `42`.
      - Afterwards: `login.value == OwnAccount`, `settings.twitch.bot == null` and `settings.twitch.account?.userId == "42"`.
      - Both secrets: the bot secret is null, and the streamer's secret is unchanged ("r0").
    - `disconnectForgetsTheBot`: the bot is null and the bot secret is null.
    - `aDisconnectThatCannotBeSavedChangesNothing`: a settings file that cannot be written gives `saveFailed` true, and the bot is still saved.
    - `copyLinkAndModCommand`: `copied == listOf(verificationUri, "/mod mybot")`.
  - `TwitchCardTest`:
    - `theBotSectionOffersToConnect`
    - `aConnectedBotShowsWhoRepliesAndTheModTip`: the text "Replies come from @MyBot" and the `/mod mybot` tip with a copy button.
    - `theBotsDeadLoginShowsTheBanner`

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*TwitchBotSetupViewModelTest*" --tests "*TwitchCardTest*"`. Expected: compile failure.

- [ ] **Step 3: Implement** to the Interfaces. Layout:
  - `SectionHeader(twitch_bot_title, AppIcons.…)` with a fitting existing icon (`Chat` or `Person`).
  - Then, by state:
    - not connected: intro and button;
    - logging in: `CodePanel` with the private-window note and Copy link;
    - own account: `ErrorText`;
    - connected: "Antwoorden komen van @…", Ontkoppelen, moderator ✓ or the tip with the `/mod` command and Copy, "Opnieuw kijken", and the last drop;
    - dead login: the banner, using the `DeadLoginBanner` pattern with the bot's strings.
  - Add the four states to `Renders.kt`, named `bot-*`.

- [ ] **Step 4: Run**
  - `.\gradlew.bat jvmTest --tests "*TwitchBotSetupViewModelTest*" --tests "*TwitchCardTest*" --tests "*StringsTest*"`. Expected: PASS.
  - Then `$env:REQUESTIFY_RENDERS="<workspace>/renders"; .\gradlew.bat jvmTest --tests "*Renders*" --rerun` and look at the `bot-*` PNGs.

- [ ] **Step 5: Commit** `feat: connect a bot account for chat replies on Connections`

### Task 8: The bot in the welcome wizard; donations celebrate on a passed test (F-11)

**Files:**
- Modify:
  - `ui/welcome/WelcomeWizardViewModel.kt`, `ui/welcome/WelcomeWizard.kt`.
  - `ui/setup/DonationsCard.kt`, `ui/setup/DonationsSetupViewModel.kt`.
  - `AppGraph.kt` (the `welcome` block, ~421–441).
- Test: `ui/welcome/WelcomeWizardViewModelTest.kt`, `ui/welcome/WelcomeWizardTest.kt`, `ui/setup/DonationsSetupViewModelTest.kt`

**Interfaces:**
- Consumes: `TwitchSetupContent(vm, bot, code, compactBot)` (Task 7).
- Produces:
  - `WelcomeWizardViewModel.celebrated()`: after a **Twitch** celebration it always stays on the Twitch card (Decision 7). The `twitchOffersPoints` constructor parameter and AppGraph's `offersPoints`/`twitchOffersPoints` are removed.
  - `fun DonationsCardState.confirmed(): Boolean`, in `DonationsSetupViewModel.kt`:
    - `kind != None && hasKey && health.status != KeyRefused`, and
    - `test is DonationTest.Ok || (test == DonationTest.Idle && health.status == DonationStatus.Connected)`.
    - AppGraph's wizard `donationsConnected` uses it.
    - `markExistingUser(anyConnectionSaved = …)` keeps today's looser `donationsOk` (a saved key is an existing user).
  - `DonationsSetupContent(state, vm, onChoseNone: (() -> Unit)? = null)`: "Geen" calls `vm.choose(None)` and then `onChoseNone`. The wizard passes `wizard::later`.

- [ ] **Step 1: Write the failing tests:**
  - `WelcomeWizardViewModelTest`:
    - `theTwitchCardStaysAfterItsCelebration` replaces `anAffiliateStaysForChannelPoints`: any Twitch account, then `celebrated()`, and the step is still `Twitch`. Then `continueOn()` moves on.
  - `DonationsSetupViewModelTest`:
    - `aKeyIsConfirmedOnlyOnceItsTestPassed`: a pasted key with the test still `Testing` gives `confirmed() == false`; after the fake answers OK, `true`.
    - `aSavedKeyThatConnectsIsConfirmed`: test `Idle` and health `Connected` give `true`.
  - `WelcomeWizardTest`:
    - `choosingGeenMovesTheWizardOn`: on the donations card, click "Geen"/"None", and the next card shows.
    - `theWizardTwitchCardOffersTheBot`: with a logged-in Twitch, the compact bot text and "Bot koppelen"/"Connect bot" show on the Twitch card.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*WelcomeWizard*" --tests "*DonationsSetupViewModelTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces. The wizard's Twitch card calls `TwitchSetupContent(twitch, bot, code = …, compactBot = true)`. `WelcomeWizard` gets the bot view model from `AppGraph` the same way it gets `twitch`.

- [ ] **Step 4: Run** the same command plus `--tests "*AppGraphTest*"`. Expected: PASS.

- [ ] **Step 5: Commit** `feat: the wizard offers the bot after Twitch, and donations celebrate once the key works`

---

## Part B — start, exit and crashes

### Task 9: A start during Quit waits (M-1); the crash marker is per run (M-4); shutdown is recognised by itself (F-18)

**Files:**
- Modify: `desktop/SingleInstance.kt`, `desktop/CrashGuard.kt`, `Shell.kt` (`quitApp`), `Main.kt`
- Test: `desktop/SingleInstanceTest.kt`, `desktop/CrashGuardTest.kt`, `AppCloseTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Produces:
  - `RunningInstance.markQuitting()`. From then on, `answer` replies `"quitting"` to `"show <token>"` and does not call `onShowRequested`.
    - Any other answer is still understood as before: an older instance's `"ok"` means "shown, step aside".
  - `SingleInstance.start(onShowRequested, askToShow = true, waitForLockMs = 0, quitWaitMs: Long = 15_000)`. When the lock is held and the running instance answers `"quitting"`, it keeps trying the lock for up to `quitWaitMs`. It returns `First` once it gets the lock, else `AlreadyRunning`.
  - `private fun askRunningToShow(): ShowAnswer` with `enum ShowAnswer { Shown, Quitting, NoAnswer }`.
  - `quitApp(markQuitting: () -> Unit, hideTray, hideWindow, stopGracefully, markOrderlyEnd, exit)`: `markQuitting` runs first.
    - Main passes `instance::markQuitting`.
    - The "Nu bijwerken" and install-at-start paths also call `instance.markQuitting()` before they stop.
  - `RunMarker.runId: String? = null`. `CrashGuard` makes its run id with `UUID.randomUUID()` and writes it in `markRunning()`.
    - `touch()` reads, checks and writes under `lock`, and does nothing after `markOrderlyEnd()`.
    - `markOrderlyEnd()` sets `ended = true` under `lock`. It deletes `running.json` only when the file's `runId` equals this run's.
  - `fun jvmIsShuttingDown(): Boolean` (top level in `CrashGuard.kt`): adds and removes a no-op shutdown hook; `IllegalStateException` means true.
    - `CrashGuard(..., isShuttingDown: () -> Boolean = ::jvmIsShuttingDown)`.
    - `onUncaught` treats `shuttingDown || isShuttingDown()` as the M-5 path.

- [ ] **Step 1: Write the failing tests:**
  - `SingleInstanceTest` (real sockets and threads, as the file does):
    - `aStartDuringQuitWaitsAndThenRuns`
      - The first instance calls `markQuitting()`.
      - The second `start()` runs on a thread; the first is closed after 300 ms.
      - The second returns `First` within 5 s, and the first's `onShowRequested` was never called.
    - `aQuitThatNeverEndsLeavesTheNewStartOut`: `quitWaitMs = 500` and a first instance that never closes give `AlreadyRunning`.
  - `CrashGuardTest`:
    - `aTouchAfterAnOrderlyEndWritesNothing`: `markRunning()`, `markOrderlyEnd()`, `touch()`, and no `running.json`.
    - `theEndRemovesOnlyItsOwnRunsMarker`
      - After `markRunning()`, overwrite `running.json` with another `runId` (as a newer instance would).
      - `markOrderlyEnd()` leaves the file in place.
    - `anErrorWhileTheJvmShutsDownIsNoCrashEvenBeforeOurHook`: `isShuttingDown = { true }`, then `onUncaught(...)`. No relaunch, no fatal box, no exit, and a crash file written.
    - `theJvmIsNotShuttingDownWhileRunning`: `jvmIsShuttingDown() == false`.
  - `AppCloseTest.quitHidesStopsMarksAndExitsInOrder`: the expected order starts with `"quitting"`.
  - `SavedFormatCompatTest.runMarkerIsPinned`: a literal `running.json` with `"runId":"r1"` decodes, and one without `runId` decodes too.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*SingleInstanceTest*" --tests "*CrashGuardTest*" --tests "*AppCloseTest*" --tests "*SavedFormatCompatTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces. Constant `QUIT_WAIT_MS = 15_000L`.

- [ ] **Step 4: Run** the same command plus `--tests "*AppStartTest*"`. Expected: PASS.

- [ ] **Step 5: Commit** `fix: a start during Quit waits for it, and the crash marker belongs to its own run`

### Task 10: The fatal box comes after the app stopped (M-12); the shutdown hook saves (M-14); window icons once (M-17)

**Files:**
- Modify: `desktop/CrashGuard.kt`, `Main.kt`
- Test: `desktop/CrashGuardTest.kt`

**Interfaces:**
- Produces:
  - `CrashGuard.beforeFatal: () -> Unit` (a `@Volatile var`, default `{}`). Called inside `onUncaught` right before `showFatal`, in its own try/catch (a failure there still shows the box).
    - Main sets it after the graph exists: hide the window (`shell.hideNow()`), remove the tray icon, then `runBlocking { withTimeoutOrNull(FATAL_STOP_MS) { graph.stopGracefully() } }`, with `FATAL_STOP_MS = 5_000L`.
    - A relaunch (not the box) does not call it: the new instance waits for the old lock anyway.
  - `CrashGuard.flushOnShutdown: () -> Unit` (`@Volatile var`, default `{}`). The shutdown hook calls it first, in a try/catch, then `markOrderlyEnd()`.
    - Main sets `{ runBlocking { withTimeoutOrNull(2_000) { graph.flushAll(2_000) } } }`.
  - Main: the window's `SideEffect { window.iconImages = … }` becomes `LaunchedEffect(Unit) { window.iconImages = AppIconImages.windowIcons() }`, so it runs once. The `shell.hideWindow` assignment stays where it is.

- [ ] **Step 1: Write the failing tests** in `CrashGuardTest`:
  - `theAppStopsBeforeTheFatalBox`: no installation exe (so the box, not a relaunch), `beforeFatal = { steps += "stop" }`, and `showFatal` records "box". After `onUncaught`, the steps are `["stop", "box"]` and exit is 1.
  - `aStopThatFailsStillShowsTheBox`: `beforeFatal` throws; the box still shows, and exit is 1.
  - `theShutdownHookSavesBeforeItEnds`: `flushOnShutdown` records "flush". `shutdownHook.run()`, then "flush" recorded and `running.json` gone.
  - `aSaveThatFailsInTheHookStillEndsOrderly`

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*CrashGuardTest*"`. Expected: compile failure.

- [ ] **Step 3: Implement** to the Interfaces, including the Main wiring and the icon change. Main has no test harness: the final review reads these lines.

- [ ] **Step 4: Run** `.\gradlew.bat jvmTest --tests "*CrashGuardTest*" --tests "*AppStartTest*" --tests "*AppCloseTest*"`. Expected: PASS.

- [ ] **Step 5: Commit** `fix: a fatal error stops the app before its message, and Windows shutting down still saves`

---

## Part C — saving and secrets

### Task 11: Flush cuts a back-off short (M-2); secrets are re-read outside the lock (M-3); a rejection saved later is flushed (M-9); small ones (M-17)

**Files:**
- Modify:
  - `core/storage/BackgroundWriter.kt`, `core/secrets/SecretStore.kt` (`DpapiSecretStore`).
  - `core/intake/RequestIntake.kt`, `core/playback/PlaybackController.kt` (KDoc only), `AppGraph.kt` (`flushAll`).
  - `core/twitch/StreamWatcher.kt`, `core/blacklist/BlacklistStore.kt` (KDoc only), `core/settings/Settings.kt` (`SettingsStore`).
- Test:
  - `core/storage/BackgroundWriterTest.kt`, `core/secrets/DpapiSecretStoreTest.kt`, `core/intake/RequestIntakeTest.kt`, `core/twitch/StreamWatcherTest.kt`.
  - Create `core/settings/SettingsTestSupport.kt` (test source), and update the test call sites of `SettingsStore.update`.

**Interfaces:**
- Produces:
  - **M-2:** `BackgroundWriter`'s back-off waits with `withTimeoutOrNull(wait) { wake.receive() }` on a `Channel<Unit>(CONFLATED)`. `flush(timeoutMs)` sends `wake` before it waits, so a write in back-off is tried at once. `save()` does not cut the back-off.
  - **M-3:** `DpapiSecretStore.load()` splits into:
    - `private fun readDecrypted(): Map<String, String>?`, which touches no state;
    - `@Synchronized private fun adopt(map)`, which sets `plain`, `undecryptable` and `loadFailed`.
    - `readAgainUntilItWorks()` calls `readDecrypted()` outside the monitor and `adopt` inside it. `loadFailed` becomes `@Volatile`.
  - **M-9:**
    - `saveRejectionLater`'s waits use `withTimeoutOrNull(delay) { retryNow.receive() }` (conflated channel).
    - `RequestIntake.awaitSaved()` also sends `retryNow` and waits until no rejection is waiting: a `MutableStateFlow<Int>` count, `first { it == 0 }`.
    - `AppGraph.flushAll` adds `{ withTimeoutOrNull(timeoutMs) { intake.awaitSaved() } != null }`.
    - `PlaybackController.awaitSaved` KDoc: before the saved queue is loaded it returns at once. Edits made meanwhile wait for the load and are saved with it, so this call does not cover them.
  - **M-17:**
    - **reReadPending:** `StreamWatcher.readAgainAt` launches its re-read in the run's own job, so `stop()` cancels it. `start()` and `stop()` set `reReadPending = false` under `lock`.
    - **BlacklistStore KDoc:** a change is refused only while `blacklist.json` could not be read at start; a failed write keeps the change, retries in the background and reports itself in `saveProblem`.
    - **SettingsStore.update:** removed from production code; `tryUpdate` is the API. The tests use `fun SettingsStore.setForTest(f: (Settings) -> Settings)` (`assertTrue(tryUpdate(f), "the settings could not be saved")`). Its call sites are:
      - `RewardManagerTest`, `TwitchSessionTest`, `TwitchSetupParts`, `AppGraphTest`, `RulesScreenTest`.
      - `TwitchChatTest`, `TwitchChatModTest`, `BitsSourceTest`, `ChannelPointsSourceTest`.

- [ ] **Step 1: Write the failing tests:**
  - `BackgroundWriterTest.flushCutsTheBackoffShort` (virtual time, the file's `context()`):
    - The first write throws and `backoffMs = listOf(30_000)`; then the write works.
    - `flush(1_000)` returns true, and the second write happened before 1 s of virtual time.
  - `DpapiSecretStoreTest.aReReadDoesNotHoldTheLock` (real threads):
    - The store is built on an unreadable file, with `rereadEveryMs = 10` and `SaveContext(scope, clock, Dispatchers.IO)`.
    - `unprotect` blocks on a `CountDownLatch` during the re-read.
    - While it is blocked, `get("x")` on the test thread returns within 100 ms. Then release the latch.
  - `RequestIntakeTest.aRejectionSavedLaterIsPartOfAwaitSaved`:
    - `rejected.json` is unreadable at start (locked as in the existing rejected tests), and a paid request is rejected.
    - Then release the lock and advance until the store has re-read.
    - `withTimeoutOrNull(1_000) { intake.awaitSaved() }` is non-null (not waiting the 5 s retry), and the entry is in `rejected.json`.
  - `StreamWatcherTest.aStopAndStartDoesNotBlockTheNewRunsReRead`: start a re-read wait, `stop()`, `start()`, a contradicting read, and the new run reads again after its window.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*BackgroundWriterTest*" --tests "*DpapiSecretStoreTest*" --tests "*RequestIntakeTest*" --tests "*StreamWatcherTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces. Then replace every test-side `settings.update {` with `settings.setForTest {` and delete `SettingsStore.update`.

- [ ] **Step 4: Run** the whole suite in the background: `.\gradlew.bat jvmTest > <workspace>\task11.txt`. Expected: green.

- [ ] **Step 5: Commit** `fix: Quit retries a failed save at once, secrets re-read off the lock, and a late rejection is flushed`

---

## Part D — requests and sources

### Task 12: Sweep vs hand-over (M-8); the expiry notice per run (M-10); a cleared currency (M-15); RewardManagerTest on virtual time (M-16)

**Files:**
- Modify: `core/intake/RequestIntake.kt`, `desktop/Notifier.kt`, `ui/setup/DonationsSetupViewModel.kt`
- Test: `core/intake/RequestIntakeTest.kt`, `desktop/NotifierTest.kt`, `ui/setup/DonationsSetupViewModelTest.kt`, `core/twitch/points/RewardManagerTest.kt`

**Interfaces:**
- Produces:
  - **M-8:** after `leaveQueueOnceListed`'s `removeMatching`, any id that was listed but neither removed nor still queued, and that is now the controller's handed-over or playing request (from `loadedSnapshot()`), is taken out of Rejected again with `rejected.remove(id)`.
    - Ruling against the spec's wording: the spec says "the snapshot is taken under the lock". Holding the playback lock across a disk save (up to `SWEEP_SAVE_WAIT_MS`) would stall playback. Undoing the listing gives the same outcome.
  - **M-10:** `Notifier` keeps `private var expiryNotifiedThisRun: KeyExpiryNotice?`. `onKeyExpiring` skips when the saved **or** the in-memory notice covers the stage, and sets the in-memory one whether or not `tryUpdate` saved.
  - **M-15:** `DonationsSetupViewModel.runTest`: `channelCurrency` answering `Ok(null)` saves `streamElementsCurrency = null`; `Refused`/`Failed` leave it.
  - **M-16:** `RewardManagerTest.aNewerWantedStateWinsOverAPendingRetry` runs under `runTest` on `backgroundScope`, with the `FakeRewardsHelix` engine on the test dispatcher. It advances virtual time instead of `eventually`/`Thread.sleep`. The other tests keep their harness.

- [ ] **Step 1: Write the failing tests:**
  - `RequestIntakeTest.aRequestHandedOverDuringASweepIsNotListedAsRejected`
    - Block the `rejected.json` save for a moment (as `aSweptRequestLeavesTheQueueOnlyOnceItsEntryIsOnDisk` does).
    - Hand the request over meanwhile (advance the fake player to its hand-over), then let the save through.
    - The request is not in `rejected.entries`.
  - `NotifierTest.anUnsavableStageIsNotSentAgainThisRun`: the settings cannot be written (`.tmp` directory), the key expires in 5 days, and two ticks 1 h apart give one `KeyExpiring`.
  - `DonationsSetupViewModelTest.aChannelWithoutACurrencyClearsTheSavedOne`: settings currency "EUR", the fake tipping answer has no `currency`, and after the test it is `null`.
  - M-16: rewrite the test as above. It must pass, and fail if `RewardManager` sent a stale `is_paused=true` after the newer state. Check that once by temporarily inverting the final assertion, and note it in the ledger.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*RequestIntakeTest*" --tests "*NotifierTest*" --tests "*DonationsSetupViewModelTest*" --tests "*RewardManagerTest*"`. Expected: the three new tests fail.

- [ ] **Step 3: Implement** to the Interfaces.

- [ ] **Step 4: Run**
  - The same command. Expected: PASS.
  - Then wrap the M-16 test body in a temporary `repeat(50)` and run `--tests "*RewardManagerTest.aNewerWantedStateWinsOverAPendingRetry"`. Expected: PASS. Remove the `repeat` before committing.

- [ ] **Step 5: Commit** `fix: a handed-over request leaves Rejected, the expiry notice keeps its step, and a vanished currency is cleared`

---

## Part E — updates

### Task 13: Update states: a failed check keeps Ready (F-2), one install per run (F-3), Updated only for the pending version (F-4), the first failure logged (F-1), "Wat is er nieuw" always (F-12)

**Files:**
- Modify:
  - `core/update/UpdateService.kt`, `ui/settings/AboutCard.kt`.
  - `core/log/Logbook.kt` (extract the record mapping), `AppGraph.kt`, `Main.kt` and both `strings.xml`.
- Test: `core/update/UpdateServiceTest.kt`, `ui/settings/AboutCardTest.kt`, `AppGraphTest.kt`

**Interfaces:**
- Produces:
  - **F-2:** `UpdateService.checkFailedNote: StateFlow<Boolean>`.
    - A manual check that fails while the state is `Ready`/`InstallFailed` keeps that state and sets the note.
    - Without such a state, it becomes `CheckFailed(true)` as today.
    - Any check that starts clears the note.
    - `AboutCard(..., checkFailedNote: Boolean)` shows `update_check_failed_note` ("Zoeken naar updates lukte nu niet." / "Checking for updates did not work just now.") under Ready/InstallFailed.
  - **F-3:** `installNow()` emits at most one install request per run (`AtomicBoolean`). `fun installRequestFailed()` resets it, and Main calls it when `UpdateLauncher.launch` returns false.
  - **F-4:** `reconcile`: `Updated` and `JustUpdated` only when `running == target`. `running > target` (a newer version installed by hand) clears the pending update with no event and no card.
  - **F-1:**
    - `val UpdateService.startFailure: UpdateEvent.InstallFailed?`: the failure `reconcile` reported at this start.
    - `internal fun updateRecord(event: UpdateEvent, at: Long): LogRecord` is extracted from `Logbook.onUpdate`, which uses it.
    - `suspend fun AppGraph.logNow(event: UpdateEvent)`: `logStore.start()`, `logStore.append(updateRecord(event, now))`, then `withTimeoutOrNull(2_000) { logStore.stop() }`.
    - Main's install-at-start path, after `UpdateLauncher.launch` returned true and before `exitProcess`, does `graph.updates.startFailure?.let { runBlocking { graph.logNow(it) } }`. This run never starts its Logbook, so the row is written once.
  - **F-12:** `AboutViewModel.runningReleasePage(): String`. For a release version it is `https://github.com/DIMENTS/Requestify/releases/tag/v<version>`; for "dev" or a local build, `https://github.com/DIMENTS/Requestify/releases`.
    - "Wat is er nieuw" shows in every state except `Off`.
    - In `Ready` it opens the new release's page (as today); otherwise it opens `runningReleasePage()`.

- [ ] **Step 1: Write the failing tests:**
  - `UpdateServiceTest`, in the file's style:
    - `aFailedManualCheckKeepsReady`
    - `aFailedManualCheckWithoutAnUpdateSaysSo`
    - `installNowAsksOncePerRun`: two `installNow()` give one emission. After `installRequestFailed()`, a third gives a second emission.
    - `aNewerVersionInstalledByHandShowsNoCard`: pending 0.2.1 with `attempts = 1`, running 0.2.2. No `Updated` event, `justUpdated` null, pending cleared.
    - `theStartKnowsItsFailedInstall`: the setup of `aFailedInstallIsReportedAndRetriedOnce`, plus `startFailure == InstallFailed(0.2.1, 1603)`.
  - `AppGraphTest.logNowWritesTheRowWithoutTheLog`: build the graph as `exitCalls()` does, without `start()`. `logNow(UpdateEvent.InstallFailed(AppVersion.parse("0.2.1")!!, 1603))`, then `LogStore(...).readAll()` has one `UpdateFailed` with `exitCode = 1603`. Use the real `AppVersion` factory name.
  - `AboutCardTest`:
    - `whatsNewIsThereInEveryStateButOff`
    - `aFailedCheckShowsNextToReady`

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*UpdateServiceTest*" --tests "*AboutCardTest*" --tests "*AppGraphTest*" --tests "*LogbookTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces.

- [ ] **Step 4: Run** the same command. Expected: PASS.

- [ ] **Step 5: Commit** `fix: update states stay put on a failed check, install once, and the first failed install reaches the Log`

### Task 14: Downloads: remembered failures (F-5), never while live (F-6), checksum before install (F-8)

**Files:**
- Modify:
  - `core/update/UpdateStore.kt`, `core/update/UpdateService.kt`, `core/update/UpdateDownloader.kt` (extract the hash helper).
  - `desktop/UpdateLauncher.kt`, `ui/settings/AboutCard.kt` and both `strings.xml`.
- Test: `core/update/UpdateServiceTest.kt`, `core/update/UpdateStoreTest.kt`, `desktop/UpdateLauncherTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Produces:
  - **F-5:**
    - `@Serializable data class FailedDownload(val version: String, val atEpochMs: Long)`, in `UpdateStore` file `failed-download.json`.
    - `UpdateStore.failedDownload(): FailedDownload?`, `saveFailedDownload(f)` and `clearFailedDownload()`. Atomic like the other files, never throwing.
    - In `UpdateService.onRelease`, a saved failure for **this** version less than `DOWNLOAD_RETRY_MS = 86_400_000` ago means no download this check. The state stays as it was; with nothing settled it becomes `UpToDate`.
    - A failed download saves the failure. The `DownloadFailed` event is emitted only when no failure was saved for this version before. This replaces the in-memory `reportedDownloadFailures`.
    - A successful download clears the failure.
  - **F-6:**
    - New state `UpdateState.WaitingForOffline(version: AppVersion)`, never saved.
    - `onRelease` with `gate.value.live` remembers the release and sets that state, without downloading.
    - `start()` also collects `gate` and, on the first not-live status with a remembered release, runs a check.
    - `AboutCard` shows `update_waiting_offline`: "Versie %1$s gevonden; hij wordt gedownload zodra je offline bent." / "Version %1$s found; it downloads once you are offline."
  - **F-8:**
    - `fun sha256Of(file: Path): String` (lowercase hex), in `core/update/`, used by `UpdateDownloader` too.
    - `UpdateLauncher.launch` checks `sha256Of(msi) == pending.sha256` before it starts the helper. A mismatch or a missing file means `store.clearPending()` and `false`, without starting anything.

- [ ] **Step 1: Write the failing tests:**
  - `UpdateServiceTest`:
    - `aFailedDownloadIsRetriedOnceADay`: a failing download at the 2-min check, no second download attempt at +6 h, one at the check after +24 h.
    - `aNewerReleaseIsDownloadedAtOnce`: 0.2.1 failed; at the next check 0.2.2 is downloaded.
    - `aFailedDownloadIsLoggedOncePerVersionAcrossRuns`: a second `UpdateService` on the same store gives no second `DownloadFailed`.
    - `downloadWaitsUntilTheStreamIsOffline` (Review Focus 5): the gate is live at the check, so `WaitingForOffline` and no download. The gate goes offline, and the download happens and the state becomes `Ready`.
  - `UpdateLauncherTest.aChangedMsiIsNotInstalled`: an msi file whose hash differs from `pending.sha256`. `launch` is false, `start` was not called, and `pending.json` is gone.
  - `SavedFormatCompatTest.failedDownloadIsPinned`: literal `{"version":"0.2.1","atEpochMs":5}`.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*UpdateServiceTest*" --tests "*UpdateStoreTest*" --tests "*UpdateLauncherTest*" --tests "*SavedFormatCompatTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces.

- [ ] **Step 4: Run** the same command plus `--tests "*UpdatePolicyTest*" --tests "*AboutCardTest*"`. Expected: PASS (the start policy is unchanged).

- [ ] **Step 5: Commit** `fix: a failed download waits a day, downloads wait for the end of the stream, and the installer is checked once more`

### Task 15: The update helper: a readable command (F-9), the launcher process and reboot codes (F-7); uninstall clears the updates folder (F-10)

**Files:**
- Modify: `desktop/UpdateLauncher.kt`, `core/update/UpdateService.kt` (`reconcile`, exit code 0), `desktop/UninstallCleanup.kt`, `Main.kt` (the `--uninstall-cleanup` call)
- Test: `desktop/UpdateLauncherTest.kt`, `core/update/UpdateServiceTest.kt`, `desktop/UninstallCleanupTest.kt`

**Interfaces:**
- Produces:
  - `updateHelperCommand(msi, msiLog, exitCodeFile, installingMarker, waitForPids: List<Long>, launcher, hidden): List<String>`. The command is `powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command <script>`, where `<script>` is one line of `; `-separated statements:
    - `$ErrorActionPreference = 'SilentlyContinue'`
    - `$q = [char]34`
    - `Wait-Process -Id <pid1>,<pid2> -Timeout 60`
    - `$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ('/i ' + $q + '<msi>' + $q + ' <ui> /norestart REQUESTIFY_UPDATER=1 /l*v ' + $q + '<log>' + $q) -Wait -PassThru`
    - `$c = $p.ExitCode`
    - `if ($c -eq 3010 -or $c -eq 1641) { $c = 0 }`
    - `Set-Content -LiteralPath '<exitCodeFile>' -Value $c`
    - `Remove-Item -LiteralPath '<installing.txt>'`
    - `Start-Process -FilePath '<launcher>'`, plus ` -ArgumentList '--autostart'` when hidden.

    Every path is in single quotes with `'` doubled. The script contains no `"` character (Java's Windows argument quoting cannot break it).
  - `UpdateLauncher.launch` passes `waitForPids = listOf(current pid) + parent pid`, the parent only when `ProcessHandle.current().parent()` is the launcher exe (`info().command()` equals `launcher`).
  - `reconcile`: `running < target` with `lastExitCode() == 0` means installed but waiting for a Windows restart. No `InstallFailed` event, and the state is `Ready`.
  - `cleanUpForUninstall(registry: RegistryPort, dataDir: Path, updatesDir: Path)` also deletes `updatesDir` recursively, best effort and never throwing. Main passes `AppPaths.default().updatesDir`.

- [ ] **Step 1: Write the failing tests:**
  - `UpdateLauncherTest`:
    - `theHelperIsAReadableCommand`: `-Command` and no `-EncodedCommand`, no `"` in the script, `Wait-Process -Id 11,22 -Timeout 60` for pids 11 and 22, and the `3010`/`1641` line present.
    - `aQuoteInAPathIsDoubled`: msi `C:\Users\O'Brien\x.msi` appears as `'C:\Users\O''Brien\x.msi'`, inside `$q` quotes.
    - The existing script tests decode Base64 today: change them to read the script from `command.last()` directly.
  - `UpdateServiceTest.anInstallWaitingForARebootIsNoFailure`: pending 0.2.1 with `attempts = 1`, exit code file "0", running 0.2.0. No `InstallFailed` event, and the state is `Ready`.
  - `UninstallCleanupTest.theUpdatesFolderIsRemoved`: a temp `updatesDir` with `pending.json` and `x.msi` is gone afterwards. A missing folder is fine.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*UpdateLauncherTest*" --tests "*UpdateServiceTest*" --tests "*UninstallCleanupTest*" --tests "*InstallerScriptTest*"`. Expected: the new tests fail.

- [ ] **Step 3: Implement** to the Interfaces.

- [ ] **Step 4: Run** the same command. Expected: PASS.

- [ ] **Step 5: Commit** `fix: the update helper is a readable script that waits for the launcher, and uninstall removes the downloads`

### Task 16: Local build numbers always rise (F-14); the release workflow takes the tag safely and the card skips the placeholder notes (F-17)

**Files:**
- Modify: `build.gradle.kts`, `.github/workflows/release.yml`, `ui/home/UpdatedCard.kt`
- Test: `ui/home/UpdatedCardTest.kt`

**Interfaces:**
- Produces:
  - `build.gradle.kts`:
    - `LocalBuildVersion` gets parameters `interface Params : ValueSourceParameters { val file: RegularFileProperty }`. The file is `.gradle/requestify-local-build.txt` in the project directory, which survives `clean`.
    - `obtain()` takes `n = minute-based number as today`. When the saved number is ≥ n it uses `saved + 1`, wrapping to 1 past 65535. It writes the number back, and returns `"0.0.$n"`.
    - New task `printRequestifyVersion` prints `requestifyVersion`.
  - `release.yml`:
    - `env: REF_NAME: ${{ github.ref_name }}` on the job.
    - `$tag = $env:REF_NAME` in "Check the tag", and `gh release create "$env:REF_NAME" …` in "Draft release".
    - No `${{ github.ref_name }}` left inside any `run:` script.
  - `const val PLACEHOLDER_NOTES = "Schrijf hier wat er nieuw is. / Write what's new here."` in `UpdatedCard.kt`. The workflow keeps that exact text.
    - `releaseNotesText` drops any line equal to it (after trimming); when nothing is left, it returns "".
    - `UpdatedCard` leaves out the notes text when it is blank.

- [ ] **Step 1: Write the failing test** `UpdatedCardTest.thePlaceholderLineIsLeftOut`: `releaseNotesText(PLACEHOLDER_NOTES)` is `""`, and `releaseNotesText("## Nieuw\n$PLACEHOLDER_NOTES\n- Bot")` is `"Nieuw\n• Bot"` (adapted to the function's real heading output).

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*UpdatedCardTest*"`. Expected: FAIL.

- [ ] **Step 3: Implement** to the Interfaces.

- [ ] **Step 4: Run**
  - `.\gradlew.bat jvmTest --tests "*UpdatedCardTest*"`. Expected: PASS.
  - `.\gradlew.bat printRequestifyVersion` twice in a row. Expected: two `0.0.<n>` lines, the second higher.
  - `Select-String -Path .github/workflows/release.yml -Pattern 'github.ref_name'`. Expected: only the `env:` line.

- [ ] **Step 5: Commit** `fix: local builds always count up, and the release workflow and the Bijgewerkt card handle the tag and placeholder safely`

---

## Part F — the title bar and the docs

### Task 17: "Nog niets gekoppeld" instead of "Alles verbonden" (F-31)

**Files:**
- Modify:
  - `ui/common/ConnectionChip.kt`, `desktop/Tray.kt`, `AppGraph.kt`.
  - The title bar that calls `ConnectionChip` (`ui/window/TitleBar.kt` or where it lives), `Main.kt` (the `trayLook` calls) and both `strings.xml`.
- Test: `ui/common/ConnectionChipTest.kt`, `desktop/TrayTest.kt`

**Interfaces:**
- Produces:
  - `AppGraph.nothingConnected: StateFlow<Boolean>`: Spotify's auth status is not `LoggedIn` and `settings.twitch.account == null`.
  - `ConnectionChip(problems, nothingConnected: Boolean, onClick, modifier)`. With no problems and `nothingConnected`, it shows `StatusLook(connection_nothing_connected, AppIcons.Link, Tone.Neutral)`; otherwise as today.
  - `trayLook(problems, saveProblems, nothingConnected: Boolean = false)`. With no problems and nothing to save, the status is `connection_nothing_connected` and there is no warning.
  - String `connection_nothing_connected`: "Nothing connected yet" / "Nog niets gekoppeld".

- [ ] **Step 1: Write the failing tests:**
  - `ConnectionChipTest.nothingConnectedIsNeutral`: "Nothing connected yet" shows, and "All connected" does not.
  - `TrayTest.nothingConnectedIsNoWarning`: `statusLine == "Nothing connected yet"` and `warning == false`.

- [ ] **Step 2: Run** `.\gradlew.bat jvmTest --tests "*ConnectionChipTest*" --tests "*TrayTest*"`. Expected: compile failure.

- [ ] **Step 3: Implement** and wire it (title bar and Main's two `trayLook` calls).

- [ ] **Step 4: Run** the same command plus `--tests "*StringsTest*" --tests "*AppScaffold*"`. Expected: PASS.

- [ ] **Step 5: Commit** `fix: the title bar says nothing is connected yet instead of all connected`

### Task 18: The manual checklist and the README (Decision 9, F-15, F-16, M-15's check)

**Files:**
- Modify: `docs/superpowers/plans/2026-11-19-requestify-plan-5-manual-checks.md`, `README.md`

**Interfaces:** none (docs).

- [ ] **Step 1: Edit the checklist** (Dutch, in the file's own style):
  - **Part E:**
    - Rename it "E. Bijwerken van 0.2.0 naar 0.3.0".
    - Steps 11–15 say `v0.3.0`/"0.3.0".
    - Step 17's pre-release is `v0.3.1`.
    - Step 16 (failed install) starts with "Sluit Requestify af via het systeemvak (Afsluiten)".
    - Add a closing line to step 17: remove the `v0.3.1` pre-release on GitHub and delete `%LOCALAPPDATA%\Requestify-updates\pending.json` and its `.msi`, so no pre-release stays pending.
  - **F-15 gaps:**
    - In part B: install while you are signed in to another Windows account too, and install right after signing in to Windows (autostart and the installer at once).
    - In part F: uninstall while Requestify runs. Expected: the installer asks to close it, or closes it, and afterwards `%LOCALAPPDATA%\Requestify-updates` is gone (F-10).
  - **New part G "Bot-account":**
    1. Connect the bot in a private window: "Antwoorden komen van @bot".
    2. Approve the code as yourself: "Dat is je eigen account…", and your own Twitch login still works.
    3. `!song` in chat: the answer comes from the bot.
    4. Log the bot out at twitch.tv (Settings → Connections → disconnect Requestify) and send `!song` again: the answer comes from you, plus one notification "De bot moet opnieuw inloggen".
    5. `/mod bot`, then "Opnieuw kijken" shows "Moderator ✓".
    6. Ontkoppelen: answers come from you, and no warning shows.
  - **M-15:** one line under part D or G: "Heb je ooit een StreamElements-account bij de hand: kijk of de valuta bij Donaties klopt."

- [ ] **Step 2: Edit the README.** Add one sentence after the Smart App Control step, in both languages:
  - Dutch: "Staat Smart App Control op *Evaluatie*, dan kan Windows hem later zelf aanzetten; kijk dan opnieuw."
  - English: "If Smart App Control is set to *Evaluation*, Windows may switch it on by itself later; check again then."

- [ ] **Step 3: Verify**
  - `Select-String -Path docs/superpowers/plans/2026-11-19-requestify-plan-5-manual-checks.md -Pattern '0\.2\.1'`. Expected: no matches.
  - `Select-String -Path README.md -Pattern 'Evaluati'`. Expected: two matches.

- [ ] **Step 4: Commit** `docs: the checklist covers 0.3.0 and the bot, and the README warns about Smart App Control in Evaluation`

---

## After the last task

- Run the whole suite: `.\gradlew.bat jvmTest`. Expected: all green, more tests than the 1122 on `main`.
- Run the renders (`REQUESTIFY_RENDERS`) and look at the bot section and the wizard's Twitch card in Dutch and English, light and dark.
- The final review, then the user's own test of an installed build (checklist parts D–G) before tagging `v0.3.0`. **Tagging and pushing wait for the user's go-ahead at that moment.**
