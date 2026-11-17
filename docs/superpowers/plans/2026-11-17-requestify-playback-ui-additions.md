# Requestify Playback UI Additions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a previous-track button, show Spotify's own upcoming tracks under the requests, show the last played song and its state at startup, and give the Now Playing card a fixed-slot layout that never shifts.

**Architecture:** Three new Spotify player calls (previous, full queue, recently played) feed the existing `PlaybackController`, which gains an `upcoming` list, a persisted last-played snapshot and a "needs extra permission" flag. The UI reads those flows: the Now Playing card becomes a fixed-slot skeleton used by every state, and the queue pane appends a read-only "Then in Spotify" section.

**Tech Stack:** unchanged — Kotlin 2.4.21, Compose Multiplatform 1.12.2 (JBR 21), material3 1.12.0-alpha03, MaterialKolor 5.0.1, Ktor 3.6.1, kotlinx-coroutines/serialization, JUnit 4 + kotlin-test, coroutines-test, ktor-client-mock, Compose UI test (`androidx.compose.ui.test.v2.runComposeUiTest`).

**Spec:** `docs/superpowers/specs/2026-11-17-requestify-playback-ui-additions-design.md` (builds on `2026-11-16-requestify-design.md`)

## Global Constraints

- Branch `feat/plan-1-core-spotify`; build/test with `./gradlew jvmTest` (Gradle provisions JBR 21).
- Every user-facing string in Compose resources, both `values/` (en) and `values-nl/` (nl). Never show raw `SpotifyResult.Failed.message` or other diagnostic strings.
- Spacing only via `ui/theme/Spacing.kt` tokens in touched screens.
- Locks in `PlaybackController`: `spotifyMutex` serializes Spotify sequences; state + every save under `stateMutex` (via `withState {}`), never held across a network call.
- Persisted JSON stays forward compatible: new fields have defaults; `AppJson` (`ignoreUnknownKeys`, `encodeDefaults`).
- Spotify search limit ≤ 10 (unchanged). Required scopes become `user-read-playback-state user-modify-playback-state user-read-currently-playing user-read-private user-read-recently-played`.
- Commit trailer names the model that wrote the commit.

## Review Focus

1. **Spotify repeats tracks in its queue** (short playlist/album fills its 20-item queue with repeats) → "Then in Spotify" shows distinct tracks only, at most 15. → Task 2 `upcomingIsDistinctCappedAndSkipsHandedOver`.
2. **Existing login without the new permission** → recently-played answers 403 "Insufficient client scope": no error status, no call on every poll, local last-played still shown, Connections asks to log in again. → Task 2 `missingPermissionFallsBackToLocalAndStopsAsking`.
3. **App starts while offline or Spotify is closed** → the last song shows at once as LAST PLAYED instead of "Nothing is playing". → Task 2 `startupShowsLocalLastPlayedBeforeAnyPoll`.
4. **Previous pressed while a request plays** → that request moves to history (not back into the queue), the queue and a handed-over request stay intact. → Task 2 `previousWhileARequestPlaysMovesItToHistory`.
5. **Long or wrapped titles, no requester, idle and last-played states** → progress bar and controls keep identical positions in wide and narrow layouts. → Task 3 `controlsKeepTheirPlaceInEveryState`.

---

