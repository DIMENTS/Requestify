# Requestify Plan 2: Rules + Twitch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Viewers request Spotify songs through Twitch chat (`!sr`), Channel Points and Bits. One rules engine decides
what gets in, every decision is visible (Rejected tab, chat replies), and requests open and close with the stream.

**Architecture:** Every source turns its input into an `IncomingRequest` and hands it to one `RequestIntake`.
The intake resolves the track and calls `PlaybackController.submit`, which runs the pure `RuleEngine` under the
controller's `stateMutex`, so limits are atomic. The controller emits lifecycle events (`Started`, `Removed`) that
Channel Points turns into fulfil/refund through a persisted outbox. Twitch runs on the broadcaster's user token:
device-code login, Helix calls and one EventSub WebSocket. A `RequestsGate` derives "requests open" from the live
status, manual overrides and the 10-minute offline grace. Wiring stays manual constructor injection in `AppGraph`.

**Tech Stack:** unchanged. Kotlin 2.4.21 · Compose Multiplatform 1.12.2 on JetBrains Runtime 21 · material3
1.12.0-alpha03 (Expressive) · MaterialKolor 5.0.1 · Ktor 3.6.1: CIO plus the client `WebSockets` plugin, which
ships in `ktor-client-core`, so no new dependency · kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.13.0 ·
JUnit 4 + kotlin-test, coroutines-test, ktor-client-mock, Compose UI test (`runComposeUiTest`).

**Spec:** `docs/superpowers/specs/2026-11-17-requestify-plan-2-rules-twitch-design.md` (builds on
`2026-11-16-requestify-design.md`). The verified Twitch API facts (endpoints, bodies, error shapes, doc links) are in
`docs/superpowers/specs/2026-11-17-twitch-api-notes.md`. Every Twitch task reads the matching section there.

## Global Constraints

- **Where to work:** the worktree `worktrees/plan-2-rules-twitch` on branch `feat/plan-2-rules-twitch`.
  Run every command from the worktree root. Tests: `./gradlew jvmTest`; a single test class:
  `./gradlew jvmTest --tests "<FQN>"`. Baseline: 298 tests, all green.
- **Strings:** every user-facing string, chat replies included, lives in Compose resources, in both
  `src/commonMain/composeResources/values/strings.xml` (en) and `values-nl/strings.xml` (nl).
  - Chat replies use the app language, via `org.jetbrains.compose.resources.getString`.
  - Never show raw diagnostic strings (`SpotifyResult.Failed.message`, Twitch error bodies) to the user or in
    chat.
- **Spacing:** only through the `ui/theme/Spacing.kt` tokens in touched screens. Source chip colours stay fixed
  per source.
- **PlaybackController locks:** `spotifyMutex` serialises Spotify sequences. State and every save go under
  `stateMutex` (via `withState {}`), which is never held across a network call. New entry points follow the same
  rule.
- **Persisted JSON stays forward compatible:**
  - new fields get defaults;
  - `AppJson` ignores unknown keys and encodes defaults;
  - existing `@SerialName`s never change;
  - new sealed types that are saved get stable `@SerialName`s.

  Every new store uses `JsonFileStore` (atomic write, corrupt file kept aside, unreadable file never overwritten),
  like `SettingsStore`.
- **Twitch client:**
  - `TWITCH_CLIENT_ID = "wu2hg6lvxw5lqb2qbs046ou19nupnp"` (Public client; a constant in source; not a secret).
  - Scopes, exactly: `user:read:chat user:write:chat channel:manage:redemptions bits:read moderator:read:followers`.
- **Secrets:** the Twitch refresh token is stored only through `SecretStore` (DPAPI), under key
  `twitch.refresh_token`. Persist a rotated refresh token before the new access token is used.
- **Twitch API calls** carry `Client-Id: <TWITCH_CLIENT_ID>` and `Authorization: Bearer <user token>`. A 401
  triggers one forced refresh and one retry.
- **Spotify:** search `limit` stays ≤ 10.
- **UI choices** (user-picked mockups, viewable at
  `C:\Users\Dimit\Documents\Github Repositories\Requestify\.superpowers\brainstorm\595-1794945881\content\home-additions.html`
  and `screens-rules-blacklist-twitch.html`):
  - title bar **C** (Expressive toggle button);
  - Rejected tab **A** (one list, every row alike);
  - block from Home **B** (a small menu);
  - Rules **A** (one list in groups);
  - Blacklist **A** (tabs);
  - Twitch card **A** (one card).
- **Never drive the user's real desktop with scripted clicks.** Verify UI with Compose UI tests and offscreen
  renders.

## Review Focus

1. **Two `!sr` from one viewer arrive together** with "per viewer" at 1 → exactly one is accepted; the other is
   rejected with the limit. → Task 3 `concurrentSubmitsFromOneViewerRespectTheLimit`.
2. **Bits request while Spotify is unreachable** → it waits (even across a restart) and is queued once Spotify
   answers; it is never rejected or lost. → Task 8 `paidRequestWaitsForSpotifyAcrossRestart`.
3. **A Channel Points refund fails** (network down) and the app restarts → the refund is sent after the restart.
   → Task 21 `failedRefundIsRetriedAfterRestart`.
4. **The stream drops for 2 minutes** after the streamer closed requests by hand → free requests stay, and the
   manual close survives the drop. → Task 9 `shortDropKeepsFreeRequestsAndManualClose`.
5. **EventSub sends `session_reconnect`, or goes silent past the keepalive** → it moves to the new URL without
   resubscribing, or reconnects and resubscribes. Either way no event is handled twice. → Task 15
   `reconnectMessageMovesWithoutResubscribing`, `keepaliveSilenceReconnectsAndResubscribes`.

---

## Milestone 1: rules, Home additions, Twitch login and chat (ends with a live test)

### Task 1: Extract last-played and upcoming bookkeeping from `PlaybackController`

Pure refactor (plan-1 follow-up). Behaviour does not change; the 298 existing tests are the safety net.

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/LastPlayedBook.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/UpcomingBook.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/PlaybackController.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/UpcomingBookTest.kt`

**Interfaces:**
- Produces: `internal class LastPlayedBook(config: PlaybackConfig, clock: () -> Long)`.
  - It owns `lastPlayed`, `lastLive`, `spotifyRecent`, `recentAskedAtMs`, `loginGeneration`, and the
    `needsExtraPermission` `MutableStateFlow`.
  - It moves these over from the controller: `show(cp, playing, playIntentApplied)`, `lastPlayedCard(shown)`,
    `recentlyPlayedDue()`, `answeredRecentlyPlayed(result, askedUnder)`, `onLoginChanged()`, plus a `restore`
    from `PersistedPlayback`.
- Produces: `internal class UpcomingBook(config: PlaybackConfig, clock: () -> Long)`.
  - It owns `readAtMs`, `readForTrackId` and `stale`.
  - It has `due(active, trackId, handOverResult): Boolean`, `markRead(trackId)`, `markStale()`, `reset()`, and
    `from(spotifyQueue, handedOver): List<Track>`.
- Both are documented as "not thread-safe: used only under the controller's locks (`stateMutex` for
  `LastPlayedBook`, `spotifyMutex` for `UpcomingBook`)".

- [ ] **Step 1: Write `UpcomingBookTest`** with the cases now covered only through the controller:
  `fromLeavesOutHandedOverAndRepeatsCappedAt15` (20 tracks with repeats and the handed-over one → 15 distinct, the
  handed-over track absent) and `dueAfterRefreshIntervalOrTrackChangeOnly` (with `upcomingRefreshMs = 30_000`:
  false at +29 s on the same track; true at +30 s; true at once for another track id; false while not active).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.playback.UpcomingBookTest"`.
  Expected: compile FAIL (class missing).
- [ ] **Step 3: Move the code into the two classes.** The controller keeps its public API and its locks; it
  delegates. Its file should drop below ~560 lines.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all tests PASS (298 + new).
- [ ] **Step 5: Commit** `refactor: last-played and upcoming bookkeeping get their own classes`.

### Task 2: Queue row composables in their own file; minimum window size scales with the screen

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/QueueRows.kt`. It gets `QueueRow`, `SpotifyRow`,
  `QueueRowLayout`, `HandedOverRow`, `RequestDetails`, `TrackDetails`, `connectedShape`, the row size constants
  and `QUEUE_ROW_TAG`, all `internal`.
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/QueuePane.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/window/WindowSize.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/Main.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/window/WindowSizeTest.kt`

**Interfaces:**
- Produces: `fun awtMinimumSize(size: DpSize, density: Float, awtScale: Double): java.awt.Dimension`. It converts
  dp → device px with Compose's `density`, then device px → AWT user space with `awtScale`
  (`window.graphicsConfiguration.defaultTransform.scaleX`), rounding up.

- [ ] **Step 1: Write `WindowSizeTest`:**
  ```kotlin
  assertEquals(Dimension(720, 640), awtMinimumSize(DpSize(720.dp, 640.dp), density = 1.5f, awtScale = 1.5))
  assertEquals(Dimension(1080, 960), awtMinimumSize(DpSize(720.dp, 640.dp), density = 1.5f, awtScale = 1.0))
  assertEquals(Dimension(720, 640), awtMinimumSize(DpSize(720.dp, 640.dp), density = 1f, awtScale = 1.0))
  ```
- [ ] **Step 2: Run** the test. Expected: FAIL (function missing).
- [ ] **Step 3: Implement `awtMinimumSize`.** In `Main.kt`, set `window.minimumSize` from it, using
  `LocalDensity.current.density` and the window's AWT transform, again whenever the window moves to another
  screen (`GraphicsConfiguration` change).
- [ ] **Step 4: Move the row composables** to `QueueRows.kt` unchanged; `QueuePane.kt` keeps the pane, the tabs,
  the list and the empty states (below ~350 lines).
- [ ] **Step 5: Run** `./gradlew jvmTest`. Expected: all PASS (the `QueuePaneTest` bounds tests unchanged).
- [ ] **Step 6: Commit** `refactor: queue rows get their own file; the minimum window size follows the screen scale`.

