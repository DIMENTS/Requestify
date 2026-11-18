# Requestify plan 3 — donations and the Log — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A donation with a Spotify link (DonoLink or StreamElements, one at a time) becomes a paid request, and a Log shows what happened to every request, connection, stream change and possible gap for 30 days.

**Architecture:** Donations follow the plan-2 Bits pattern: a source turns platform events into `Donation`s, one shared `DonationHandler` turns them into `IncomingRequest`s for `RequestIntake`. The Log listens (approach A): `Logbook` subscribes to what the app already reports (intake records, controller `RequestEvent`s, outbox results, gate events, connection states, gap reports) and appends `LogRecord`s to day files; the UI reads rows folded per request.

**Tech Stack:** Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Material 3 Expressive), Ktor 3.6.1 CIO (HTTP + WebSockets), kotlinx coroutines + serialization, JUnit/kotlin-test, coroutines-test virtual time, ktor-client-mock, `runComposeUiTest`.

**Spec:** `docs/superpowers/specs/2026-11-18-requestify-plan-3-donations-log-design.md` (parents: `2026-11-16-requestify-design.md`, `2026-11-17-requestify-plan-2-rules-twitch-design.md`).

## Global Constraints

- Paths below are relative to `src/jvmMain/kotlin/nl/diments/requestify/` (main) and `src/jvmTest/kotlin/nl/diments/requestify/` (tests) unless they start with `src/` or `docs/`.
- Every user-facing string exists in both `src/commonMain/composeResources/values/strings.xml` and `values-nl/strings.xml` with identical keys and placeholders (`StringsTest` enforces it). Dutch says "Kanaalpunten", "Logboek", "Donaties".
- No chat reply may start with a placeholder, "!" or "/". Every donation reply starts with fixed text, never with the donor name.
- Persisted names are stable forever: every new `@SerialName` gets a pin in `core/storage/SavedFormatCompatTest.kt`. New persisted fields have defaults (old files must read).
- Lock order in `PlaybackController`: `spotifyMutex` then `stateMutex`; never a network call under `stateMutex`. Events are emitted after the save (no replay).
- A paid request is never lost silently; a donation is never queued twice (the anti-goals).
- Log I/O never runs on the UI thread and never blocks the queue (`Dispatchers.IO`, bounded buffer).
- Only DonoLink's own donations: `platform=donolink`, `type=donation`, `unit=cents`.
- One donation source at a time: `DonationSourceKind.None | DonoLink | StreamElements`.
- Deliberately switching a source on (from off, from None, or from the other source) starts at "now"; only time while it was ON is caught up.
- Retention of the Log: 30 days. DonoLink expiry warning from 7 days before expiry.
- Never drive the user's desktop with scripted clicks; UI checks are offscreen renders (`renders/` in the SDD workspace).

## Review Focus

1. **The same donation twice** — a DonoLink poll at the cursor boundary, a restart between handing a donation over and saving the cursor, an SE tip arriving live and again in the backfill: exactly one request. (Task 8 `aDonationSeenTwiceIsQueuedOnce`, Task 10 `restartBeforeTheCursorSaveQueuesItOnce`, Task 12 `aTipInTheBackfillAndLiveIsQueuedOnce`.)
2. **Donor names** that are empty, whitespace, "!skip", "/me", emoji or 300 characters long — "Anoniem"/"Anonymous" for empty, no command in chat, the reply within Twitch's 500 characters. (Task 8 `donorNamesCannotBreakTheReply`.)
3. **A long time off with the source on** — several DonoLink pages (`has_more`) are caught up in order, with paid priority, and the cursor ends at the newest. (Task 10 `catchUpFollowsEveryPageInOrder`.)
4. **A busy month of Log** — 30 days with thousands of rows: opening the Log, filtering and searching stay responsive (folding off the UI thread, lazy list). (Task 6 `foldingTenThousandRecordsStaysFast`, Task 7 `filteringRunsOffTheUiThread`.)
5. **A Log that cannot write** (disk full, locked file) — nothing else waits, records are retried, a banner says so, nothing is lost while memory allows. (Task 5 `aFailedWriteIsRetriedAndReported`.)

---

## Phase A — foundations

### Task 1: Settings, reasons, files and secrets for donations

**Files:**
- Create: `core/settings/DonationSettings.kt`
- Modify: `core/settings/Settings.kt` (add field), `core/model/RejectReason.kt`, `core/storage/AppPaths.kt`, `core/secrets/SecretStore.kt` (`SecretKeys`), `core/twitch/chat/ChatReplies.kt` + `ui/home/RejectedList.kt` (reason texts), both `strings.xml`
- Test: `core/storage/SavedFormatCompatTest.kt`, `ui/StringsTest.kt`, `ui/home/RejectedListTest.kt`

