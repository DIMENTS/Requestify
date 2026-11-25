# Spotify call budget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Requestify asks Spotify far less, waits when Spotify says so, and shows until when.

**Architecture:** `SpotifyCallGate` sits in `SpotifyApi` and answers for every call. `PollPace` (pure, next to
`UpcomingBook`/`DeviceBook`) decides when `PlaybackController` polls next. `AppGraph` feeds it live and window state.

**Tech Stack:** Kotlin, coroutines, Ktor MockEngine tests, Compose Desktop.

**Spec:** [docs/superpowers/specs/2026-11-25-spotify-call-budget-design.md](../specs/2026-11-25-spotify-call-budget-design.md)

## Global Constraints

- Values exactly as the spec's tables. Saved files stay readable by 0.3.3. Texts in `values` and `values-nl`.
- Existing controller tests keep testing state with the old steady cadence (`STEADY_POLLS`); cadence has its own tests.

## Review Focus

1. A wait of hours must not block other endpoints, saves, or a new login.
2. A request that arrives in the slow state must still be handed over in its window.
3. Spotify reporting a finished song forever must not become a 1 s loop.
4. Clock set back or PC sleep during a wait or poll delay.
5. Starting hidden (autostart) must still make a device active and react when the stream goes live.

### Task 1: Gate in SpotifyApi
Create `core/spotify/SpotifyCallGate.kt`; modify `SpotifyApi.kt`, `SpotifyAuth.kt`. Tests: `SpotifyCallGateTest`, `SpotifyApiTest`, `SpotifyAuthTest`.
Produces: `SpotifyResult.RateLimited(retryAfterMs: Long, quota: Boolean = false, detail: String? = null)`,
`SpotifyApi(..., gate: SpotifyCallGate)`, `SpotifyCallGate(clock)`: `heldFor(endpoint): RateLimited?`, `sent(endpoint)`,
`refused(endpoint, retryAfterMs: Long?, quota: Boolean): RateLimited`, `taken(endpoint)`, `reset()`.
- [ ] tests red, implement, tests green

### Task 2: PollPace
Create `core/playback/PollPace.kt`; modify `PlaybackConfig`, `DeviceBook`, `UpcomingBook`, `LastPlayedBook`. Test: `PollPaceTest`.
Produces: `PollPace.next(Polled): Long`, `failed(quiet): Long`, `commandSent(trackId)`, `lookAgain()`.
- [ ] tests red, implement, tests green

### Task 3: Controller uses both
`PlaybackController(..., engaged: StateFlow<Boolean> = MutableStateFlow(true))`, `lookAgain()`; tests `PlaybackControllerPaceTest` incl. calls per hour.
- [ ] tests red, implement, tests green

### Task 4: Wiring
`AppGraph` (gate, engaged, window signals), `Main` (shown, minimized, focus). Test: `AppGraphTest`.
- [ ] tests red, implement, tests green

### Task 5: Chip and Log
`SpotifyHealth.RateLimited(untilEpochMs, quota, detail)`, chip text with time, `ConnectionSnapshot.LimitReached`, `LogConnectionState.LimitReached`.
- [ ] tests red, implement, tests green

### Task 6: Verify
Full `gradlew jvmTest`, the measured calls per hour, one fresh reviewer.