### Task 1: Spotify calls — previous, full queue, recently played

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/SpotifyApi.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/auth/LoopbackReceiver.kt` (`SPOTIFY_SCOPES`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/spotify/SpotifyApiTest.kt` (+ fixture `src/jvmTest/resources/spotify/recently-played.json`), adjust the scope assertion in `SpotifyAuthTest`
- Modify: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/FakeSpotifyPlayer.kt` (implement the new methods, scriptable, recording calls)

**Interfaces:**
- Produces:

```kotlin
// SpotifyResult gains:
data object MissingPermission : SpotifyResult<Nothing>   // 403 whose error.message mentions "scope" (case-insensitive) and reason != PREMIUM_REQUIRED
data class RecentTrack(val track: Track, val playedAtEpochMs: Long)
// SpotifyPlayer gains:
suspend fun skipPrevious(): SpotifyResult<Unit>                 // POST /me/player/previous
suspend fun userQueue(): SpotifyResult<List<Track>>             // GET /me/player/queue → mappable tracks in order (unusable items dropped, as userQueueTrackIds does)
suspend fun recentlyPlayed(): SpotifyResult<RecentTrack?>       // GET /me/player/recently-played?limit=1 → Ok(null) when empty; played_at (ISO-8601) → epoch ms
val SPOTIFY_SCOPES // + "user-read-recently-played" (last)
```

- [ ] **Step 1: Write the failing tests:** `previousPostsToPlayerPrevious` (method POST, path `/v1/me/player/previous`, 204 → Ok), `userQueueMapsTracksAndDropsUnusable` (fixture `queue.json` + a local-file item → only usable tracks, order kept), `recentlyPlayedMapsTrackAndPlayedAt` (`played_at` "2026-11-17T09:30:00.000Z" → `playedAtEpochMs == 1794907800000`), `recentlyPlayedEmptyIsOkNull`, `insufficientScopeIsMissingPermission` (403 `{"error":{"status":403,"message":"Insufficient client scope"}}` → `MissingPermission`), `premiumRequiredStaysPremiumRequired`, `authorizeUrlIncludesRecentlyPlayedScope`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*SpotifyApiTest" --tests "*SpotifyAuthTest"`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement** in `SpotifyApi.kt` via the existing request helper; extend `FakeSpotifyPlayer` with `previousResult`, `queueTracks`, `recentResult` scripts.
- [ ] **Step 4: Run** the same command, then `./gradlew jvmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat: Spotify previous, full queue and recently played calls`.

---