### Task 3: `PlaybackController.submit`, targeted removal and lifecycle events

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/RequestEvents.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/model/RejectReason.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/PlaybackController.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/PlaybackControllerSubmitTest.kt`,
  `core/storage/SavedFormatCompatTest.kt` (round trip of every `RejectReason` name)

**Interfaces:**
- Produces (saved in rejected.json later, so the names are stable):
  ```kotlin
  @Serializable sealed interface RejectReason {
      NotATrack(kind: String) "not_a_track"; NoMatch "no_match"; Unplayable "unplayable";
      SpotifyUnavailable "spotify_unavailable"; ViewerBlocked "viewer_blocked"; TrackBlocked "track_blocked";
      ArtistBlocked "artist_blocked"; Explicit "explicit"; TooShort(minMs: Long) "too_short";
      TooLong(maxMs: Long) "too_long"; Closed "closed"; QueueFull(max: Int) "queue_full";
      ViewerLimit(max: Int) "viewer_limit"; Cooldown(remainingMs: Long) "cooldown"; Duplicate "duplicate";
      BelowMinimum(minBits: Int) "below_minimum"
  }
  ```
- Produces:
  ```kotlin
  data class PlaybackSnapshot(
      val queue: List<Request>, val handedOver: Request?, val playing: Request?,
      val history: List<PlayedEntry>, val nowPlaying: NowPlaying?,
  )
  sealed interface SubmitOutcome {
      /** [position] is 1-based in the queue; 0 when the id is already handed over or playing. */
      data class Accepted(val position: Int, val waitMs: Long) : SubmitOutcome
      data class Rejected(val reason: RejectReason) : SubmitOutcome
  }
  enum class RemoveReason { Streamer, WrongSong, ModRemove, StreamEnded, Blacklisted }
  sealed interface RequestEvent {
      data class Started(val request: Request) : RequestEvent
      data class Removed(val request: Request, val why: RemoveReason) : RequestEvent
  }
  // PlaybackController:
  val events: SharedFlow<RequestEvent>   // MutableSharedFlow(extraBufferCapacity = Channel.UNLIMITED), tryEmit
  suspend fun submit(request: Request, decide: (PlaybackSnapshot) -> RejectReason?): SubmitOutcome
  fun remove(id: String, why: RemoveReason = RemoveReason.Streamer)
  suspend fun removeMatching(why: RemoveReason, pick: (List<Request>) -> List<Request>): List<Request>
  fun snapshot(): PlaybackSnapshot     // from the state flows, for !song / !queue
  ```
- `waitMs` = the rest of the current track (`nowPlaying.positionAt(clock())`, 0 without one) + the handed-over
  track + the tracks queued before the new one.
- `submit` waits until the saved state is loaded: retry every `config.idlePollMs` while `syncState()` is false.
  It runs `decide` and the add in one `withState {}`.
- An id already queued, handed over or playing is not added again, and `decide` is not called; it returns
  `Accepted` with its current position (0 when handed over or playing).
- Events are emitted after the change is saved:
  - `Started` when `settleTrackChange` moves the handed-over request to `playing`;
  - `Removed` for `remove`/`removeMatching`.

  A request taken by `markHandedOver` is not "removed".

- [ ] **Step 1: Write the failing tests:**
  - `submitAcceptsAtPaidPriorityPositionWithWait`: queue [free r1 200 s]; now playing 100 s into a 200 s track;
    submit paid r2 → `Accepted(position = 1, waitMs = 100_000)`.
  - `submitRejectsWithTheDecidedReasonAndAddsNothing`: decide returns `RejectReason.QueueFull(25)` → `Rejected`;
    queue unchanged; nothing saved.
  - `concurrentSubmitsFromOneViewerRespectTheLimit` (Review Focus 1): launch two submits for viewer "v1" with a
    decide that rejects when the snapshot already holds a request of "v1". `runCurrent()`; exactly one
    `Accepted`, one `Rejected(ViewerLimit(1))`.
  - `submitWaitsForTheSavedState`: lock `queue.json` (as in `PlaybackControllerTest.lockQueueFile`), submit, then
    unlock, advance `idlePollMs` → `Accepted(1, …)` and the saved queue holds it after the earlier saved items.
  - `startedIsEmittedWhenTheHandedOverRequestPlays`, `removeEmitsRemovedWithReason`,
    `removeMatchingRemovesAtomicallyAndReturnsRemoved` (pick = free chat requests; paid ones stay).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.playback.PlaybackControllerSubmitTest"`.
  Expected: FAIL (unresolved `submit`).
- [ ] **Step 3: Implement** the interfaces above in the controller.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: atomic submit, targeted removal and request lifecycle events`.

### Task 4: Controller follow-ups (near-end polling, played-while-closed, reopening)

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/PlaybackController.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/queue/QueueStore.kt` (`PersistedPlayback.handedOverAtEpochMs: Long? = null`)
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/SpotifyApi.kt` (`recentlyPlayedSince`)
- Modify: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/FakeSpotifyPlayer.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/PlaybackControllerFollowUpTest.kt`, `SpotifyApiTest.kt`

**Interfaces:**
- Produces: `SpotifyPlayer.recentlyPlayedSince(afterEpochMs: Long): SpotifyResult<List<RecentTrack>>`
  (`GET me/player/recently-played?limit=50&after=<ms>`).

- [ ] **Step 1: Write the failing tests:**
  - `nearEndPollsFastOnlyWhilePlaying`: paused with 10 s left → the next tick comes after `idlePollMs` (3 s), not
    1 s.
  - `handedOverThatPlayedWhileAwayGoesToHistoryNotBackToHead`: saved state has `handedOver = r1`,
    `handedOverAtEpochMs = T`. Spotify plays another track and its queue lacks r1. `recentlyPlayedSince(T)`
    contains r1's track → r1 is in `history`, not in `queue`, and `Started(r1)` was emitted.
  - `handedOverMissingAndNotInRecentlyPlayedReturnsToHead`: same, but recently played lacks it → back at head (as
    before).
  - `reopeningMidSongHandsOverBeforeSpotifysOwnNext`: requests closed; track has 12 s left; open → within the same
    `runCurrent()` the head is added to Spotify's queue.
  - `SpotifyApiTest.recentlyPlayedSinceSendsAfterAndLimit50`.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The near-end poll needs `cp.isPlaying`.
  - `markHandedOver` stores `handedOverAtEpochMs = clock()` (it is cleared with `handedOver`).
  - In `settleTrackChange`, when the sent request is missing from Spotify's queue, the tick asks
    `recentlyPlayedSince(handedOverAt)` (a Spotify call: made in `tick()` before the state section, like
    `userQueueTrackIds`) and moves it to history when found.
  - A failed read keeps the old behaviour (back to the head).
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `fix: near-end polling only while playing; a request that played while away is not played twice`.

### Task 5: Request model additions, rules and the rules engine

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/model/Models.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/model/ChannelPointsRef.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/rules/Rules.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/rules/RuleEngine.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/blacklist/Blacklist.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/settings/Settings.kt` (`rules: Rules = Rules()`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/rules/RuleEngineTest.kt`,
  `core/blacklist/BlacklistTest.kt`, `core/storage/SavedFormatCompatTest.kt` (extend)

**Interfaces:**
- Consumes: `RejectReason` and `PlaybackSnapshot` (Task 3).
- Produces (model; every new field defaulted):
  ```kotlin
  Track(..., val artistIds: List<String> = emptyList())
  Requester(displayName, login = null, val twitchUserId: String? = null)
  Request(..., val rulesWaived: Boolean = false)   // "Add anyway": the streamer let it in past the rules
  val Request.isViewerRequest: Boolean get() = source != RequestSource.MANUAL && !rulesWaived
  val Request.isFreeViewerRequest: Boolean
      get() = payment == null && (source == RequestSource.CHAT || source == RequestSource.CHANNEL_POINTS)
  /** Request.sourceRef of a Channel Points request: "cp:<rewardId>:<redemptionId>", or "cpx:…" for a directly linked reward Requestify did not create. */
  data class ChannelPointsRef(val rewardId: String, val redemptionId: String, val managed: Boolean) {
      fun format(): String; companion object { fun parse(ref: String?): ChannelPointsRef? }
  }
  ```
- Produces (rules):
  ```kotlin
  @Serializable data class Rules(
      val maxQueueLength: Int = 25, val maxPerViewer: Int = 2, val cooldownMinutes: Int = 0,
      val noDuplicates: Boolean = true, val duplicateWindowMinutes: Int = 60,
      val minDurationMs: Long = 30_000, val maxDurationMs: Long = 420_000, val blockExplicit: Boolean = false,
  )
  data class RuleContext(
      val rules: Rules, val requestsOpen: Boolean, val snapshot: PlaybackSnapshot,
      val lastAcceptedAtMs: Long?, val blacklist: Blacklist, val nowMs: Long,
  )
  object RuleEngine { fun decide(request: Request, ctx: RuleContext): RejectReason? }   // null = accept
  ```
- Produces (blacklist model):
  ```kotlin
  @Serializable data class BlockedTrack(val track: Track, val atEpochMs: Long, val by: String? = null)
  @Serializable data class BlockedArtist(val id: String?, val name: String, val atEpochMs: Long, val by: String? = null)
  @Serializable data class BlockedViewer(val twitchUserId: String, val login: String, val atEpochMs: Long, val by: String? = null)
  @Serializable data class Blacklist(
      val tracks: List<BlockedTrack> = emptyList(), val artists: List<BlockedArtist> = emptyList(),
      val viewers: List<BlockedViewer> = emptyList(),
  ) {
      fun blocksViewer(r: Requester): Boolean      // by twitchUserId only
      fun blocksTrack(t: Track): Boolean           // by id
      fun blocksArtistOf(t: Track): Boolean        // by id; by case-insensitive name when the track or entry has no id
  }
  ```
  `by` is null for the streamer, otherwise the mod's login.

- [ ] **Step 1: Write `RuleEngineTest`.** One test per rule with the spec's defaults, plus the order:
  - `contentFiltersApplyToPaid`: paid + blacklisted artist → `ArtistBlocked`.
  - `paidSkipsLimits`: paid while closed, with the queue full and the viewer at the limit → `null`.
  - `orderIsBlacklistThenExplicitThenLength`: a blocked viewer requests an explicit 8:00 track → `ViewerBlocked`.
  - `freeWhileClosedIsClosedFirst`: → `Closed`, even when the queue is full.
  - `queueFullAt25`; `viewerLimitCountsQueuedAndHandedOverNotPlaying` (2 queued → `ViewerLimit(2)`; 1 queued + the
    playing one → `null`).
  - `cooldownReportsRemaining`: cooldown 5 min, last accepted 2 min ago → `Cooldown(180_000)`.
  - `duplicateQueuedHandedOverPlayingOrPlayedWithin60Min`: played 59 min ago → `Duplicate`; 61 min ago → `null`;
    `noDuplicates = false` → `null`.
  - `durationBounds`: 0:29 → `TooShort(30_000)`, 0:30 ok, 7:00 ok, 7:01 → `TooLong(420_000)`.
  - `explicitOnlyWhenBlocked`.