**Interfaces:**
- Produces:
  - `enum class DonationSourceKind { None, DonoLink, StreamElements }` (`@Serializable`, names `none`, `donolink`, `streamelements`)
  - `@Serializable data class DonationSettings(val source: DonationSourceKind = DonationSourceKind.None, val donoLinkMinimumCents: Long = 100, val streamElementsMinimumCents: Long = 100, val streamElementsCurrency: String? = null)`; `Settings.donations: DonationSettings = DonationSettings()`
  - `RejectReason.BelowMinimumAmount(val minCents: Long, val currency: String)` `@SerialName("below_minimum_amount")`; `RejectReason.OtherCurrency(val currency: String)` `@SerialName("other_currency")`
  - `AppPaths.donationsFile` = `donations.json`; `AppPaths.logDir` = `log/`
  - `SecretKeys.DONOLINK_KEY = "donolink.key"`, `SecretKeys.STREAMELEMENTS_JWT = "streamelements.jwt"`

- [ ] **Step 1: Failing tests.** `SavedFormatCompatTest.donationNamesArePinned` (encodes each new reason and `DonationSourceKind` and asserts the serial names above); `SavedFormatCompatTest.oldSettingsWithoutDonationsRead` (a settings JSON from before plan 3 → `donations == DonationSettings()`); `RejectedListTest.donationReasonsHaveText` (both reasons render a non-empty text in en and nl: "Below the minimum (€1,00)" / "Onder het minimum (€ 1,00)" via `formatMoney`, "Other currency (USD)" / "Andere valuta (USD)").
- [ ] **Step 2:** Run `./gradlew jvmTest --tests "*SavedFormatCompatTest" --tests "*RejectedListTest"` → FAIL (unresolved references).
- [ ] **Step 3:** Implement the types and fields above; add the reason texts to the Rejected list and to `ChatReplies.reasonText` (chat wording per strings files).
- [ ] **Step 4:** Run the same tests plus `*StringsTest` → PASS.
- [ ] **Step 5:** Commit `feat: donation settings, reasons and files`.

### Task 2: The intake and the controller report what they did

**Files:**
- Modify: `core/intake/RequestIntake.kt`, `core/playback/PlaybackController.kt`, `core/playback/RequestEvents.kt`, `core/twitch/bits/BitsSource.kt`
- Test: `core/intake/RequestIntakeTest.kt`, `core/playback/PlaybackControllerFollowUpTest.kt`, `core/twitch/bits/BitsSourceTest.kt`

**Interfaces:**
- Produces:
  - `data class IntakeRecord(val incoming: IncomingRequest, val outcome: IntakeOutcome, val atEpochMs: Long)`
  - `RequestIntake.records: SharedFlow<IntakeRecord>` — one record for every `submit`, `rejectBeforeRules`, settled pending request (the same moment `pendingResolved` emits) and `addAnyway` (outcome `Accepted`), emitted after the outcome is saved; `extraBufferCapacity = Int.MAX_VALUE` (never drops, never suspends the caller)
  - `RequestIntake.isKnown(id: String): Boolean` — true when `id` is in the queue, handed over, history, Rejected or pending
  - `RequestEvent.HandedOver(val request: Request)` emitted after the save that marks a request handed to Spotify
- Consumes: existing `IntakeOutcome`, `RequestEvent`.

- [ ] **Step 1: Failing tests.**
  - `RequestIntakeTest.everySubmitIsRecorded`: accepted, rejected (listed), ignored (link-only text) and pending each produce exactly one `IntakeRecord` with that outcome and the incoming's id.
  - `RequestIntakeTest.aSettledPendingRequestIsRecordedAgain`: Spotify down → record `Pending`; Spotify back → second record `Accepted`.
  - `RequestIntakeTest.addAnywayIsRecorded`.
  - `RequestIntakeTest.isKnownCoversQueueHistoryRejectedAndPending`.
  - `PlaybackControllerFollowUpTest.handOverEmitsHandedOverAfterTheSave` (event arrives after `queue.json` shows the hand-over).
  - `BitsSourceTest.aCheerWithoutALinkIsRecordedAsIgnored`: the cheer goes through `intake.submit` (outcome `Ignored`, one record, no chat reply).
- [ ] **Step 2:** Run the four test classes → FAIL.
- [ ] **Step 3:** Implement. `BitsSource` no longer returns early for `SearchText`/`Empty`: it submits and stays silent on `Ignored` (`ChatReplies.outcome` already returns null for it — verify). If the streamer's own add (Home) does not pass `RequestIntake.submit`, emit its record from that one place.
- [ ] **Step 4:** Run the four test classes, then the whole suite → PASS.
- [ ] **Step 5:** Commit `feat: the intake and the queue report every outcome`.

### Task 3: The gate, the outbox and connections report changes

**Files:**
- Modify: `core/stream/RequestsGate.kt`, `core/twitch/points/RedemptionOutbox.kt`
- Test: `core/stream/RequestsGateTest.kt`, `core/twitch/points/RedemptionOutboxTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface GateEvent { val atEpochMs: Long }` with `WentLive`, `WentOffline`, `OpenedByHand`, `ClosedByHand`, `StreamEnded` (the end-of-stream clear starts), `data class` each with `atEpochMs`
  - `RequestsGate.events: SharedFlow<GateEvent>` (emitted after the state is saved or applied in memory per R7)
  - `RequestsGate.loadedLastSeenLiveMs: Long?` — `lastSeenLiveMs` as read from `stream.json` at construction (for the gap after an app-off period)
  - `sealed interface OutboxResult { val redemptionId: String; val status: RedemptionStatus }`: `Sent`, `Retrying(tries: Int)`, `GaveUp`; `RedemptionOutbox.results: SharedFlow<OutboxResult>`