### Task 2: Controller — previous, upcoming, last played, permission flag

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/PlaybackController.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/core/queue/QueueStore.kt` (`PersistedPlayback.lastPlayed`)
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/PlaybackControllerTest.kt`, `src/jvmTest/kotlin/nl/diments/requestify/core/queue/QueueStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 (`skipPrevious`, `userQueue`, `recentlyPlayed`, `RecentTrack`, `MissingPermission`).
- Produces:

```kotlin
enum class NowPlayingKind { Live, LastPlayed }
data class NowPlaying(..., val kind: NowPlayingKind = NowPlayingKind.Live)   // existing fields unchanged
@Serializable data class LastPlayed(val track: Track, val progressMs: Long, val playedAtEpochMs: Long)
// PersistedPlayback gains: val lastPlayed: LastPlayed? = null
data class PlaybackConfig(..., val upcomingRefreshMs: Long = 30_000, val recentlyPlayedRefreshMs: Long = 60_000)
// PlaybackController gains:
val upcoming: StateFlow<List<Track>>            // ≤ 15 (UPCOMING_LIMIT), distinct by id, handed-over track excluded; empty when nothing is active
val needsExtraPermission: StateFlow<Boolean>    // true after recentlyPlayed() → MissingPermission
suspend fun previous()                          // under spotifyMutex: skipPrevious(), then wake
fun onLoginChanged()                            // clears the permission flag and lets recentlyPlayed be asked again
```

Behaviour (decided here; the spec leaves the timing to the builder):
- **Upcoming:** refresh (outside the state lock, then apply) when the track changed, after a hand-over, after `skip()`/`previous()`, and when `upcomingRefreshMs` has passed since the last refresh. Map: `userQueue()` → drop the first item whose id == handed-over track id → `distinctBy { id }` → `take(UPCOMING_LIMIT)` with `const val UPCOMING_LIMIT = 15`. A non-Ok answer keeps the last list. Nothing active (`currentlyPlaying` Ok(null)) → empty list.
- **Last played, local:** keep the latest live `NowPlaying` in memory; persist `lastPlayed = LastPlayed(track, positionAt(now), now)` when the track changes, when play/pause state changes, and when playback becomes inactive (cp == null) — never on every poll. After `load()`, if a `lastPlayed` exists, publish `NowPlaying(track, progressMs, isPlaying = false, request = null, sampledAtEpochMs = clock(), kind = LastPlayed)` immediately (before the first poll).
- **Last played, Spotify:** while `currentlyPlaying` is Ok(null): on the first such tick and then at most every `recentlyPlayedRefreshMs`, call `recentlyPlayed()` (unless the permission flag is set). Show the newer of local `lastPlayed` and the Spotify `RecentTrack` (by `playedAtEpochMs`; Spotify's has progress 0) as `kind = LastPlayed`, `isPlaying = false`. `MissingPermission` → `needsExtraPermission = true`, stop asking until `onLoginChanged()`; health unaffected. Any Ok → flag false.
- **Previous:** a request that was playing settles into history as on any track change; never re-queued.

- [ ] **Step 1: Write the failing tests** (virtual time, real `QueueStore` in a temp dir, `FakeSpotifyPlayer`):
  - `previousCallsSpotifyAndRefreshesUpcoming` (calls `skipPrevious` once, then a `userQueue` refresh).
  - `previousWhileARequestPlaysMovesItToHistory` (R1 playing, previous → Spotify reports track P → `history.first().request == R1`, queue unchanged, handed-over unchanged).
  - `upcomingIsDistinctCappedAndSkipsHandedOver` (queue `[R2, T1, T2, T1, T3 … T20]`, R2 handed over → `upcoming` ids `[T1 … T15]`: distinct, 15 long, R2 absent).
  - `upcomingRefreshesOnTrackChangeAndEvery30s` (call times `[0, change, +30_000]`; no call on ordinary 3 s polls in between).
  - `failedUpcomingRefreshKeepsTheLastList`; `nothingActiveClearsUpcoming`.
  - `startupShowsLocalLastPlayedBeforeAnyPoll` (store has `lastPlayed` → `nowPlaying` has `kind == LastPlayed` before the fake is first polled).
  - `lastPlayedIsPersistedOnTrackChangeAndPauseOnly` (count saves over 10 polls of the same playing track == 0 extra).
  - `newerSpotifyRecentWinsOverLocal` / `olderSpotifyRecentLosesToLocal`.
  - `missingPermissionFallsBackToLocalAndStopsAsking` (MissingPermission once → flag true, local shown, no further `recentlyPlayed` calls over 5 minutes; `onLoginChanged()` → asked again on the next idle tick).
  - `QueueStoreTest.lastPlayedRoundTripsAndOldFilesStillLoad` (a v1 queue.json without `lastPlayed` decodes with null).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*PlaybackControllerTest" --tests "*QueueStoreTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat: previous track, Spotify's upcoming tracks and the last played song`.

---