- [ ] **Step 2: Write `BlacklistTest`:**
  - `artistMatchesByIdOrByNameWithoutIds`;
  - `viewerMatchesByUserIdNotByName` (same login, other id → not blocked).

  And extend `SavedFormatCompatTest` with `oldQueueWithoutNewFieldsStillReads` (a queue.json without `artistIds`,
  `twitchUserId`, `rulesWaived` reads with defaults).
- [ ] **Step 3: Run** the three test classes. Expected: FAIL.
- [ ] **Step 4: Implement.** Check order exactly as the spec's "Order of checks" (input checks happen in the
  intake, not here):
  1. viewer blocked;
  2. track blocked;
  3. artist blocked;
  4. explicit;
  5. too short;
  6. too long;
  7. then, only when `payment == null`: closed → queue full → viewer limit → cooldown → duplicate.

  `isViewerRequest == false` requests are never passed to the engine (the intake skips it).
- [ ] **Step 5: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 6: Commit** `feat: rules engine, blacklist model and the request fields it needs`.

### Task 6: Spotify catalog additions for the intake and the blacklist

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/SpotifyApi.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/PlaybackController.kt` (`healthOf` handles `NotFound`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/spotify/SpotifyApiTest.kt` (+ fixtures in `src/jvmTest/resources/spotify/`)

**Interfaces:**
- Produces:
  - `SpotifyResult.NotFound`: HTTP 400, or 404 without `NO_ACTIVE_DEVICE`. Previously these were
    `Failed("HTTP 404")`.
  - `Track.artistIds` is filled from `artists[].id`.
  - `SpotifyCatalog.searchArtists(query: String, limit: Int = 5): SpotifyResult<List<ArtistRef>>` and
    `SpotifyCatalog.artist(id: String): SpotifyResult<ArtistRef>`, with
    `data class ArtistRef(val id: String, val name: String, val imageUrl: String?)`.
  - `SpotifyInput.ArtistId(id)`: `classifySpotifyInput` returns it for artist links, instead of `NotATrack("artist")`.
    Callers that only take tracks treat it as `NotATrack("artist")`.
  - `Track.isPlayable: Boolean = true`. `track(id)` and `searchTracks` send `market=from_token` and read
    `is_playable` when Spotify sends it; absent means playable. This field is not persisted meaningfully: it
    defaults to true on read.