- [ ] **Step 1: Failing tests.** `RequestsGateTest.eventsFollowEveryChange` (live, offline, toggle open/close by hand, the grace end → `StreamEnded`, in that order, one each); `RequestsGateTest.loadedLastSeenLiveIsKept`; `RedemptionOutboxTest.resultsReportSentRetryingAndGaveUp`.
- [ ] **Step 2:** Run both classes → FAIL.
- [ ] **Step 3:** Implement (emit with `extraBufferCapacity = Int.MAX_VALUE`; never emit under a lock that a collector could need).
- [ ] **Step 4:** Run both classes → PASS.
- [ ] **Step 5:** Commit `feat: the gate and the refund outbox report their changes`.

## Phase B — the Log

### Task 4: Log records and rows

**Files:**
- Create: `core/log/LogRecords.kt`, `core/log/LogRows.kt`
- Test: `core/log/LogRowsTest.kt`, `core/storage/SavedFormatCompatTest.kt`

**Interfaces:**
- Produces (all `@Serializable`, stable `@SerialName`s pinned):
  - `sealed interface LogRecord { val rowId: String; val atEpochMs: Long }`:
    - `RequestReceived(rowId, atEpochMs, source: RequestSource, requester: Requester, input: String, payment: Payment?, sourceRef: String?)` — `rowId` = incoming id
    - `RequestStep(rowId, atEpochMs, step: LogStep)`
    - `ConnectionChanged(rowId, atEpochMs, service: LogService, state: LogConnectionState, detail: String?)` — one row per outage: `rowId` = `"conn:<service>:<startMs>"`
    - `StreamChanged(rowId, atEpochMs, change: LogStreamChange)` — `rowId` = `"stream:<atEpochMs>"`
    - `GapNoticed(rowId, atEpochMs, source: RequestSource, fromEpochMs: Long?, toEpochMs: Long)` — `fromEpochMs == null`: start unknown ("before 21:10")
  - `sealed interface LogStep`: `Queued(position: Int)`, `Rejected(reason: RejectReason, listed: Boolean)`, `Pending`, `Ignored` (no Spotify link), `HandedOver`, `Played`, `Removed(why: RemoveReason)`, `RefundPending(tries: Int)`, `Refunded`, `RefundGaveUp`, `Fulfilled`, `Track(track: Track)` (the resolved track, once known)
  - `enum class LogService { Spotify, Twitch, DonoLink, StreamElements, Log }`; `enum class LogConnectionState { Lost, Restored, LoginExpired, KeyRefused, KeyExpiring }`; `enum class LogStreamChange { Live, Offline, OpenedByHand, ClosedByHand, StreamEnded }`
  - `data class LogRow(val id: String, val kind: LogKind, val atEpochMs: Long, val records: List<LogRecord>, val isProblem: Boolean, val clearedCount: Int = 0, val refundedCount: Int = 0)` with `enum class LogKind { Request, Connection, Stream, Gap }`; `LogRow.latestStep: LogStep?`, `LogRow.track: Track?`
  - `fun foldRows(records: List<LogRecord>): List<LogRow>` — groups by `rowId`, newest row first, records in time order; a `StreamEnded` row counts the `Removed(StreamEnded)` steps and `Refunded` steps of the next 2 minutes as "n cleared, m refunded"
  - `isProblem` rule: the row's latest state is `Rejected`, `Pending`, `RefundPending`, `RefundGaveUp`, a connection not `Restored`, `LoginExpired`, `KeyRefused`, `KeyExpiring`, any `GapNoticed`, or a `Rejected(SpotifyUnavailable)` anywhere in it.