### Task 3: Now Playing card — fixed slots, previous button, state label

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/NowPlayingPane.kt`, `HomeViewModel.kt` (`onPrevious()`), `HomeScreen.kt` (wire it)
- Modify: both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/home/NowPlayingPaneTest.kt`, `HomeScreenTest.kt`, `HomeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 (`NowPlaying.kind`, `PlaybackController.previous()`).
- Produces: `HomeViewModel.onPrevious()`; `NowPlayingPane(nowPlaying, onPrevious, onTogglePause, onSkip, modifier, compact, clock)`; strings `now_playing_previous` ("Previous" / "Vorige"), `now_playing_label_paused` ("PAUSED" / "GEPAUZEERD"), `now_playing_label_last_played` ("LAST PLAYED" / "LAATST GESPEELD").

Layout rules (from the spec): slots always reserved — art, label, title (`minLines = 2`, `maxLines = 2`, ellipsis), artist (1 line), requester/source row (fixed height, empty when none), progress, controls (**Previous · Play/Pause (primary, wide) · Next** button group). Idle (`nowPlaying == null`) uses the same skeleton: placeholder art, label `now_playing_label`, idle title in the title slot, idle body across the artist + requester rows (≤ 2 lines), progress empty, controls disabled. Label: `LastPlayed` → last-played label; else playing → `now_playing_label`; else paused label. The existing scroll-when-too-short behaviour stays.

- [ ] **Step 1: Write the failing tests:**
  - `controlsKeepTheirPlaceInEveryState` — for wide (320×640 dp card) and narrow (compact, 700 dp wide): render (a) playing, 2-line title + BITS requester, (b) own music, 1-line title, (c) paused, (d) last played, (e) idle; assert the progress bar's and the three buttons' bounds are identical (±0.5 dp) across all five.
  - `buttonsArePreviousPlayNextInOrder` (x order + content descriptions "Previous", "Pause", "Skip").
  - `labelFollowsTheState` (NOW PLAYING / PAUSED / LAST PLAYED, English locale).
  - `idleDisablesControls`.
  - `HomeViewModelTest.previousDelegatesToTheController`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.ui.home.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then the full suite. Expected: PASS; existing bounds tests (band under controls, one start edge, scroll-to-controls) still green.
- [ ] **Step 5: Commit** `feat: Now Playing keeps a fixed layout and gets a previous button`.

---

### Task 4: "Then in Spotify" section and the extra-permission note

**Files:**
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/QueuePane.kt`, `HomeViewModel.kt` (`HomeUiState.upcoming`)
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/ui/setup/SpotifySetupViewModel.kt`, `SpotifySetupScreen.kt`
- Modify: both `strings.xml`
- Test: `QueuePaneTest.kt`, `HomeViewModelTest.kt`, `SpotifySetupViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 (`upcoming`, `needsExtraPermission`, `onLoginChanged()`).
- Produces: `HomeUiState.upcoming: List<Track>`; `SpotifySetupState.needsExtraPermission: Boolean`; strings `queue_then_in_spotify` ("Then in Spotify" / "Daarna in Spotify"), `queue_your_music` ("Your music" / "Je muziek"), `setup_extra_permission` ("Log in again to show your last played song." / "Log opnieuw in om je laatst gespeelde nummer te tonen.").

Rules (merged list, spec §2): on the Queue tab, one list in playing order — "Up next in Spotify" row → request rows → a subtle divider with the caption `queue_then_in_spotify` → up to 15 Spotify rows. Spotify rows use **the same row composable/shape** as request rows (connected list continues across the divider), show the source chip `queue_your_music` ("Your music" / "Je muziek") in the chip slot, and have no drag handle (keep its space so columns line up) and no remove button; they are not part of the reorderable set (drag targets only among requests). Divider + Spotify rows hidden when `upcoming` is empty. The total duration in the header stays the requests' total. Connections: when logged in and `needsExtraPermission`, show the note with the existing log-in action (so the streamer can log in again without logging out); after a successful login the VM calls `controller.onLoginChanged()`.

- [ ] **Step 1: Write the failing tests:** `mergedListShowsRequestsThenSpotifyInOrder` (2 requests + 3 upcoming → titles appear top-to-bottom in that order, divider caption between them, the 3 Spotify rows show "Your music" and no remove button), `spotifyRowsAlignWithRequestRows` (title start x and duration end x equal for a request row and a Spotify row), `thenInSpotifyHiddenWhenEmpty`, `upcomingRowsAreNotDraggable` (drag on an upcoming row doesn't call `onMove`), `HomeViewModelTest.upcomingFlowsIntoState`, `SpotifySetupViewModelTest.extraPermissionNoteAndReloginClearsIt`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.ui.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat: show Spotify's upcoming tracks and ask once for the new permission`.