- [ ] **Step 1: Write the failing tests:** `trackParsesArtistIdsAndIsPlayable`, `notFoundFor400And404WithoutDeviceReason`,
  `searchArtistsUsesTypeArtistAndLimitAtMost10`, `artistById`, `artistLinkClassifiesAsArtistId`
  (`https://open.spotify.com/intl-nl/artist/0OdUWJ0sBjDrqHygGUXeCF?si=x` → `ArtistId("0OdUWJ0sBjDrqHygGUXeCF")`).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.spotify.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Check the live Spotify docs for `is_playable` after the February 2026 migration. If
  the field is gone, leave `isPlayable` at its default and note it in the commit message.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Spotify artist search, artist ids, playable flag and not-found answers`.

### Task 7: Blacklist, Rejected and pending-resolve stores

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/blacklist/BlacklistStore.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/intake/RejectedStore.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/intake/IncomingRequest.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/intake/PendingResolveStore.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/storage/AppPaths.kt` (`blacklistFile` = `blacklist.json`,
  `rejectedFile` = `rejected.json`, `pendingFile` = `pending.json`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/blacklist/BlacklistStoreTest.kt`, `core/intake/RejectedStoreTest.kt`,
  `core/intake/PendingResolveStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class IncomingRequest(
      val id: String, val input: String, val linkOnly: Boolean, val requester: Requester,
      val source: RequestSource, val payment: Payment?, val sourceRef: String? = null,
      val receivedAtEpochMs: Long, val replyTo: String? = null,   // chat message id to reply to
  )
  @Serializable data class RejectedEntry(
      val id: String, val request: Request?, val input: String, val requester: Requester,
      val source: RequestSource, val payment: Payment?, val reason: RejectReason,
      val atEpochMs: Long, val refunded: Boolean,
  )
  class RejectedStore(file: JsonFileStore<List<RejectedEntry>>) {
      val entries: StateFlow<List<RejectedEntry>>   // newest first
      val paidCount: StateFlow<Int>                 // the badge
      fun add(entry: RejectedEntry): Boolean; fun remove(id: String): Boolean
      companion object { const val MAX = 50 }
  }
  class BlacklistStore(file: JsonFileStore<Blacklist>) {
      val blacklist: StateFlow<Blacklist>
      fun addTrack(track: Track, by: String?, at: Long): Boolean; fun addArtist(id: String?, name: String, by: String?, at: Long): Boolean
      fun addViewer(userId: String, login: String, by: String?, at: Long): Boolean
      fun removeTrack(trackId: String): Boolean; fun removeArtist(idOrName: String): Boolean; fun removeViewer(userId: String): Boolean
  }
  class PendingResolveStore(file: JsonFileStore<List<IncomingRequest>>) {
      val pending: StateFlow<List<IncomingRequest>>; fun add(r: IncomingRequest): Boolean; fun remove(id: String): Boolean
  }
  ```
- All three follow `SettingsStore`'s rules:
  - a file unreadable at start → in-memory defaults plus `loadFailed`, and nothing is written over it;
  - a write that fails returns false and leaves the state unchanged.
- An entry that already exists (same id, or the same blocked item) is not added twice.

- [ ] **Step 1: Write the failing tests:**
  - `RejectedStoreTest.capKeepsPaidAndDropsOldestFreeFirst`: 50 entries (2 paid), add a free one → 50 entries;
    the oldest free one is gone; both paid stay.
  - `RejectedStoreTest.paidNeverDroppedEvenAboveCap`: 51 paid → 51 kept.
  - `RejectedStoreTest.paidCountIsTheBadge`.
  - `RejectedStoreTest.roundTripAcrossRestart`.
  - `BlacklistStoreTest.addIsIdempotentAndPersists`.
  - `BlacklistStoreTest.unreadableFileIsNeverOverwritten`: lock the file as in `PlaybackControllerTest`.
  - `PendingResolveStoreTest.roundTrip`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: crash-safe blacklist, rejected and pending-resolve stores`.

### Task 8: `RequestIntake`

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/intake/RequestIntake.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/AppGraph.kt` (build the stores and the intake; nothing calls
  `submit` yet)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/intake/RequestIntakeTest.kt` (with a `FakeSpotifyCatalog`
  in the same test folder)

**Interfaces:**
- Consumes: `PlaybackController.submit/removeMatching/enqueue` (Task 3), `RuleEngine` (Task 5), the stores (Task 7),
  `SpotifyCatalog` (Task 6).
- Produces:
  ```kotlin
  sealed interface IntakeOutcome {
      data class Accepted(val request: Request, val position: Int, val waitMs: Long) : IntakeOutcome
      /** [listed]: it is in the Rejected list (false for free requests of blocked viewers). */
      data class Rejected(val reason: RejectReason, val track: Track?, val listed: Boolean) : IntakeOutcome
      data object Ignored : IntakeOutcome   // link-only input without any Spotify link: not a request
      data object Pending : IntakeOutcome   // paid, Spotify unreachable: waits in pending.json
  }
  class RequestIntake(
      controller: PlaybackController, catalog: SpotifyCatalog, settings: SettingsStore,
      blacklist: BlacklistStore, rejected: RejectedStore, pending: PendingResolveStore,
      requestsOpen: StateFlow<Boolean>, scope: CoroutineScope, clock: () -> Long,
      retryDelaysMs: List<Long> = listOf(5_000, 15_000, 30_000, 60_000),   // then every 60 s
  ) {
      val pendingResolved: SharedFlow<Pair<IncomingRequest, IntakeOutcome>>  // a pending paid request settled (for its reply)
      fun start()                                                           // resumes pending.json
      suspend fun submit(incoming: IncomingRequest): IntakeOutcome
      fun recordRejection(incoming: IncomingRequest, reason: RejectReason, track: Track? = null)
      fun addAnyway(entryId: String); fun dismiss(entryId: String)
      suspend fun blockTrack(track: Track, by: String?): Boolean
      suspend fun blockArtist(id: String?, name: String, by: String?): Boolean
      suspend fun blockViewer(userId: String, login: String, by: String?): Boolean
  }
  ```
- **Resolving the track:**
  - link → `catalog.track(id)`; search text → `catalog.searchTracks(text, 5)`, taking the first `isPlayable`
    result.
  - `NotFound` or an empty search → `NoMatch`; a track that is not playable → `Unplayable`.
  - A non-track link → `NotATrack(kind)`.
  - `linkOnly` without any Spotify link → `Ignored`.
  - Any other failure is "Spotify unavailable": free → `Rejected(SpotifyUnavailable)`; paid → `Pending`, retried on
    `retryDelaysMs`, kept across restarts.
- **Cooldown memory:** a `Map<twitchUserId, lastAcceptedAtMs>` in memory, updated inside the `decide` lambda
  when it accepts, so it stays atomic with the submit.
- **Rejections:**
  - Every rejection is added to `RejectedStore` except `ViewerBlocked` on a free request (`listed = false`).
  - `refunded` = `ChannelPointsRef.parse(sourceRef)?.managed == true`. The refund itself is Task 21's.
- **`addAnyway`** enqueues `entry.request.copy(rulesWaived = true)` and removes the entry. **`dismiss`** removes
  it.
- **Blocking** adds to the store, then sweeps. `controller.removeMatching(RemoveReason.Blacklisted)` takes the
  queued `isViewerRequest` requests that the new entry matches, and each one becomes a `RejectedEntry` with the
  matching reason (a free one of a blocked viewer is not listed).

- [ ] **Step 1: Write the failing tests:**
  - `linkResolvesAndIsQueuedWithPosition`;
  - `searchTakesFirstPlayableResult`;
  - `albumLinkIsNotATrackAndListed`;
  - `bitsWithoutLinkIsIgnored`;
  - `freeRequestWhileSpotifyDownIsRejectedSpotifyUnavailable`;
  - `paidRequestWaitsForSpotifyAcrossRestart` (Review Focus 2): the catalog fails → `Pending`, and
    `pending.json` holds it. Build a new intake on the same files, `start()`, the catalog answers →
    `pendingResolved` emits `Accepted` and the queue holds it.
  - `blockedViewerFreeRequestIsNotListedButPaidIs`;
  - `cooldownIsAtomicWithSubmit` (two submits, cooldown 5 min → the second is `Cooldown`);
  - `addAnywaySkipsRulesAndRemovesEntry`;
  - `blockArtistSweepsQueuedViewerRequestsButNotOwnOrWaived` (a queued MANUAL and a `rulesWaived` request of
    that artist stay; a free chat one moves to Rejected as `ArtistBlocked`; a `Removed(…, Blacklisted)` event is
    emitted for it).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.intake.RequestIntakeTest"`.
  Expected: FAIL.
- [ ] **Step 3: Implement**, then wire the stores and the intake in `AppGraph`; `start()` in `AppGraph.start()`.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS (`AppGraphTest` included).
- [ ] **Step 5: Commit** `feat: request intake resolves, applies the rules and records rejections`.

### Task 9: `RequestsGate` (open/closed, overrides, offline grace, end-of-stream clear)

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/stream/RequestsGate.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/storage/AppPaths.kt` (`streamFile` = `stream.json`)
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/AppGraph.kt`: `requestsOpen` comes from the gate; the gate's
  `onStreamEnded` clears with `controller.removeMatching(RemoveReason.StreamEnded) { it.filter(Request::isFreeViewerRequest) }`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/stream/RequestsGateTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class StreamState(
      val live: Boolean = false,
      val override: Boolean? = null,               // manual open/closed; null = follow the stream
      val overrideAfterStream: Boolean = false,    // set while offline: survives the stream's end, postpones the clear
      val offlineSinceMs: Long? = null,            // in the 10-minute grace
      val clearPostponed: Boolean = false,
      val openWithoutTwitch: Boolean = true,
  )
  data class GateStatus(
      val open: Boolean, val twitch: Boolean, val live: Boolean, val manual: Boolean,
      val clearAtEpochMs: Long?, val clearPostponed: Boolean,
  )
  class RequestsGate(
      file: JsonFileStore<StreamState>, twitchConnected: StateFlow<Boolean>, scope: CoroutineScope,
      clock: () -> Long, onStreamEnded: suspend () -> Unit, graceMs: Long = 600_000,
  ) {
      val open: StateFlow<Boolean>; val status: StateFlow<GateStatus>
      fun start()                       // a grace that ran out while the app was off ends the stream at once
      fun onLive(live: Boolean)         // stream.online/offline and the REST live status (same call)
      fun toggle()                      // the title-bar button
  }
  ```
- **State machine** (spec "Requests open/closed and the stream"):
  - **Twitch not connected:** `open = openWithoutTwitch`; `toggle` flips it.
  - **Twitch connected, in a grace** (`offlineSinceMs != null`): `open = override == true && overrideAfterStream`.
    A drop closes requests; only an open made after the stream went offline keeps them open.
  - **Twitch connected, otherwise:** `open = override ?: live`.
  - **`toggle` while live** sets `override = !open` and `overrideAfterStream = false`.
  - **`onLive(false)`** while live: `live = false`, `offlineSinceMs = now`.
  - **`onLive(true)`:**
    - within the grace: a drop. `offlineSinceMs = null`; the override is kept.
    - otherwise: a real start. `override = null`, `overrideAfterStream = false`, `clearPostponed = false`.
  - **Grace ends** (`offlineSinceMs + graceMs`): the stream really ended. `offlineSinceMs = null`. An override set
    while live ends. Then, if `open` (an after-stream open) → `clearPostponed = true`; else `onStreamEnded()`.
  - **`toggle` while offline** (outside or inside the grace) sets `override` and `overrideAfterStream = true`.
    When it closes while `clearPostponed` → `onStreamEnded()`, `clearPostponed = false`.
  - **Repeated values:** `onLive` with the same value as `live` does nothing (the REST check repeats events).

- [ ] **Step 1: Write the failing tests** (virtual time, temp dir):
  - `followsTheStream`;
  - `shortDropKeepsFreeRequestsAndManualClose` (Review Focus 4): live, toggle → closed, offline, +2 min, online →
    closed by override and `onStreamEnded` not called;
  - `tenMinutesOfflineEndsTheStreamOnce` (exactly one `onStreamEnded` at +600 s);
  - `restartDuringGraceStillClearsOnTime` (new gate on the same file at +5 min, then +5 min → cleared);
  - `restartAfterGraceClearsAtStart`;
  - `manualOpenAfterStreamPostponesClearUntilClosed`;
  - `manualOpenWhileOfflineEndsAtNextRealStartAndEnd`;
  - `withoutTwitchItIsAPlainRememberedSwitchDefaultOpen`;
  - `statusShowsClearTimeDuringGrace`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.stream.RequestsGateTest"`. Expected: FAIL.
- [ ] **Step 3: Implement**, and wire it in `AppGraph`. Use `MutableStateFlow(false)` for `twitchConnected` until
  Task 16. `requestsOpen` for the controller and the intake is `gate.open`; `gate.start()` runs in
  `AppGraph.start()`.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS. `AppGraphTest` still sees requests open without
  Twitch.
- [ ] **Step 5: Commit** `feat: requests open and close with the stream, with a 10-minute grace and manual overrides`.

### Task 10: Title bar: requests toggle and one connection chip

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/window/RequestsToggle.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/common/ConnectionChip.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/window/TitleBar.kt`, `ui/AppScaffold.kt`,
  `ui/common/SpotifyStatusChip.kt` (keep its texts, reuse them), `AppGraph.kt` (expose `gate`)
- Modify: both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/window/RequestsToggleTest.kt`,
  `ui/common/ConnectionChipTest.kt`, `ui/window/TitleBarTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface ConnectionProblem {
      data class Spotify(val health: SpotifyHealth) : ConnectionProblem
      data object TwitchLoginNeeded : ConnectionProblem
      data object TwitchDisconnected : ConnectionProblem
      data object RewardRemoved : ConnectionProblem
  }
  data class TwitchHealth(val loginNeeded: Boolean = false, val disconnected: Boolean = false, val rewardRemoved: Boolean = false)
  fun connectionProblems(spotify: SpotifyHealth, twitch: TwitchHealth): List<ConnectionProblem>   // most important first
  @Composable fun ConnectionChip(problems: List<ConnectionProblem>, onClick: () -> Unit, modifier: Modifier = Modifier)
  @Composable fun RequestsToggle(status: GateStatus, onToggle: () -> Unit, modifier: Modifier = Modifier)
  ```
- **Which problems count, most important first:**
  1. Spotify `LoggedOut`, `LoginNeeded`, `PremiumRequired`;
  2. `TwitchLoginNeeded`;
  3. Spotify `NoActiveDevice`;
  4. Spotify `Unreachable`, `RateLimited`;
  5. `TwitchDisconnected`;
  6. `RewardRemoved`.

  `Ok` and `NothingPlaying` are not problems.
- The chip shows "All connected" / "Alles verbonden" with a check while the list is empty, otherwise the first
  problem's text. A click navigates to Connections.
- **Toggle labels:**

  | State | en | nl |
  |---|---|---|
  | with Twitch, live, open | "Live · requests open" | "Live · verzoeken open" |
  | with Twitch, offline, closed | "Offline · requests closed" | "Offline · verzoeken dicht" |
  | without Twitch, open | "Requests open" | "Verzoeken open" |
  | without Twitch, closed | "Requests closed" | "Verzoeken dicht" |

  Overrides combine the live part and the open part in the same way.
- **Toggle look:** open is filled `primary` with a rounded-square shape; closed is outlined with a pill shape. It
  animates with the motion scheme's spatial spec. A tooltip explains the state. During the grace it shows when
  the free requests will be cleared ("Free requests clear at 23:41"), or "Clearing waits until you close
  requests".
- `TwitchHealth()` is all-false until Task 16 wires it.

- [ ] **Step 1: Write the failing tests:**
  - `problemsAreOrderedMostImportantFirst`;
  - `nothingPlayingIsNotAProblem`;
  - `chipSaysAllConnectedWithoutProblems`;
  - `toggleLabelsForEveryState` (all four en strings above, plus "Offline · requests open" in an override);
  - `toggleClickCallsOnToggle`;
  - `TitleBarTest`: the toggle sits in the bar's client area, so it is clickable, not a drag area.
- [ ] **Step 2: Run** the three test classes. Expected: FAIL.
- [ ] **Step 3: Implement.** Replace `SpotifyStatusChip` in `TitleBar` with `ConnectionChip`, and pass
  `RequestsToggle` into the `requestsSwitch` slot from `AppScaffold`.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: the title bar opens and closes requests and shows one connection status`.

### Task 11: Home: Rejected tab, row menu and the Now Playing block menu

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/QueuePane.kt`, `QueueRows.kt`, `NowPlayingPane.kt`,
  `HomeScreen.kt`, `HomeViewModel.kt`; `ui/common/Icons.kt` (block, more-vert, music-off, person-off)
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/RejectedList.kt`
- Modify: both `strings.xml`; `AppGraph.kt` (pass the intake and the rejected store to `HomeViewModel`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/home/RejectedListTest.kt`, `QueuePaneTest.kt`,
  `NowPlayingPaneTest.kt`, `HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `RequestIntake.addAnyway/dismiss/blockTrack/blockArtist/blockViewer`, `RejectedStore.entries/paidCount`.
- Produces:
  - `HomeUiState.rejected: List<RejectedEntry> = emptyList()` and `HomeUiState.rejectedBadge: Int = 0`.
  - `HomeViewModel.onAddAnyway(id)`, `onDismiss(id)`.
  - `onBlock(target: BlockTarget)`, with
    `sealed interface BlockTarget { Song(track); Artist(track); Viewer(requester) }`.
  - `onBlockCurrentAndSkip(target: BlockTarget)`. For Now Playing, song and artist also call `controller.skip()`.
  - `NowPlayingPane(..., onBlock: (BlockTarget) -> Unit)`.
  - `QueuePane(..., onBlock: (Request, BlockTarget) -> Unit, onAddAnyway: (String) -> Unit, onDismiss: (String) -> Unit)`.

- **Tabs:** **Queue · n / Played / Rejected** with a badge for `rejectedBadge` (an M3 `Badge`, error colours).
- **Rejected rows:** the queue's connected-row style.
  - Source chip, requester, and the reason in `colorScheme.error` with an icon; "· points refunded" when
    `refunded`.
  - An "Add anyway" tonal icon button (only when `request != null`) and a Dismiss X.
  - An empty state.
  - Every `RejectReason` has an en/nl text: "Longer than 7:00", "Below the minimum (100 Bits)", "On the
    blacklist", "Already 2 requests in the queue", …
- **Queue row:** the X becomes a ⋮ menu with Remove, Block song, Block artist, and Block viewer (the last only
  when `requester.twitchUserId != null`).
- **Now Playing:** a block button at the start of the button group opens a menu:
  - "Block song and skip";
  - "Block artist and skip";
  - "Block @name", for a request from a Twitch viewer.
  The existing fixed-slot guarantees stay: progress and controls keep identical positions in every state.

- [ ] **Step 1: Write the failing tests:**
  - `RejectedListTest.rowShowsReasonAndActions` (en);
  - `RejectedListTest.rowWithoutTrackOffersOnlyDismiss`;
  - `QueuePaneTest.rejectedTabShowsPaidBadge`;
  - `QueuePaneTest.rowMenuOffersBlockViewerOnlyForTwitchViewers`;
  - `NowPlayingPaneTest.blockMenuItems`;
  - `NowPlayingPaneTest.controlsKeepTheirPlaceInEveryState` (existing test, now including the block button);
  - `HomeViewModelTest.blockSongFromNowPlayingBlocksAndSkips`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.ui.home.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Rejected tab, block menus on Home`.

### Task 12: Rules screen and navigation

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/rules/RulesScreen.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/AppScaffold.kt` (`Destination.Rules`, icon tune), `ui/common/Icons.kt`
- Modify: both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/rules/RulesScreenTest.kt`, `ui/AppScaffoldTest.kt`

**Interfaces:**
- Consumes: `SettingsStore.tryUpdate`, `Settings.rules`, `Settings.paidPriority`.
- Produces: `@Composable fun RulesScreen(settings: SettingsStore)`; `Destination` order is Home, Connections,
  Rules, Settings (Blacklist is inserted after Rules in Task 17).

- **Layout:** one scrolling page (choice A), using the `PageScaffold` like Settings.
  - On top, an info note in `tertiaryContainer` with a bolt icon: "Paid requests skip the limits. Blacklist,
    length and explicit always apply." / "Betaalde verzoeken slaan de limieten over. Blacklist, lengte en
    expliciet gelden altijd."
  - Group "Queue" / "Wachtrij":
    - max queue length: stepper 1–100;
    - per viewer at once: stepper 1–10;
    - cooldown per viewer: stepper "off", then 1–60 min;
    - no duplicates: switch, plus a stepper 15–240 min in steps of 15, shown when on;
    - paid requests go first: switch.
  - Group "Songs" / "Nummers":
    - min length: stepper 0:00–2:00 in steps of 0:15;
    - max length: stepper 2:00–15:00 in steps of 0:30;
    - block explicit: switch.
- A stepper's buttons disable at its bounds. A failed save shows the existing save-failed text and leaves the
  value as it was.

- [ ] **Step 1: Write the failing tests:**
  - `RulesScreenTest.defaultsShown` (25, 2, off, on + 60 min, 0:30, 7:00, off);
  - `steppersRespectBoundsAndSteps` (max length at 15:00 has + disabled; 7:00 + → 7:30);
  - `changesAreSaved`;
  - `AppScaffoldTest.railHasRules`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Rules screen`.

### Task 13: Twitch login (device code), refresh and validation

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/auth/TokenResult.kt`. Move `TokenResult` here from
  `core/spotify/SpotifyApi.kt` (package `nl.diments.requestify.core.auth`) and update its imports.
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/auth/TwitchAuth.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/settings/TwitchSettings.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/settings/Settings.kt` (`twitch: TwitchSettings = TwitchSettings()`),
  `core/secrets/SecretStore.kt` (`SecretKeys.TWITCH_REFRESH_TOKEN = "twitch.refresh_token"`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/auth/TwitchAuthTest.kt` (`MockEngine`; read
  "1. Device Code Grant Flow", "2. Refresh" and "3. Validate" in the API notes)

**Interfaces:**
- Produces:
  ```kotlin
  const val TWITCH_CLIENT_ID = "wu2hg6lvxw5lqb2qbs046ou19nupnp"
  val TWITCH_SCOPES = listOf("user:read:chat", "user:write:chat", "channel:manage:redemptions", "bits:read", "moderator:read:followers")
  @Serializable data class TwitchAccount(val userId: String, val login: String, val displayName: String, val broadcasterType: String = "")
  @Serializable enum class ChatPermission { Everyone, Followers, Subscribers, Vips, Moderators }
  @Serializable data class ChatCommandNames(
      val request: String = "sr", val song: String = "song", val queue: String = "queue", val wrongSong: String = "wrongsong",
      val skip: String = "skip", val remove: String = "remove", val blacklist: String = "blacklist",
  )
  @Serializable data class ChannelPointsSettings(
      val enabled: Boolean = false, val rewardId: String? = null, val managed: Boolean = true,
      val title: String? = null, val cost: Int = 500, val prompt: String? = null, val rewardRemoved: Boolean = false,
  )
  @Serializable data class TwitchSettings(
      val account: TwitchAccount? = null, val chatEnabled: Boolean = true,
      val chatPermission: ChatPermission = ChatPermission.Everyone, val commands: ChatCommandNames = ChatCommandNames(),
      val channelPoints: ChannelPointsSettings = ChannelPointsSettings(), val bitsEnabled: Boolean = true, val bitsMinimum: Int = 100,
  )
  sealed interface TwitchAuthStatus { data object LoggedOut; data object LoggedIn; data class LoginNeeded(val reason: String) }
  data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresAtEpochMs: Long, val intervalMs: Long)
  sealed interface DeviceLoginResult { data class Success(val userId: String, val login: String); data object Expired; data class Failed(val reason: String) }
  class TwitchAuth(
      http: HttpClient, secrets: SecretStore, clock: () -> Long,
      idBaseUrl: String = "https://id.twitch.tv", requestTimeoutMs: Long = 15_000,
  ) {
      val status: StateFlow<TwitchAuthStatus>
      suspend fun startDeviceLogin(): DeviceCode?          // null when Twitch cannot be reached
      suspend fun awaitDeviceLogin(code: DeviceCode): DeviceLoginResult
      suspend fun token(forceRefresh: Boolean = false): TokenResult
      suspend fun validate(): TokenResult                   // GET /oauth2/validate; 401 → one refresh; dead → LoginNeeded
      fun markLoginNeeded(reason: String)                   // EventSub authorization_revoked, or a 401 after refresh
      fun logout()
  }
  ```
- **Polling:** `awaitDeviceLogin` polls every `intervalMs` (with `kotlinx.coroutines.delay`, so it is testable in
  virtual time) until `expiresAtEpochMs`. A 400 with message `authorization_pending` → keep polling; any other
  error → `Failed`; time over → `Expired`.
- **On success:** persist the refresh token, cache the access token, then `validate` to learn the user id and
  login. Bump the generation; ignore stale refreshes, as in `SpotifyAuth`.
- **Refresh:**
  - `client_id`, `grant_type=refresh_token` and a URL-encoded `refresh_token`; never two at once (a `Mutex`).
  - On success, persist the rotated token before returning the access token.
  - 400 "Invalid refresh token" or 401 → remove the secret, `LoginNeeded`. Anything else → `Unavailable`, keep
    the login.

- [ ] **Step 1: Write the failing tests:**
  - `startDeviceLoginSendsClientIdAndScopes` (form `client_id`, `scopes` = the five, space-joined);
  - `pollingContinuesOnAuthorizationPendingThenSucceeds` (virtual time: two pending answers 5 s apart, then
    tokens → `Success`, and the refresh token is stored);
  - `expiredCodeEndsWithExpired`;
  - `otherErrorEndsWithFailed`;
  - `refreshPersistsRotatedTokenBeforeReturning`;
  - `invalidRefreshTokenMeansLoginNeeded`;
  - `networkErrorDuringRefreshKeepsLogin`;
  - `concurrentTokenCallsRefreshOnce`;
  - `validateRefreshesOnceOn401`;
  - `logoutRemovesSecretFirst`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.auth.TwitchAuthTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** (mirror `SpotifyAuth`'s structure: `stateLock`, `generation`, `cached`).
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS (the `TokenResult` move compiles everywhere).
- [ ] **Step 5: Commit** `feat: Twitch device-code login with rotating refresh and hourly validation support`.

### Task 14: Twitch Helix client

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/TwitchApi.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/TwitchApiTest.kt` (+ JSON fixtures in
  `src/jvmTest/resources/twitch/`, shaped as in the API notes sections 5–9, 12–14)

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface TwitchResult<out T> {
      data class Ok<T>(val value: T); data object NotFound; data class Forbidden(val message: String)
      data class BadRequest(val message: String); data object LoginNeeded; data object LoggedOut
      data class RateLimited(val retryAfterMs: Long); data class Failed(val message: String)
  }
  data class TwitchChannel(val id: String, val login: String, val displayName: String, val broadcasterType: String)
  data class TwitchUserRef(val id: String, val login: String, val displayName: String)
  data class SentChat(val messageId: String, val isSent: Boolean)
  data class Reward(val id: String, val title: String, val cost: Int, val prompt: String, val isEnabled: Boolean,
                    val isPaused: Boolean, val isUserInputRequired: Boolean, val skipsRequestQueue: Boolean)
  data class RewardSpec(val title: String, val cost: Int, val prompt: String)
  data class RewardPatch(val title: String? = null, val cost: Int? = null, val prompt: String? = null,
                         val isEnabled: Boolean? = null, val isPaused: Boolean? = null)
  data class Redemption(val id: String, val rewardId: String, val userId: String, val userLogin: String,
                        val userName: String, val userInput: String, val redeemedAtEpochMs: Long)
  enum class RedemptionStatus { FULFILLED, CANCELED }
  class TwitchApi(
      http: HttpClient, token: suspend (forceRefresh: Boolean) -> TokenResult, broadcasterId: () -> String?,
      baseUrl: String = "https://api.twitch.tv/helix", requestTimeoutMs: Long = 15_000,
  ) {
      suspend fun me(): TwitchResult<TwitchChannel>                       // GET users (token user)
      suspend fun userByLogin(login: String): TwitchResult<TwitchUserRef?>
      suspend fun isLive(): TwitchResult<Boolean>                         // GET streams?user_id=
      suspend fun follows(userId: String): TwitchResult<Boolean>          // GET channels/followers?broadcaster_id=&user_id=
      suspend fun sendChat(message: String, replyTo: String?): TwitchResult<SentChat>
      suspend fun rewards(onlyManageable: Boolean): TwitchResult<List<Reward>>
      suspend fun createReward(spec: RewardSpec): TwitchResult<Reward>     // user input required, does not skip the queue
      suspend fun updateReward(id: String, patch: RewardPatch): TwitchResult<Reward>
      suspend fun unfulfilledRedemptions(rewardId: String): TwitchResult<List<Redemption>>   // all pages, first=50
      suspend fun setRedemptionStatus(rewardId: String, redemptionId: String, status: RedemptionStatus): TwitchResult<Unit>
      suspend fun subscribe(type: String, version: String, condition: Map<String, String>, sessionId: String): TwitchResult<Unit>
  }
  ```
- **Mapping:**
  - 200/202/204 → `Ok`; on `subscribe`, 409 → `Ok`.
  - 400 → `BadRequest(message)`; 403 → `Forbidden(message)`; 404 → `NotFound`.
  - 401 → one `token(true)` retry, then `LoginNeeded`.
  - 429 → `RateLimited`, from `Ratelimit-Reset` (epoch seconds) or 5 s.
  - Anything else, a timeout or an exception → `Failed`.
- **Token answers:** `TokenResult.LoggedOut` → `LoggedOut`; `TokenResult.LoginNeeded` → `LoginNeeded`.
- **Without a broadcaster id** → `LoggedOut`.
- **`sendChat`:** body `broadcaster_id`, `sender_id` (both = the broadcaster), `message` (cut to 500 chars), and
  `reply_parent_message_id` when given.
- **`setRedemptionStatus`:** PATCH with `id`, `broadcaster_id`, `reward_id` in the query, and
  `{"status":"FULFILLED"}` as the body.

- [ ] **Step 1: Write the failing tests:** one per method for URL, headers (`Client-Id`, `Bearer`) and body, plus:
  - `subscribeTreats202And409AsOk`;
  - `retriesOnceAfter401WithForcedRefresh`;
  - `redemptionStatus404IsNotFound`;
  - `createRewardDuplicateTitleIsBadRequestWithMessage`;
  - `nonAffiliateIs403Forbidden`;
  - `followersEmptyDataMeansNotFollowing`;
  - `sendChatReportsDroppedMessages` (`is_sent: false`);
  - `unfulfilledRedemptionsFollowsPagination`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.TwitchApiTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** (structure like `SpotifyApi.execute`).
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Twitch Helix client`.

### Task 15: EventSub WebSocket client

Port the session handling of `C:\Users\Dimit\Documents\Github Repositories\Signal-Catchall\src\ingest\EventSubClient.ts`
(and its tests) to Kotlin coroutines, with the corrections in the API notes, section 4.

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/eventsub/EventSubClient.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/eventsub/EventSubParser.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/eventsub/EventSubSocket.kt` (interface + Ktor implementation)
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/AppGraph.kt`: `install(WebSockets)` on the HttpClient. Build
  the client, but do not start it yet.
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/eventsub/EventSubClientTest.kt`, `EventSubParserTest.kt`
  (fixtures in `src/jvmTest/resources/twitch/eventsub/`)

**Interfaces:**
- Produces:
  ```kotlin
  interface EventSubSocket { val incoming: ReceiveChannel<String>; suspend fun close() }
  fun interface EventSubConnector { suspend fun connect(url: String): EventSubSocket }
  class KtorEventSubConnector(http: HttpClient) : EventSubConnector
  sealed interface EventSubEvent {
      data class ChatMessage(val messageId: String, val chatterId: String, val chatterLogin: String, val chatterName: String,
                             val text: String, val badges: Set<String>, val sourceBroadcasterId: String?) : EventSubEvent
      data class Redemption(val id: String, val rewardId: String, val userId: String, val userLogin: String,
                            val userName: String, val userInput: String) : EventSubEvent
      data class Cheer(val messageId: String, val isAnonymous: Boolean, val userId: String?, val userLogin: String?,
                       val userName: String?, val message: String, val bits: Int) : EventSubEvent
      data object StreamOnline : EventSubEvent; data object StreamOffline : EventSubEvent
      data class RewardUpdated(val reward: Reward) : EventSubEvent; data class RewardRemoved(val rewardId: String) : EventSubEvent
  }
  sealed interface EventSubState { data object Stopped; data object Connecting; data object Connected;
      data class Disconnected(val sinceEpochMs: Long); data object LoginNeeded }
  class EventSubClient(
      connector: EventSubConnector, api: TwitchApi, broadcasterId: () -> String?, rewardId: () -> String?,
      onAuthorizationRevoked: () -> Unit, scope: CoroutineScope, clock: () -> Long,
      url: String = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30",
  ) {
      val events: SharedFlow<EventSubEvent>; val state: StateFlow<EventSubState>
      val freshSessions: SharedFlow<Unit>   // after a fresh session's subscriptions: resync over REST
      fun start(); fun stop(); fun rewardChanged()   // subscribe the reward types for the new reward id on the live session
  }
  ```
- **On a fresh `session_welcome`,** subscribe these, then emit `freshSessions`:
  - `channel.chat.message` v1 (`broadcaster_user_id`, `user_id` = the broadcaster);
  - `channel.cheer` v1;
  - `stream.online` v1 and `stream.offline` v1;
  - and when `rewardId()` is not null: `channel.channel_points_custom_reward_redemption.add`,
    `channel.channel_points_custom_reward.update` and `.remove`, each v1 with
    `{broadcaster_user_id, reward_id}`.
- **On `session_reconnect`:** connect to `reconnect_url` and keep the old socket until the new welcome arrives.
  Then close it, with no resubscribe and no `freshSessions`.
- **Keepalive:** no message within the welcome's `keepalive_timeout_seconds` + 5 s → reconnect.
- **Backoff:** 1 s doubling to 30 s on failed connects; reset on a welcome.
- **Subscribe answers:** `LoginNeeded` → `state = LoginNeeded`; stop reconnecting until `start()` is called
  again.
- **Revocation** with status `authorization_revoked` → `onAuthorizationRevoked()`.
- **De-duplication:** on `metadata.message_id`; keep the last 500 ids.
- **Parsing:** lowercase redemption statuses; chat `badges[].set_id`; `source_broadcaster_user_id`. Unknown
  types are ignored.

- [ ] **Step 1: Write `EventSubParserTest`.** One fixture per event type (fields as in the API notes): chat with
  `lead_moderator` and `founder` badges, shared-chat source, anonymous cheer, redemption, reward update and
  remove, keepalive, reconnect, revocation.
- [ ] **Step 2: Write `EventSubClientTest`** with a fake connector (channels you push JSON into) and a `MockEngine`
  `TwitchApi`:
  - `welcomeSubscribesEverythingWithinTheSession`;
  - `reconnectMessageMovesWithoutResubscribing` (Review Focus 5);
  - `keepaliveSilenceReconnectsAndResubscribes` (Review Focus 5; virtual time 35 s);
  - `duplicateMessageIdIsDeliveredOnce`;
  - `subscribe401StopsWithLoginNeeded`;
  - `revocationCallsAuthorizationRevoked`;
  - `backoffDoublesToThirtySeconds`;
  - `rewardSubscriptionsOnlyWithARewardId`.
- [ ] **Step 3: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.eventsub.*"`. Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 6: Commit** `feat: EventSub WebSocket client with reconnect, keepalive and de-duplication`.

### Task 16: Twitch session and the Twitch card (login, account, chat row)

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/TwitchSession.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/setup/TwitchCard.kt`, `ui/setup/TwitchSetupViewModel.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/setup/SpotifySetupScreen.kt`: the Connections screen now
  shows the Spotify card and the Twitch card.
- Modify: `AppGraph.kt`. Wire `TwitchAuth`, `TwitchApi`, `EventSubClient`, `TwitchSession`,
  `TwitchSetupViewModel`. `gate`'s `twitchConnected` = session logged in. `TwitchHealth` is fed to the
  connection chip.
- Modify: both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/TwitchSessionTest.kt`, `ui/setup/TwitchSetupViewModelTest.kt`,
  `ui/setup/TwitchCardTest.kt`, `AppGraphTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class TwitchSession(auth: TwitchAuth, api: TwitchApi, eventSub: EventSubClient, settings: SettingsStore,
                      scope: CoroutineScope, clock: () -> Long, validateEveryMs: Long = 3_600_000) {
      val connected: StateFlow<Boolean>      // logged in with an account
      val health: StateFlow<TwitchHealth>    // loginNeeded; disconnected = EventSub Disconnected for > 30 s; rewardRemoved from settings
      fun start(); fun stop()
  }
  sealed interface TwitchLoginState { data object Idle; data object Requesting; data class ShowingCode(val code: DeviceCode);
      data object Expired; data object Failed; data object Unreachable }
  class TwitchSetupViewModel(auth: TwitchAuth, api: TwitchApi, settings: SettingsStore, scope: CoroutineScope,
                             openBrowser: (String) -> Unit, copyToClipboard: (String) -> Unit) {
      val login: StateFlow<TwitchLoginState>; val settings: StateFlow<Settings>
      fun onLogin(); fun onCancelLogin(); fun onOpenBrowser(); fun onCopyCode(); fun onLogout()
      fun onChatEnabled(on: Boolean); fun onChatPermission(p: ChatPermission); fun onCommandName(edit: (ChatCommandNames) -> ChatCommandNames)
  }
  ```
- **`TwitchSession`:**
  - It starts EventSub while logged in with an account, and stops it when logged out or the login is dead.
  - It validates at start and every `validateEveryMs`.
  - EventSub's `onAuthorizationRevoked` → `auth.markLoginNeeded(…)`.
- **After a successful login** the VM calls `api.me()` and saves `TwitchAccount` (including `broadcasterType`)
  in settings. Logout clears the account; a dead login keeps it, to show "log in again as …".
- **Card** (choice A, mockup "Twitch op het Koppelingen-scherm" A + the device-code panel):
  - Logged out: "Log in with Twitch".
  - Logging in:
    - the `userCode` large, with Copy and "Open browser", which opens `verificationUri` (it carries the code);
    - an Expressive `LoadingIndicator` with "Waiting until you confirm…", and Cancel;
    - an expiry error when the code runs out.
  - Logged in:
    - the account name, the Affiliate/Partner tag and "Log out";
    - the chat row: `!sr` switch plus the permission dropdown (Everyone/Followers/Subscribers/VIPs/Moderators);
    - a collapsible Commands row with seven text fields. A name is stored without "!", trimmed and lower-case;
      blank or duplicate names are refused with a field error.
  - Dead login: "Your Twitch login has expired. Log in again." plus the button.

- [ ] **Step 1: Write the failing tests:**
  - `TwitchSessionTest`:
    - `startsEventSubOnlyWhileLoggedIn`;
    - `validatesHourly` (virtual time: 3 validations at 0, 60, 120 min);
    - `revokedAuthorizationMeansLoginNeeded`;
    - `disconnectedShowsOnlyAfter30s`.
  - `TwitchSetupViewModelTest`:
    - `loginShowsCodeThenSavesAccount`;
    - `expiredCodeShowsExpired`;
    - `logoutClearsAccount`;
    - `commandNamesAreNormalisedAndDuplicatesRefused`.
  - `TwitchCardTest`:
    - `loggedOutShowsLoginButton`;
    - `codeIsShownWithCopyAndOpen`;
    - `loggedInShowsAccountAndAffiliateTag`.
  - `AppGraphTest.twitchLoggedOutKeepsRequestsOpenWithoutTwitch`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement**, and wire `AppGraph.start()`/`stop()` (`session.start()`/`stop()`).
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: log in to Twitch from the Connections screen`.

### Task 17: Blacklist screen

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/blacklist/BlacklistScreen.kt`, `ui/blacklist/BlacklistViewModel.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/AppScaffold.kt` (`Destination.Blacklist` after Rules, block
  icon), `AppGraph.kt`, both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/blacklist/BlacklistViewModelTest.kt`, `BlacklistScreenTest.kt`

**Interfaces:**
- Consumes: `BlacklistStore`, `RequestIntake.block*` (so adding sweeps the queue), `SpotifyCatalog.searchTracks/track/searchArtists/artist`,
  `TwitchApi.userByLogin`, `TwitchSession.connected`.
- Produces: `class BlacklistViewModel(...)` with `tab`, `input`, `results` (tracks, artists or a Twitch user),
  `onInput`, `onAdd(result)`, `onRemove(key)`; `@Composable fun BlacklistScreen(vm: BlacklistViewModel)`.

- **Layout:** choice A. Segmented tabs **Songs · n / Artists · n / Viewers · n**, then the add field, then the
  list.
- **The add field adapts to the tab:**
  - songs: search or track link (300 ms debounce, like Home);
  - artists: search or artist link;
  - viewers: a Twitch name looked up with `userByLogin`. Without Twitch the field is disabled with "Connect Twitch
    to block viewers".
- **Rows:** connected rows with "blocked by you / by @mod · <date>" and an X.

- [ ] **Step 1: Write the failing tests:**
  - `songSearchAddsAndSweeps`: the intake's `blockTrack` is called;
  - `artistLinkResolvesArtist`;
  - `viewerNameResolvesToUserId`;
  - `unknownViewerShowsNotFound`;
  - `viewerTabDisabledWithoutTwitch`;
  - `BlacklistScreenTest.tabsShowCountsAndRowsShowWhoBlocked`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Blacklist screen`.

### Task 18: Chat requests and viewer commands

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/chat/ChatCommand.kt` (parsing and roles)
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/chat/ChatReplySender.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/chat/TwitchChat.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/chat/ChatReplies.kt` (texts via `getString`)
- Modify: `AppGraph.kt` (start `TwitchChat` with the session), both `strings.xml` (`chat_*`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/chat/ChatCommandTest.kt`, `TwitchChatTest.kt`, `ChatReplySenderTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface ChatCommand { data class Request(val input: String); data object Song; data object Queue; data object WrongSong
      data object Skip; data class Remove(val position: Int?); data object Blacklist }
  fun parseChatCommand(text: String, names: ChatCommandNames): ChatCommand?   // "!name" at the start, case-insensitive
  enum class ChatRole { Viewer, Subscriber, Vip, Moderator, Broadcaster }       // ordered
  fun roleOf(badges: Set<String>): ChatRole   // broadcaster; moderator|lead_moderator; vip; subscriber|founder
  class ChatReplySender(api: TwitchApi, scope: CoroutineScope, maxTries: Int = 3) {
      fun send(text: String, replyTo: String?)        // sequential; RateLimited → wait and retry; dropped after maxTries
      fun isOwnMessage(messageId: String): Boolean    // the last 100 sent ids
      fun start(); fun stop()
  }
  /** All chat texts in the app language. Only [outcome] is used by other tasks (Channel Points, Bits). */
  class ChatReplies {
      /** Null when nothing is said: ViewerBlocked, Ignored, Pending. [mention] = "@name " for replies that are not threaded. */
      suspend fun outcome(outcome: IntakeOutcome, mention: String?, refundedPoints: Boolean): String?
  }
  class TwitchChat(eventSub: EventSubClient, intake: RequestIntake, controller: PlaybackController, api: TwitchApi,
                   settings: SettingsStore, replies: ChatReplies, sender: ChatReplySender,
                   broadcasterId: () -> String?, scope: CoroutineScope, clock: () -> Long) { fun start(); fun stop() }
  ```
- **Which messages are ignored:** messages with `sourceBroadcasterId` set to another broadcaster (shared chat),
  the app's own messages (`sender.isOwnMessage`), and everything while `chatEnabled` is off.
- **`!sr` permission:**
  - The role must be at least the level: Followers = any role, plus a follower check; Subscribers ≥ Subscriber;
    VIPs ≥ Vip; Moderators ≥ Moderator.
  - The follower check (`api.follows`) runs only for the Followers level and a `Viewer` role. It is cached per
    user id for the session; a failed check counts as not following.
  - A refused viewer gets a short reply and no Rejected entry.
- **`!sr <input>`:** `IncomingRequest(id = messageId, input, linkOnly = false, Requester(chatterName,
  chatterLogin, chatterId), CHAT, null, replyTo = messageId)` → `intake.submit`.
  - Replies: accepted → "Added: Song – Artist at #4 (≈ 12 min)"; rejected → the reason's text; `ViewerBlocked`
    → no reply; `Ignored` cannot happen (not link-only).
  - Without input → a usage hint.
- **`!song`:** the current track and its requester, or "the streamer's own music"; nothing playing → "Nothing is
  playing".
- **`!queue`:** "Next: <handed over>; 1. … 2. … 3. …" (at most 5) plus "You are at #n" for the asker's queued
  requests. Cut to 500 characters at a whole item.
- **`!wrongsong`:** `controller.removeMatching(RemoveReason.WrongSong)` → the asker's newest queued request (by
  `twitchUserId`), then a reply with the song or "You have no song in the queue".
- **Mod commands** are parsed here but handled in Task 20; until then they are ignored.
- **Reply texts:** en + nl, short. Tests run in English and assert on the content (names, numbers), not on full
  sentences.

- [ ] **Step 1: Write `ChatCommandTest`:**
  - `parsesConfiguredNamesCaseInsensitively` (`"!SR never gonna"` → `Request("never gonna")`);
  - `ignoresTextThatOnlyContainsTheCommandLater`;
  - `removeTakesPosition`;
  - `rolesFromBadges` (`lead_moderator` → Moderator, `founder` → Subscriber).
- [ ] **Step 2: Write `TwitchChatTest`** with a fake EventSub flow, a `MockEngine` `TwitchApi` that records chat
  sends, and a real intake/controller on temp files:
  - `srAddsAndRepliesWithPositionAsReply` (the reply has `reply_parent_message_id`);
  - `srWhileClosedRepliesClosed`;
  - `blockedViewerGetsNoReply`;
  - `followersLevelChecksOnceAndCaches`;
  - `sharedChatFromOtherChannelIgnored`;
  - `ownRepliesAreIgnored`;
  - `songAndQueueReplies`;
  - `wrongSongRemovesNewestOwnQueuedRequestOnly`.
- [ ] **Step 3: Write `ChatReplySenderTest`:** `rateLimitedWaitsAndRetries`, `droppedAfterThreeTries`, `sendsInOrder`.
- [ ] **Step 4: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.chat.*"`. Expected: FAIL.
- [ ] **Step 5: Implement.**
- [ ] **Step 6: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 7: Commit** `feat: chat requests and viewer commands with replies`.

### Task 19: Mid-way live test (with the user)

No code unless the test finds something; fixes go through superpowers:systematic-debugging, with a failing test
first.

- [ ] **Step 1:** `./gradlew jvmTest` green. Start the app from the worktree with `./gradlew run`; the user runs
  it on their own machine.
- [ ] **Step 2: Walk the user through it, in Dutch:**
  1. Twitch login with the device code.
  2. The Twitch card shows the account and the Affiliate tag.
  3. The title-bar toggle follows the live status, and opening by hand works.
  4. `!sr` with a link and with search text, from the user and from a second account → added, with a reply.
  5. A rule hit (e.g. a track longer than 7:00) → reply and a Rejected row; "Add anyway" works.
  6. `!song`, `!queue`, `!wrongsong`.
  7. Block from Home and from the Blacklist screen; the queue is swept.
  8. The Rules screen changes apply to the next request.
- [ ] **Step 3:** Record the findings in the shared notes folder. Fix them before Milestone 2, or agree with the
  user to defer them.

## Milestone 2: mod commands, Channel Points, Bits and the stream lifecycle

### Task 20: Mod commands

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/chat/TwitchChat.kt`, both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/chat/TwitchChatModTest.kt`

**Interfaces:**
- Consumes: `controller.skip()`, `controller.removeMatching(RemoveReason.ModRemove)`, `intake.blockTrack(track, by = modLogin)`.

- Only `ChatRole.Moderator` or `Broadcaster`; others are ignored silently.
- **`!skip`** → "Skipped: Song".
- **`!remove n`** removes queue position n, numbered as in `!queue` (1 = first in Requestify's queue; the
  handed-over one cannot be removed). Missing or invalid n → "No position n".
- **`!blacklist`** blocks the playing track (by the mod), skips it, and replies. With nothing playing → "Nothing
  is playing".

- [ ] **Step 1: Write the failing tests:**
  - `viewerCannotSkip`;
  - `modSkipSkips`;
  - `leadModeratorCanRemovePositionTwo`;
  - `removeInvalidPositionReplies`;
  - `blacklistBlocksCurrentTrackByModAndSkips` (the blacklist entry's `by` = the mod's login).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.chat.TwitchChatModTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: moderators can skip, remove and blacklist from chat`.

### Task 21: Channel Points: redemptions, outbox and catch-up

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/points/RedemptionOutbox.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/points/ChannelPointsSource.kt`
- Modify: `core/storage/AppPaths.kt` (`redemptionsFile` = `redemptions.json`), `AppGraph.kt`, both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/points/RedemptionOutboxTest.kt`, `ChannelPointsSourceTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class OutboxEntry(val rewardId: String, val redemptionId: String, val status: RedemptionStatus, val tries: Int = 0)
  class RedemptionOutbox(file: JsonFileStore<List<OutboxEntry>>, api: TwitchApi, scope: CoroutineScope,
                         backoffMs: List<Long> = listOf(5_000, 15_000, 60_000, 300_000)) {   // then every 5 min
      val entries: StateFlow<List<OutboxEntry>>
      fun enqueue(rewardId: String, redemptionId: String, status: RedemptionStatus)   // persisted before any call
      fun start(); fun stop()
  }
  class ChannelPointsSource(eventSub: EventSubClient, intake: RequestIntake, controller: PlaybackController, api: TwitchApi,
                            outbox: RedemptionOutbox, settings: SettingsStore, replies: ChatReplies, sender: ChatReplySender,
                            scope: CoroutineScope, clock: () -> Long) { fun start(); fun stop(); suspend fun catchUp() }
  ```
- **Outbox:**
  - It sends in order. `Ok` or `NotFound` → drop the entry. `Forbidden` (not our reward) → drop. `LoginNeeded` /
    `LoggedOut` → wait for the next `start()`. Anything else → backoff and retry.
  - A second entry for the same redemption replaces the first one if it has not been sent yet.
- **Source:**
  - It listens only when `channelPoints.enabled` and `event.rewardId == settings.rewardId`.
  - Redemption → `IncomingRequest(id = redemptionId, input = userInput, linkOnly = false,
    Requester(userName, userLogin, userId), CHANNEL_POINTS, null, sourceRef = ChannelPointsRef(rewardId,
    redemptionId, managed).format())`.
  - Rejected and managed → `outbox.enqueue(CANCELED)`.
  - Reply: a plain chat message starting with "@userName" (no reply id), saying added or the reason. The reason
    text adds "points refunded" only when managed. No reply for `ViewerBlocked`.
  - `RequestEvent.Started` with a managed ref → FULFILLED. `RequestEvent.Removed` with a managed ref → CANCELED.
- **Catch-up** (`catchUp()`, on `start()` and on every `eventSub.freshSessions`; managed rewards only): read
  `unfulfilledRedemptions(rewardId)`, then per redemption:
  - its ref is queued, handed over or in the outbox → leave it;
  - playing, or in history → FULFILLED;
  - otherwise → treat it as a new redemption (rules apply; refunded when closed).

- [ ] **Step 1: Write `RedemptionOutboxTest`:**
  - `failedRefundIsRetriedAfterRestart` (Review Focus 3): `Failed` → still in the file; a new outbox on the same
    file → `start()` → sent and dropped;
  - `notFoundDropsEntry`;
  - `loginNeededWaitsWithoutDropping`;
  - `backoffSchedule`.
- [ ] **Step 2: Write `ChannelPointsSourceTest`:**
  - `redemptionQueuedAndUnfulfilledUntilItPlays` (no outbox entry until `Started`, then FULFILLED);
  - `rejectedRedemptionIsRefundedAndReplyMentionsRefund`;
  - `removedBeforePlayingIsRefunded` (X, `!wrongsong`, `StreamEnded`);
  - `handedOverThatReturnsToHeadIsStillRefundable`;
  - `catchUpSettlesPlayedQueuedAndUnknown`;
  - `linkedForeignRewardNeverTouchesOutboxAndNeverClaimsRefund`;
  - `otherRewardsAreIgnored`.
- [ ] **Step 3: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.points.*"`. Expected: FAIL.
- [ ] **Step 4: Implement**, and wire it in `AppGraph` (start/stop with the session).
- [ ] **Step 5: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 6: Commit** `feat: Channel Points redemptions are fulfilled or refunded, also across restarts`.

### Task 22: Channel Points: the reward (create, scan, copy, link, edit, pause)

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/points/RewardManager.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/setup/TwitchCard.kt`, `TwitchSetupViewModel.kt`, `AppGraph.kt`,
  both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/points/RewardManagerTest.kt`, `ui/setup/TwitchCardTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ScannedReward(val reward: Reward, val manageable: Boolean)
  sealed interface RewardActionResult { data object Ok; data object DuplicateTitle; data object NotAffiliate; data object Failed }
  class RewardManager(api: TwitchApi, settings: SettingsStore, eventSub: EventSubClient, requestsOpen: StateFlow<Boolean>,
                      language: () -> AppLanguage, scope: CoroutineScope) {
      suspend fun scan(): List<ScannedReward>?                 // null when Twitch could not answer
      suspend fun createNew(): RewardActionResult              // defaults: "Song request"/"Nummerverzoek", 500, prompt "Spotify link or song name"/"Spotify-link of nummernaam"
      suspend fun copy(from: Reward): RewardActionResult        // same title/cost/prompt, as Requestify's own
      fun link(reward: ScannedReward)                           // manageable → managed = true; foreign → managed = false
      suspend fun edit(title: String?, cost: Int?, prompt: String?): RewardActionResult   // managed only
      suspend fun setEnabled(on: Boolean)                       // managed: is_enabled; never deletes
      fun start(); fun stop()                                   // follows requestsOpen → is_paused (managed + enabled)
  }
  ```
- **Linking:** setting a reward saves `rewardId`, `managed`, `title`, `cost`, `prompt`, `rewardRemoved = false`,
  then calls `eventSub.rewardChanged()`.
- **Twitch's own reward events:**
  - `RewardUpdated` (our id) → adopt the title, cost and prompt into settings. When `is_paused` differs from
    "requests closed" (managed), write it back.
  - `RewardRemoved` (our id) → `rewardRemoved = true`.
- **Card, Channel Points row:**
  - The switch.
  - Without a reward: a "Choose reward" sheet, with "Create new reward" first, then the scan in two groups,
    "Made by Requestify" and "Made elsewhere". Each foreign one has "Copy as Requestify reward" and "Link
    directly".
  - "Link directly" opens a warning dialog that lists the spec's five limits.
  - Title, cost and prompt fields (read-only for a foreign reward).
  - The status line: "Paused: stream is offline" / "Linked without management: Requestify cannot refund
    points" / "The reward was removed on Twitch" with "Create again".
  - For non-Affiliates, the row is disabled with an explanation. The Bits row follows in Task 23.
- **Errors:** a duplicate title → a field error; non-affiliate (`Forbidden`) → `NotAffiliate`.

- [ ] **Step 1: Write the failing tests:**
  - `createNewUsesLanguageDefaultsAndLinksManaged`;
  - `duplicateTitleIsReported`;
  - `scanSplitsManageable`;
  - `relinkOwnRewardCreatesNoDuplicate`;
  - `copyCreatesOwnWithSameSettings`;
  - `linkForeignIsUnmanagedAndNeverPatched`;
  - `pausesWhenRequestsCloseAndUnpausesWhenOpen`;
  - `switchingOffDisablesNeverDeletes` (no `DELETE` request recorded);
  - `rewardUpdatedOnTwitchIsAdopted`;
  - `rewardRemovedShowsCreateAgain`.

  Card tests:
  - `foreignLinkShowsWarningFirst`;
  - `nonAffiliateRowDisabled`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.** The connection chip's `RewardRemoved` comes from `settings.twitch.channelPoints.rewardRemoved`.
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Requestify's own Channel Points reward, or one chosen from the channel`.

### Task 23: Bits

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/bits/BitsSource.kt`
- Modify: `ui/setup/TwitchCard.kt` (Bits row: switch + minimum stepper 1–10 000), `AppGraph.kt`, both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/bits/BitsSourceTest.kt`

**Interfaces:**
- Produces: `class BitsSource(eventSub, intake, settings, replies, sender, scope, clock) { fun start(); fun stop() }`.
  It also collects `intake.pendingResolved` for Bits requests and replies once they settle.

- **A `Cheer`** while `bitsEnabled` → `IncomingRequest(id = messageId, input = message, linkOnly = true, …,
  BITS, Payment.Bits(bits))`.
  - Anonymous: requester = the localised "Anonymous" with no ids; no name in the reply.
  - No Spotify link → ignored.
  - With a link but `bits < bitsMinimum` → `intake.recordRejection(…, BelowMinimum(min))` and a reply.
  - Otherwise → `intake.submit`, and reply with the outcome (Pending → no reply yet; the reply comes with
    `pendingResolved`).

- [ ] **Step 1: Write the failing tests:**
  - `cheerWithTrackLinkIsPaidRequest`;
  - `cheerWithoutLinkIgnored`;
  - `belowMinimumIsRejectedPaidAndListed`;
  - `albumLinkIsRejectedPaid`;
  - `anonymousCheerHasNoNameInReply`;
  - `spotifyDownCheerWaitsThenRepliesWhenQueued`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.twitch.bits.BitsSourceTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Bits cheers with a Spotify link become paid requests`.

### Task 24: Stream lifecycle wiring

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/twitch/StreamWatcher.kt`
- Modify: `AppGraph.kt` (start/stop; graceful exit), `Main.kt` (exit waits for `graph.stop()`'s pause, at most 3 s)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/twitch/StreamWatcherTest.kt`, `AppGraphTest.kt`

**Interfaces:**
- Produces: `class StreamWatcher(eventSub: EventSubClient, api: TwitchApi, gate: RequestsGate, scope: CoroutineScope) { fun start(); fun stop() }`,
  and `suspend fun AppGraph.stopGracefully()`: pause Requestify's own reward (if managed and enabled), then stop
  everything, within 3 s.

- **Live status:** `StreamOnline`/`StreamOffline` → `gate.onLive(true/false)`. On `start()` and on every
  `freshSessions` → `api.isLive()` → `gate.onLive(it)` (`Failed` leaves the gate as it is).
- **End of stream:** `gate.onStreamEnded` (Task 9) removes the free viewer requests; their `Removed(StreamEnded)`
  events reach Channel Points (Task 21), which refunds them.

- [ ] **Step 1: Write the failing tests:**
  - `onlineAndOfflineEventsDriveTheGate`;
  - `startupAndFreshSessionReadLiveStatus`;
  - `failedLiveCheckChangesNothing`;
  - `AppGraphTest.streamEndRefundsFreeChannelPointsRequestsAfterGrace` (`MockEngine`: offline event, +10 min →
    the PATCH CANCELED for the queued CP request, the paid one stays);
  - `AppGraphTest.gracefulExitPausesOwnReward`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: requests follow the stream, and the reward pauses when the app closes`.

### Task 25: Final live test (with the user)

- [ ] **Step 1:** `./gradlew jvmTest` green; the final whole-branch review is done and its findings handled.
- [ ] **Step 2: Walk the user through it**, in Dutch, on their channel:
  1. Create the reward, or scan and choose one, including a "Link directly" warning.
  2. Redeem with a link and with text → queued; the redemption stays unfulfilled until it plays, then is
     fulfilled.
  3. Redeem a blocked song → refunded, with a reply.
  4. `!wrongsong` on a Channel Points request → refunded.
  5. A Bits cheer with a link, and one below the minimum → Rejected with the badge.
  6. Mod commands from a mod account.
  7. Go offline → closed and the reward paused. Back within 10 min → everything intact. Offline 10 min → free
     requests cleared and refunded, paid ones kept.
  8. Close the app → the reward is paused. Start it → catch-up.
- [ ] **Step 3:** Fix the findings (systematic debugging, test first). Then finish the branch with
  superpowers:finishing-a-development-branch.