- [ ] **Step 1: Failing tests.** `LogRowsTest.aRequestFoldsIntoOneRowWithItsTimeline` (received → track → queued #2 → handed over → played: one row, latest `Played`, five records in order); `outageIsOneRowUntilRestored`; `streamEndedCountsClearedAndRefunded`; `problemsFollowTheLatestState` (rejected = problem; refund pending then refunded = no problem); `gapIsAlwaysAProblem`; `SavedFormatCompatTest.logRecordNamesArePinned`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: Log records fold into one row per request`.

### Task 5: The Log store — day files, retention, failed writes

**Files:**
- Create: `core/log/LogStore.kt`
- Test: `core/log/LogStoreTest.kt`

**Interfaces:**
- Produces: `class LogStore(dir: Path, scope: CoroutineScope, clock: () -> Long, zone: ZoneId = ZoneId.systemDefault(), io: CoroutineDispatcher = Dispatchers.IO, retentionDays: Int = 30, bufferMax: Int = 10_000)`:
  - `fun append(record: LogRecord)` — never blocks, never throws; queues for the writer
  - `suspend fun readAll(): List<LogRecord>` — every day file within retention, oldest first; a corrupt line is skipped (the rest of the file still reads)
  - `val writeError: StateFlow<String?>` — null while writes succeed
  - `fun start()` / `suspend fun stop()` (flushes what it can)
  - Files: `log/yyyy-MM-dd.jsonl` (local date of `atEpochMs`), one JSON object per line (`AppJson`), appended.
  - Retention: on `start()` and every 24 h, delete day files older than `retentionDays`.
- [ ] **Step 1: Failing tests.** `appendedRecordsReadBackInOrder`; `recordsLandInTheirLocalDayFile` (two records around local midnight → two files); `filesOlderThanThirtyDaysAreDeleted`; `aCorruptLineIsSkipped`; `aFailedWriteIsRetriedAndReported` (a directory that cannot be written → `writeError` set, `append` returns at once, records kept in memory; once writable → written in order and `writeError` back to null); `theBufferNeverGrowsPastItsMax` (oldest dropped first, and `writeError` says records were lost).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (one writer coroutine on `io`, a `Channel` buffer, retry 1 s → 60 s doubling). **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: the Log keeps 30 days of day files and survives failed writes`.

### Task 6: The Logbook listens

**Files:**
- Create: `core/log/Logbook.kt`
- Test: `core/log/LogbookTest.kt`

**Interfaces:**
- Consumes: `RequestIntake.records`, `PlaybackController.events` (`RequestEvent` incl. `HandedOver`), `RedemptionOutbox.results`, `RequestsGate.events`, connection inputs, `gaps: Flow<GapNoticed>` (Task 7, Task 12).
- Produces: `class Logbook(store: LogStore, scope: CoroutineScope, clock: () -> Long, sources: LogInputs, folding: CoroutineDispatcher = Dispatchers.Default)` with
  - `data class LogInputs(val intake: Flow<IntakeRecord>, val requests: Flow<RequestEvent>, val refunds: Flow<OutboxResult>, val gate: Flow<GateEvent>, val connections: Flow<Pair<LogService, ConnectionSnapshot>>, val gaps: Flow<LogRecord.GapNoticed>)`; `enum class ConnectionSnapshot { Up, Down, LoginExpired, KeyRefused, KeyExpiring }`
  - `val rows: StateFlow<List<LogRow>>` (folded on `folding`, newest first), `val writeError: StateFlow<String?>` (from the store)
  - `fun start()` — reads the store, then subscribes to every input with `CoroutineStart.UNDISPATCHED` (none of them replays); `fun stop()`
  - Mapping: intake `Accepted` → `RequestReceived` + `Track` + `Queued(position)`; `Rejected` → `RequestReceived` + `Track` (when known) + `Rejected`; `Ignored` → `RequestReceived` + `Ignored`; `Pending` → `RequestReceived` + `Pending`; a second record for the same id adds steps only. `RequestEvent.HandedOver/Started/Removed` → steps on the request's row (`Request.id` is the incoming id — verify in `RequestIntake.buildRequest`). Outbox results → steps on the row whose `sourceRef` names that redemption (`ChannelPointsRef`). Connections: `Up→Down` opens an outage row, `Down→Up` closes it with `Restored`; repeated equal snapshots log nothing.
- [ ] **Step 1: Failing tests** (fake flows, virtual time): `everyOutcomeBecomesARow`; `queueEventsUpdateTheSameRow`; `aRefundUpdatesTheChannelPointsRow`; `anOutageIsLoggedOnceAndClosedOnRestore`; `gateEventsBecomeStreamRows`; `rowsSurviveARestart` (records written, new Logbook on the same store → same rows); `foldingTenThousandRecordsStaysFast` (10 000 records fold in under 1 s on `Dispatchers.Default`, rows not computed on the caller's thread).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: the Logbook follows requests, refunds, the stream and connections`.

### Task 7: Bits gaps, and the Log in the app

**Files:**
- Create: `core/log/BitsGapWatcher.kt`, `ui/log/LogViewModel.kt`, `ui/log/LogScreen.kt`
- Modify: `AppGraph.kt` (build `LogStore` + `Logbook` + `BitsGapWatcher`; `logbook.start()` FIRST in `start()`, before `channelPoints.startLifecycle()`; `stop()` stops it last), `ui/AppScaffold.kt` (`Destination.Log(Res.string.nav_log, AppIcons.History)` between Blacklist and Settings; remove the "a later plan adds Log" note), `ui/common/Icons.kt` if needed, both `strings.xml`
- Test: `core/log/BitsGapWatcherTest.kt`, `ui/log/LogViewModelTest.kt`, `ui/log/LogScreenTest.kt`, `AppGraphTest.kt`

**Interfaces:**
- Produces:
  - `class BitsGapWatcher(eventSubState: StateFlow<EventSubState>, gate: RequestsGate, scope, clock)`, `val gaps: SharedFlow<LogRecord.GapNoticed>`, `start()/stop()`. Rules: EventSub `Connected → Disconnected(since)` while the gate says live, then `Connected` again → gap `since..reconnect` (none for `session_reconnect`, which never leaves `Connected`); at the first `WentLive` of this run, when `gate.loadedLastSeenLiveMs != null` (the app closed during a live stream) → gap `loadedLastSeenLiveMs..now`; when the app starts and the first live confirmation comes from a read (not a `stream.online` event) with no saved live → gap `null..appStart` ("before HH:MM, Requestify was off").
  - `class LogViewModel(logbook: Logbook, scope: CoroutineScope, zone: ZoneId = ZoneId.systemDefault(), work: CoroutineDispatcher = Dispatchers.Default)`: `filter: StateFlow<LogFilter>` (`All, Requests, Connections, Problems`), `query: StateFlow<String>`, `days: StateFlow<List<LogDay>>` (`data class LogDay(val date: LocalDate, val rows: List<LogRow>)`), `selected: StateFlow<LogRow?>`, `writeError`; `fun setFilter(f)`, `fun setQuery(q)`, `fun select(id: String?)`. Search matches requester display name, track title and artists, input text (case-insensitive).
  - `@Composable fun LogScreen(vm: LogViewModel)`: header "Logboek" + filter chips + search; `LazyColumn` with day headers ("Vandaag", "Gisteren", else the date); a row shows time, source chip (reuse the existing source chip), who · what, latest state text, amount; a problem row shows the error tone; click → details dialog with source, viewer, amount, link, reason and the timeline; a banner on top while `writeError != null`.
- [ ] **Step 1: Failing tests.** `BitsGapWatcherTest`: `aDisconnectDuringALiveStreamIsAGap`, `aReconnectWithinTheSessionIsNoGap`, `anAppClosedDuringTheStreamLeavesAGap`, `noGapWhileOffline`. `LogViewModelTest`: `filtersSplitRowsByKind`, `problemsShowOnlyProblems`, `searchFindsNameAndTrack`, `rowsAreGroupedByLocalDay`, `filteringRunsOffTheUiThread`. `LogScreenTest`: `rowsShowTimeSourceAndState`, `clickingARowShowsItsTimeline`, `aWriteErrorShowsTheBanner`. `AppGraphTest.aChatRequestEndsUpInTheLog` (end-to-end with the plan-2 rig: `!sr` link → a Log row with `Queued`).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; render the Log screen offscreen (dark + light, wide + narrow, one problem row, details open) into the SDD `renders/` folder and show the user before review.
- [ ] **Step 5:** Commit `feat: the Logboek screen, and gaps in Bits are logged`.

## Phase C — donations

### Task 8: Donations share one handler

**Files:**
- Create: `core/donations/Donation.kt`, `core/donations/DonationHandler.kt`
- Modify: `core/twitch/chat/ChatReplies.kt`, both `strings.xml`
- Test: `core/donations/DonationHandlerTest.kt`, `core/twitch/chat/ChatRepliesTest.kt`

**Interfaces:**
- Produces:
  - `data class Donation(val id: String, val source: RequestSource, val donorName: String?, val message: String, val amountCents: Long, val currency: String, val occurredAtEpochMs: Long)` — `id` already prefixed (`dl-…`, `se-…`)
  - `class DonationHandler(intake: RequestIntake, settings: SettingsStore, replies: ChatReplies, sender: ChatReplySender, twitchConnected: StateFlow<Boolean>, scope: CoroutineScope, clock: () -> Long)`:
    - `suspend fun handle(d: Donation): IntakeOutcome?` — null when `intake.isKnown(d.id)` (already handled: nothing happens)
    - order: no Spotify link (`classifySpotifyInput` is `SearchText`/`Empty`) → `intake.submit` (→ `Ignored`, silent); currency ≠ the minimum's currency (DonoLink: `"EUR"`; SE: `settings.donations.streamElementsCurrency ?: d.currency`) → `intake.rejectBeforeRules(incoming, OtherCurrency(d.currency))`; `amountCents <` minimum → `rejectBeforeRules(incoming, BelowMinimumAmount(min, currency))`; else `intake.submit`
    - `IncomingRequest(id = d.id, input = d.message, linkOnly = true, requester = Requester(displayName = d.donorName?.trim()?.takeIf { it.isNotEmpty() } ?: <"Anonymous"/"Anoniem" string>), source = d.source, payment = Payment.Money(d.amountCents, d.currency.uppercase()), receivedAtEpochMs = clock())`
    - replies only when `twitchConnected.value` is true, via `ChatReplies.donationOutcome(outcome, donorName)`; `start()` collects `intake.pendingResolved` for `DONOLINK`/`STREAMELEMENTS` and replies the same way; `stop()`
  - `suspend fun ChatReplies.donationOutcome(outcome: IntakeOutcome, donorName: String): String?` — null for `Ignored`; every text starts with the fixed words of `chat_donation_*` (en "Donation request from %1$s: …", nl "Donatieverzoek van %1$s: …"); the donor name is cut to 50 characters and the whole reply to 500.
- [ ] **Step 1: Failing tests.** `DonationHandlerTest`: `aDonationWithALinkIsQueuedAsPaid` (position, `Payment.Money(300, "EUR")`, paid priority); `withoutALinkItIsIgnoredSilently`; `belowTheMinimumIsListedWithItsTrack` (Rejected entry has the track; Add anyway queues it with its amount); `anotherCurrencyIsListed`; `aDonationSeenTwiceIsQueuedOnce`; `noReplyWithoutTwitch`; `aPendingDonationRepliesWhenItSettles`; `anEmptyNameIsAnonymous`. `ChatRepliesTest.donorNamesCannotBreakTheReply` (names "", "   ", "!skip", "/me hi", "🎵", 300×"a": no reply starts with "!" or "/", each starts with the fixed prefix in en and nl, length ≤ 500).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: donations become paid requests through one handler`.

### Task 9: DonoLink API

**Files:**
- Create: `core/donations/donolink/DonoLinkApi.kt`, `core/donations/donolink/DonoLinkParse.kt`, fixtures `src/jvmTest/resources/donolink/*.json`
- Test: `core/donations/donolink/DonoLinkApiTest.kt`, `core/donations/donolink/DonoLinkParseTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface DonoLinkResult<out T> { data class Ok<T>(val value: T); data class KeyRefused(val status: Int); data class RateLimited(val retryAfterMs: Long); data class Failed(val message: String) }`
  - `data class DonoLinkMe(val ownerName: String, val keyExpiresAtEpochMs: Long?)`; `data class DonoLinkPage(val items: List<JsonObject>, val hasMore: Boolean, val nextCursor: String?)`
  - `class DonoLinkApi(http: HttpClient, key: () -> String?, baseUrl: String = "https://donolink.nl/api/v1")`: `suspend fun me(): DonoLinkResult<DonoLinkMe>`; `suspend fun activity(since: String, cursor: String?): DonoLinkResult<DonoLinkPage>` → `GET {base}/activity?platform=donolink&type=donation&order=asc&since={since}&limit=100[&cursor=…]`, `Authorization: Bearer <key>`
  - Status mapping: 401/403 → `KeyRefused`; 429/503 → `RateLimited(Retry-After seconds × 1000, capped at 300 000, else 30 000)`; other non-2xx, network, bad JSON → `Failed`
  - `fun parseDonoLinkDonation(item: JsonObject): Donation?` + `fun recordedAtOf(item: JsonObject): String?` — port of Catchall `parseDonolink.ts` (type `donation`, platform `donolink`, unit `cents`, integer `value ≥ 0`, name trimmed or null, currency default `EUR`, `id = "dl-<id>"`, time from `occurred_at`)
- [ ] **Step 1: Failing tests.** Port Catchall `parseDonolink.test.ts` cases to `DonoLinkParseTest` (own donation parsed; mirrored Twitch/SE items, other units, negative/non-integer values → null; empty name → null name). `DonoLinkApiTest` (MockEngine): `meReadsOwnerAndExpiry`, `activitySendsTheFiltersAndKey`, `followsTheCursorParameter`, `refusedKeyMapsToKeyRefused` (401, 403), `rateLimitHonoursRetryAfterWithCap` (7 → 7 000; 900 → 300 000; none → 30 000).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: DonoLink API client`.

### Task 10: DonoLink source — polling, cursor, catch-up, health

**Files:**
- Create: `core/donations/DonationCursorStore.kt`, `core/donations/DonationHealth.kt`, `core/donations/donolink/DonoLinkSource.kt`
- Test: `core/donations/DonationCursorStoreTest.kt`, `core/donations/donolink/DonoLinkSourceTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class DonationCursor(val since: String, val seenAtSince: Set<String> = emptySet())`; `@Serializable data class DonationCursors(val donoLink: DonationCursor? = null, val streamElements: DonationCursor? = null)`
  - `class DonationCursorStore(file: JsonFileStore<DonationCursors>)`: `fun get(kind: DonationSourceKind): DonationCursor?`, `fun save(kind, cursor): Boolean`, `fun resetToNow(kind, nowIso: String)` (called when the streamer switches the source on — Task 13)
  - `enum class DonationStatus { Off, Connecting, Connected, Retrying, KeyRefused }`; `data class DonationHealth(val kind: DonationSourceKind = DonationSourceKind.None, val status: DonationStatus = DonationStatus.Off, val retryingSinceMs: Long? = null, val keyExpiresAtEpochMs: Long? = null, val canCatchUp: Boolean = true)`
  - `interface DonationSource { val health: StateFlow<DonationHealth>; fun start(); fun stop() }`
  - `class DonoLinkSource(api: DonoLinkApi, handler: DonationHandler, cursors: DonationCursorStore, scope, clock, pollMs: Long = 5_000) : DonationSource`
  - Loop: `since` = saved cursor (none → now, saved at once); each tick: fetch pages while `hasMore`; for each item in order: parse; skip when its id is in `seenAtSince` and its `recorded_at` equals `since`; `handler.handle(d)`; then the cursor moves to the newest `recorded_at` and `seenAtSince` holds every id with exactly that `recorded_at`; save the cursor after each page. `me()` at start and every 24 h for the expiry. `KeyRefused` → status `KeyRefused`, loop stops until `start()` again. `RateLimited` → wait `retryAfterMs`; `Failed` → backoff 5 s doubling to 60 s, status `Retrying` with `retryingSinceMs`.
- [ ] **Step 1: Failing tests** (fake API, virtual time): `firstStartBeginsAtNow`; `aDonationIsHandedOverOnce`; `theBoundaryItemIsNotHandledTwice` (since inclusive); `manyDonationsWithOneTimestampAreAllHandledOnce` (250 items share `recorded_at`); `catchUpFollowsEveryPageInOrder`; `restartBeforeTheCursorSaveQueuesItOnce` (the handler's `isKnown` guard); `aRefusedKeyStopsAndSaysSo`; `rateLimitWaitsRetryAfter`; `failuresBackOffAndRecover`; `expiryIsReadAtStartAndDaily`; `resetToNowSkipsTheOffPeriod`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: DonoLink donations are polled, caught up and never doubled`.

### Task 11: StreamElements — verify the API, then the client

**Files:**
- Create: `docs/superpowers/specs/2026-11-18-streamelements-api-notes.md`, `core/donations/streamelements/StreamElementsApi.kt`, `core/donations/streamelements/AstroClient.kt`, `core/donations/streamelements/StreamElementsParse.kt`, fixtures `src/jvmTest/resources/streamelements/*.json`
- Test: `core/donations/streamelements/StreamElementsApiTest.kt`, `AstroClientTest.kt`, `StreamElementsParseTest.kt`

**Interfaces:**
- Step 0 (before code): read StreamElements' current developer docs (Astro gateway: URL, subscribe message for `channel.tips` with a JWT, keepalive/ping, reconnect, error/close codes; REST: the channel behind a JWT and its currency; listing tips after a time with paging). Write the facts with links into the API notes, and state plainly whether a tips backfill endpoint is documented. Everything below follows those notes; where they differ, the notes win and the report says so.
- Produces:
  - `data class SeChannel(val id: String, val name: String, val currency: String?)`
  - `class StreamElementsApi(http: HttpClient, jwt: () -> String?)`: `suspend fun channel(): SeResult<SeChannel>`; `suspend fun tipsSince(channelId: String, afterEpochMs: Long): SeResult<List<JsonObject>>` (only if the notes confirm an endpoint; else omit and set `DonationHealth.canCatchUp = false`). `SeResult` mirrors `DonoLinkResult`.
  - `interface AstroConnector { suspend fun connect(): AstroSocket }` (Ktor WebSocket by default, fake in tests), `class AstroClient(connector, jwt: () -> String?, scope, clock)`: `val tips: SharedFlow<JsonObject>`, `val state: StateFlow<AstroState>` (`Connecting, Connected, Disconnected(sinceMs), Refused`), `val freshSessions: SharedFlow<Unit>`, `start()/stop()`; reconnect with backoff 1 s doubling to 60 s (reset after a session lived 60 s).
  - `fun parseStreamElementsTip(o: JsonObject): Donation?` — id `"se-<tip id>"`, donor name, message, amount → cents (round half up), currency.
- [ ] **Step 1: Failing tests** from the documented payloads (fixtures copied from the docs, cited in the notes): `parsesATip`, `amountsBecomeCents` (3.5 → 350, 0.1 → 10), `aRefusedTokenSaysRefused`, `reconnectsWithBackoff`, `subscribesToChannelTipsWithTheJwt`, `channelReadsIdNameAndCurrency`, and (if confirmed) `tipsSinceSendsTheFilter`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: StreamElements API notes and client`.

### Task 12: StreamElements source

**Files:**
- Create: `core/donations/streamelements/StreamElementsSource.kt`
- Test: `core/donations/streamelements/StreamElementsSourceTest.kt`

**Interfaces:**
- Consumes: Task 11, `DonationHandler`, `DonationCursorStore`.
- Produces: `class StreamElementsSource(api, astro: AstroClient, handler, cursors, scope, clock) : DonationSource` with `val gaps: SharedFlow<LogRecord.GapNoticed>`. On start: `channel()` (saves `streamElementsCurrency` into settings when it changed), connect Astro; every live tip → parse → handler; on every fresh session: if `canCatchUp`, `tipsSince(lastSeen)` and hand over the ones not seen (persisted `seenAtSince` as in Task 10); if not, a `GapNoticed(source = STREAMELEMENTS, from = disconnect/last run, to = now)` when the source was on while disconnected.
- [ ] **Step 1: Failing tests:** `aLiveTipIsHandedOver`; `aTipInTheBackfillAndLiveIsQueuedOnce`; `withoutBackfillAReconnectLogsAGap`; `aRefusedJwtSaysSo`; `theChannelCurrencyIsSaved`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat: StreamElements tips become requests`.

### Task 13: Donations card, health in the chip, wiring

**Files:**
- Create: `ui/setup/DonationsCard.kt`, `ui/setup/DonationsSetupViewModel.kt`, `core/donations/DonationSources.kt`
- Modify: `ui/setup/SpotifySetupScreen.kt` (the card under `TwitchCard`), `ui/common/ConnectionChip.kt` (`ConnectionProblem.Donations(health)`; `connectionProblems(spotify, twitch, donations)`), `ui/AppScaffold.kt`, `AppGraph.kt`, both `strings.xml`
- Test: `ui/setup/DonationsCardTest.kt`, `ui/setup/DonationsSetupViewModelTest.kt`, `ui/common/ConnectionChipTest.kt`, `core/donations/DonationSourcesTest.kt`, `AppGraphTest.kt`

**Interfaces:**
- Produces:
  - `class DonationSources(settings: SettingsStore, donoLink: () -> DonationSource, streamElements: () -> DonationSource, scope)`: runs exactly the source `settings.donations.source` names for the app's whole life (independent of Twitch); switching stops the old one first; `val health: StateFlow<DonationHealth>`; `start()/stop()`. Connection snapshots for the Logbook come from this health (`Connected`→Up, `Retrying` > 60 s→Down, `KeyRefused`, key expiring ≤ 7 days→`KeyExpiring`).
  - `class DonationsSetupViewModel(settings, secrets: SecretStore, cursors: DonationCursorStore, donoLinkApi, seApi, health: StateFlow<DonationHealth>, scope, clock)`: `fun choose(kind)` (switching to a source = switching it on → `cursors.resetToNow(kind, now)`; the other's token stays saved), `fun pasteKey(text)` / `fun pasteJwt(text)` (saved to `SecretStore`, then tested: DonoLink `me()` → owner + expiry; SE `channel()` → channel name + currency), `fun setMinimum(cents: Long)` (≥ 1 cent), and a `state: StateFlow<DonationsCardState>`.
  - `DonationsCard`: segmented choice Geen · DonoLink · StreamElements; per source: where to get the key/JWT (one line + link button), paste field (masked), test result, minimum amount field in the source's currency, key expiry ("verloopt over 6 dagen", error tone ≤ 7 days), "new key needed" on `KeyRefused`; for StreamElements with `canCatchUp == false` a line "Tips gedaan terwijl Requestify uit staat, worden niet opgehaald."
  - Chip: `KeyRefused` → "DonoLink: nieuwe sleutel nodig" / "StreamElements: nieuwe token nodig"; `Retrying` > 60 s → disconnected; expiring ≤ 7 days → "DonoLink-sleutel verloopt over %d dagen".
  - `AppGraph`: build the handler, cursor store (`AppPaths.donationsFile`), APIs (shared `http`), sources and `DonationSources`; start order: `logbook.start()`, `channelPoints.startLifecycle()`, `gate.start()`, `controller.start()`, `intake.start()`, `donationHandler.start()`, `donationSources.start()`, then the Twitch session; `stop()` in reverse. The Logbook's `connections` input gets Spotify health, Twitch (EventSub state + login), and the donation health; its `gaps` input merges `BitsGapWatcher.gaps` and `StreamElementsSource.gaps`.
- [ ] **Step 1: Failing tests:** `DonationSourcesTest.runsOnlyTheChosenSource`, `switchingStopsTheOldOneFirst`; `DonationsSetupViewModelTest.switchingOnStartsAtNow` (cursor reset), `aPastedKeyIsSavedAndTested`, `theOtherTokenStaysWhenSwitching`, `aMinimumBelowOneCentIsRefused`; `DonationsCardTest.showsOwnerAndExpiry`, `expiringSoonUsesTheWarningTone`, `streamElementsWithoutCatchUpSaysSo`; `ConnectionChipTest.donationProblemsShowInTheChip`; `AppGraphTest.aDonoLinkDonationIsQueuedAndLogged` (MockEngine DonoLink + Spotify: one paid request in the queue, one Log row `Queued`, no chat reply without Twitch), `AppGraphTest.donationsRunWithoutTwitch`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS; render the Donations card offscreen (None, DonoLink connected with expiry, DonoLink key refused, StreamElements without catch-up; dark + light) for the user.
- [ ] **Step 5:** Commit `feat: the Donations card, donation health in the chip, and the sources wired`.

## Phase D — with the user

### Task 14: Final live test

- [ ] **Step 1:** `./gradlew jvmTest` green; the final whole-branch review done and its findings handled (one fix wave + one scoped re-review).
- [ ] **Step 2: Walk the user through it**, in Dutch, with `gradlew.bat run` from the plan-3 worktree:
  1. Donations card: choose DonoLink, paste a key → owner and expiry shown; set a minimum.
  2. A DonoLink donation with a Spotify link → queued as paid, chat reply with fixed prefix (Twitch logged in); Log row.
  3. A donation without a link → no request, Log row "ignored: no Spotify link".
  4. A donation below the minimum → Rejected with its track; Add anyway works.
  5. Close the app, donate with a link, start the app → caught up once.
  6. Switch donations to Geen, donate, switch back to DonoLink → that donation is not picked up.
  7. Log: filters, search, details timeline; a Twitch disconnect during a live stream shows a gap (or skip if not reproducible).
  8. StreamElements cannot be live-tested by the user: show the card states from the renders.
- [ ] **Step 3:** Fix findings (systematic debugging, test first); then superpowers:finishing-a-development-branch.
