# Requestify Plan 1: Core App + Spotify Playback

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Windows desktop app where the streamer connects their own Spotify developer app,
adds tracks to a Requestify-owned queue (search or link), and Requestify plays them through
Spotify. The app has a Material 3 Expressive Home screen that takes its colours from the album
art.

**Architecture:** Kotlin Multiplatform project with one JVM (desktop) target and Compose
Multiplatform UI. Pure domain code (queue, link parsing, theme seed selection) sits apart from I/O
(Ktor-based Spotify client, JSON file stores, DPAPI secret store). A `PlaybackController`
coroutine owns the queue. It hands the next request to Spotify shortly before the current track
ends, and reconciles state from Spotify's "currently playing". Wiring is manual constructor
injection in `AppGraph`, with no DI framework.

**Tech Stack:** Kotlin 2.4.21 · Compose Multiplatform 1.12.2 (desktop) ·
`org.jetbrains.compose.material3:material3` 1.9.0 (Expressive APIs, experimental) ·
MaterialKolor 5.0.1 · Ktor client 3.6.1 (CIO, content-negotiation, mock) · kotlinx-coroutines
1.11.0 · kotlinx-serialization-json 1.13.0 · Coil 3.6.3 (+ coil-network-ktor3) ·
sh.calvin.reorderable 3.1.0 · JNA platform 5.19.1 · lifecycle-viewmodel-compose 2.11.0 ·
JUnit/kotlin-test, kotlinx-coroutines-test, ktor-client-mock, Compose desktop UI test.

**Spec:** `docs/superpowers/specs/2026-11-16-requestify-design.md`

## Roadmap (this plan is 1 of 4)

The spec covers several subsystems. Each plan ships working, testable software:

1. **Core + Spotify** (this plan): queue, playback hand-over, Home UI, theming, Spotify setup.
2. **Rules + Twitch:** rules engine, Rejected tab, Blacklist/Rules screens, Twitch device login,
   EventSub (chat `!sr`/viewer and mod commands, Channel Points lifecycle, Bits), chat replies,
   live status lifecycle (auto open/close, end-of-stream clear), Requests open/closed switch.
3. **Donations:** StreamElements (Astro) and DonoLink sources, minimums, missed-event catch-up
   and gap logging.
4. **Reliability + release:** tray, start with Windows, single instance, Log screen, Windows
   notifications (e.g. DonoLink key expiry), full first-run wizard, installer, auto-update, code
   signing.

Plan 1 deliberately leaves out: rules, the Rejected tab, the blacklist button, the open/closed
switch (controller takes `requestsOpen` as an input, always `true` here), tray and autostart.

## Global Constraints

- Windows 10/11 x64 only. JDK toolchain 17 (Temurin 17 is installed at `JAVA_HOME`).
- Gradle wrapper copied from `../CastIRL` (Gradle 8.13). Build from the repo root.
- Package root `nl.diments.requestify`. App data directory `%APPDATA%\Requestify`.
- **No secrets in source.** The Spotify Client ID is the streamer's own (stored in settings).
  Tokens are stored only through `SecretStore` (Windows DPAPI), never in plain text.
- Spotify auth: Authorization Code + PKCE (S256), redirect URI exactly
  `http://127.0.0.1:43821/callback`, scopes `user-read-playback-state user-modify-playback-state
  user-read-currently-playing user-read-private`.
- Spotify search `limit` is never above 10 (dev-mode cap).
- Every user-facing string lives in Compose resources: `src/commonMain/composeResources/values/`
  (English) and `values-nl/` (Dutch). No hard-coded UI text.
- Source chip colours are fixed per source and never follow the theme.
- Theme: default colour comes from the album art for the whole app. A custom colour themes only
  the app chrome, and the Now Playing panel still follows the album. Light/dark follows Windows
  unless overridden.
- Licence MIT, copyright "DIMENTS".
- Test command: `./gradlew jvmTest` (single test: `./gradlew jvmTest --tests "<FQN>"`).

## Review Focus

1. **No active Spotify device when a request is next.** The request stays at the head of the
   queue, the status chip says so, and it is handed over automatically once a device is active.
   → Task 8 `noActiveDeviceKeepsRequestAndRetries`.
2. **The streamer clears Spotify's queue or starts something else after a hand-over.** The
   handed-over request returns to the head of Requestify's queue instead of vanishing.
   → Task 8 `handedOverRequestMissingFromSpotifyQueueReturnsToHead`.
3. **Token refresh during a long stream.** A rotated refresh token is persisted before the new
   access token is used. A network blip during refresh never logs the streamer out.
   → Task 6 `refreshPersistsRotatedTokenBeforeReturning`, `networkErrorDuringRefreshKeepsLogin`.
4. **App crash or restart with a full queue and a handed-over request.** Everything is restored
   and reconciled with what Spotify is playing.
   → Task 4 `queueStoreRoundTrip`, Task 8 `restoresHandedOverRequestAsNowPlaying`.
5. **A settings or queue file truncated by a crash or power loss mid-write.** The app starts with
   defaults, the broken file is kept aside, and nothing crashes.
   → Task 4 `corruptFileFallsBackToDefaultAndIsKept`.

---

### Task 1: Project scaffold with an Expressive smoke test

**Files:**
- Create: `.gitignore`, `LICENSE`, `README.md`, `settings.gradle.kts`, `build.gradle.kts`,
  `gradle.properties`, `gradle/libs.versions.toml`
- Copy from `../CastIRL`: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/Main.kt`
- Create: `src/commonMain/composeResources/values/strings.xml`, `src/commonMain/composeResources/values-nl/strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ExpressiveSmokeTest.kt`

**Interfaces:**
- Produces: generated resource class `nl.diments.requestify.resources.Res`
  (`compose.resources { packageOfResClass = "nl.diments.requestify.resources"; publicResClass = false }`),
  string `app_name` = "Requestify" in both locales.

- [ ] **Step 1: `git init`**, then write `.gitignore` with `.gradle/`, `build/`, `.kotlin/`,
  `.idea/`, `*.iml`, `local.properties`, `.superpowers/`. Copy the wrapper files from `../CastIRL`.
- [ ] **Step 2: Write the build.** `kotlin("multiplatform")` with a single `jvm()` target,
  `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose` +
  `org.jetbrains.kotlin.plugin.serialization`, all versions in `libs.versions.toml` (Tech Stack
  above). `compose.desktop.application { mainClass = "nl.diments.requestify.MainKt";
  nativeDistributions { targetFormats(Msi, Exe); packageName = "Requestify"; packageVersion = "0.1.0" } }`.
  Test deps: `kotlin("test")`, `compose.desktop.uiTestJUnit4`, coroutines-test, ktor-client-mock.
- [ ] **Step 3: Write the failing test**

```kotlin
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
class ExpressiveSmokeTest {
    @Test fun expressiveComponentsAndResourcesResolve() = runComposeUiTest {
        setContent {
            MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) {
                Column {
                    Text(stringResource(Res.string.app_name))
                    LinearWavyProgressIndicator(progress = { 0.5f })
                    ButtonGroup(overflowIndicator = {}) { clickableItem(onClick = {}, label = "Skip") }
                }
            }
        }
        onNodeWithText("Requestify").assertExists()
    }
}
```

- [ ] **Step 4: Run** `./gradlew jvmTest --tests "nl.diments.requestify.ExpressiveSmokeTest"`.
  Expected: FAIL (`Res`/`app_name` unresolved).
- [ ] **Step 5: Add `strings.xml`** (en + nl, `app_name`) and `Main.kt`: `application { Window(onCloseRequest = ::exitApplication, title = stringResource(Res.string.app_name)) { Text(...) } }`.
  If any Expressive symbol in the test does not resolve with material3 1.9.0, switch
  `material3` to the newest `org.jetbrains.compose.material3` pre-release (≥ 1.12.0-alpha03)
  and leave a comment in `libs.versions.toml` explaining why.
- [ ] **Step 6: Run the test.** Expected: PASS. Then `./gradlew run` opens a window titled
  "Requestify".
- [ ] **Step 7: Write `LICENSE`** (MIT, "Copyright (c) 2026 DIMENTS") and a 10-line `README.md`
  (what it is, link to the spec). Commit everything, including `docs/`:
  `git add -A && git commit -m "chore: scaffold Requestify desktop app"` (+ trailer).

---

### Task 2: Domain model and `RequestQueue`

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/model/Models.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/queue/RequestQueue.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/queue/RequestQueueTest.kt`

**Interfaces:**
- Produces (all `@Serializable`):

```kotlin
data class Track(val id: String, val uri: String, val name: String, val artists: List<String>,
                 val durationMs: Long, val explicit: Boolean, val albumArtUrl: String?)
enum class RequestSource { MANUAL, CHAT, CHANNEL_POINTS, BITS, STREAMELEMENTS, DONOLINK }
sealed interface Payment {
    data class Bits(val count: Int) : Payment
    data class Money(val amountCents: Long, val currency: String) : Payment
}
data class Requester(val displayName: String, val login: String? = null)
data class Request(val id: String, val track: Track, val requester: Requester,
                   val source: RequestSource, val payment: Payment?, val createdAtEpochMs: Long,
                   val sourceRef: String? = null) { val isPaid: Boolean get() = payment != null }

data class RequestQueue(val items: List<Request> = emptyList()) {
    val head: Request?
    val totalDurationMs: Long
    fun add(request: Request, paidPriority: Boolean): RequestQueue
    fun addFirst(request: Request): RequestQueue
    fun remove(id: String): RequestQueue
    fun move(id: String, toIndex: Int): RequestQueue
}
```

**Insertion rule (from the spec, with the manual-reorder override):** a free request is appended.
With `paidPriority`, a paid request goes **directly after the last paid request in the list, or at
index 0 if there is none**. Positions the streamer set by hand are never re-sorted.

- [ ] **Step 1: Write the failing tests.** Use a helper `req(id, paid: Boolean)` (paid =
  `Payment.Bits(100)`, track duration 200_000):

```kotlin
@Test fun freeRequestsAppendInOrder() =
    assertEquals(listOf("f1","f2"), RequestQueue().add(req("f1"), true).add(req("f2"), true).ids())
@Test fun paidGoesAfterLastPaidBeforeFree() = assertEquals(listOf("p1","p2","f1"),
    RequestQueue().add(req("f1"),true).add(req("p1",paid=true),true).add(req("p2",paid=true),true).ids())
@Test fun paidPriorityOffAppends() = assertEquals(listOf("f1","p1"),
    RequestQueue().add(req("f1"),false).add(req("p1",paid=true),false).ids())
@Test fun manualMoveIsRespected() {   // streamer moved f1 above p1
    val q = RequestQueue(listOf(req("p1",paid=true), req("f1"))).move("f1", 0)
    assertEquals(listOf("f1","p1","p2"), q.add(req("p2",paid=true), true).ids())
}
@Test fun moveClampsIndex() = assertEquals(listOf("b","a"),
    RequestQueue(listOf(req("a"),req("b"))).move("a", 99).ids())
@Test fun removeUnknownIdIsNoOp() { val q = RequestQueue(listOf(req("a"))); assertEquals(q, q.remove("x")) }
@Test fun addFirstPutsAtHead() = assertEquals("x", RequestQueue(listOf(req("a"))).addFirst(req("x")).head?.id)
@Test fun totalDuration() = assertEquals(400_000, RequestQueue(listOf(req("a"),req("b"))).totalDurationMs)
```

- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*RequestQueueTest"`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement** `Models.kt` and `RequestQueue.kt` (immutable: every operation returns a new instance).
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: request model and queue ordering`.

---

### Task 3: Spotify input classification

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/SpotifyInput.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/spotify/SpotifyInputTest.kt`

**Interfaces:**
- Produces:

```kotlin
sealed interface SpotifyInput {
    data class TrackId(val id: String) : SpotifyInput
    data class NotATrack(val kind: String) : SpotifyInput   // "album", "playlist", "episode", "artist", "show"
    data class SearchText(val query: String) : SpotifyInput  // trimmed, non-empty, no Spotify link found
    data object Empty : SpotifyInput
}
fun classifySpotifyInput(input: String): SpotifyInput
```

The first Spotify link anywhere in the text wins. That lets donation messages ("love this
https://open.spotify.com/track/… !!") work later. Track ids are 22 base62 characters.

- [ ] **Step 1: Write the failing tests** (id `4uLU6hMCjMI75M1A2tKUQC`):

```kotlin
@Test fun plainLink() = assertEquals(TrackId(ID), classify("https://open.spotify.com/track/$ID"))
@Test fun intlLinkWithQuery() = assertEquals(TrackId(ID), classify("https://open.spotify.com/intl-nl/track/$ID?si=abc123"))
@Test fun uri() = assertEquals(TrackId(ID), classify("spotify:track:$ID"))
@Test fun linkInsideMessage() = assertEquals(TrackId(ID), classify("love this https://open.spotify.com/track/$ID !!"))
@Test fun album() = assertEquals(NotATrack("album"), classify("https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX"))
@Test fun playlist() = assertEquals(NotATrack("playlist"), classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
@Test fun episodeUri() = assertEquals(NotATrack("episode"), classify("spotify:episode:512ojhOuo1ktJprKbVcKyQ"))
@Test fun searchText() = assertEquals(SearchText("daft punk one more time"), classify("  daft punk one more time "))
@Test fun blank() = assertEquals(Empty, classify("   "))
@Test fun malformedIdIsSearch() = assertEquals(SearchText("https://open.spotify.com/track/short"), classify("https://open.spotify.com/track/short"))
```

- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*SpotifyInputTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** with one regex for `open.spotify.com/(intl-[a-z-]+/)?(track|album|playlist|episode|artist|show)/([A-Za-z0-9]{22})`
  and one for `spotify:(track|album|…):([A-Za-z0-9]{22})`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: classify Spotify links and search text`.

---

### Task 4: App paths, crash-safe JSON stores, settings and queue persistence

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/storage/AppPaths.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/storage/JsonFileStore.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/settings/Settings.kt` (model + `SettingsStore`)
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/queue/QueueStore.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/storage/JsonFileStoreTest.kt`, `.../core/queue/QueueStoreTest.kt`

**Interfaces:**
- Consumes: `Request`, `RequestQueue` (Task 2).
- Produces:

```kotlin
class AppPaths(val dataDir: Path) { companion object { fun default(): AppPaths } } // %APPDATA%\Requestify, created if missing
    // files: settings.json, queue.json, secrets.json
class JsonFileStore<T>(path: Path, serializer: KSerializer<T>, default: () -> T) {
    fun read(): T          // missing → default(); unparseable → rename to "<name>.corrupt-<epochMs>", return default()
    fun write(value: T)    // write "<name>.tmp", then Files.move(ATOMIC_MOVE, REPLACE_EXISTING)
}
@Serializable data class Settings(
    val spotifyClientId: String? = null,
    val themeMode: ThemeMode = ThemeMode.Album,
    val darkMode: DarkMode = DarkMode.System,
    val language: AppLanguage = AppLanguage.System,
    val paidPriority: Boolean = true,
)
@Serializable sealed interface ThemeMode { @Serializable data object Album : ThemeMode
                                           @Serializable data class Custom(val seedArgb: Int) : ThemeMode }
enum class DarkMode { System, Light, Dark };  enum class AppLanguage { System, English, Dutch }
class SettingsStore(file: JsonFileStore<Settings>) { val settings: StateFlow<Settings>; fun update(f: (Settings) -> Settings) } // persists on every update

@Serializable data class PlayedEntry(val request: Request, val playedAtEpochMs: Long)
@Serializable data class PersistedPlayback(val queue: RequestQueue = RequestQueue(),
    val handedOver: Request? = null, val playing: Request? = null, val history: List<PlayedEntry> = emptyList())
class QueueStore(file: JsonFileStore<PersistedPlayback>) { fun load(): PersistedPlayback; fun save(p: PersistedPlayback) } // history capped to 50 newest on save
```

Json config everywhere: `Json { ignoreUnknownKeys = true; encodeDefaults = true }`. Forward
compatibility matters for an app that auto-updates.

- [ ] **Step 1: Write the failing tests** (JUnit `@TempDir`):
  - `roundTrip`: write `Settings(spotifyClientId = "a".repeat(32))` → `read()` equals it.
  - `missingFileGivesDefault`: `read()` on an absent path == `Settings()`.
  - `corruptFileFallsBackToDefaultAndIsKept`: write the text `{"spotifyClientId":` → `read()` == `Settings()`,
    and exactly one file matching `settings.json.corrupt-*` exists with that text.
  - `writeLeavesNoTempFile`: after `write`, `settings.json.tmp` does not exist.
  - `queueStoreRoundTrip`: a `PersistedPlayback` with 2 queued (one paid `Money(500,"EUR")`), a
    `handedOver` and a `playing` request survives save → load unchanged.
  - `historyCappedTo50`: saving 60 entries keeps the 50 with the highest `playedAtEpochMs`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*JsonFileStoreTest" --tests "*QueueStoreTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: crash-safe settings and queue persistence`.

---

### Task 5: DPAPI secret store

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/secrets/SecretStore.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/secrets/DpapiSecretStoreTest.kt`

**Interfaces:**
- Consumes: `JsonFileStore` (Task 4).
- Produces:

```kotlin
interface SecretStore { fun get(key: String): String?; fun put(key: String, value: String); fun remove(key: String) }
class DpapiSecretStore(file: Path) : SecretStore   // Map<String, base64(CryptProtectData(utf8))> in secrets.json, via Crypt32Util
class InMemorySecretStore : SecretStore             // for tests
object SecretKeys { const val SPOTIFY_REFRESH_TOKEN = "spotify.refresh_token" }
```

- [ ] **Step 1: Write the failing tests:** `roundTrip` (put/get "tok-123"), `fileHasNoPlaintext`
  (raw `secrets.json` does not contain "tok-123"), `undecryptableEntryReturnsNull` (hand-edit the
  base64 to garbage → `get` returns null and does not throw), `removeDeletes`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*DpapiSecretStoreTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** using `com.sun.jna.platform.win32.Crypt32Util.cryptProtectData` / `cryptUnprotectData` (current-user scope).
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: DPAPI-backed secret store`.

---

### Task 6: Spotify auth (PKCE, loopback callback, refresh with rotation)

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/auth/Pkce.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/auth/LoopbackReceiver.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/auth/SpotifyAuth.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/spotify/auth/PkceTest.kt`, `LoopbackReceiverTest.kt`, `SpotifyAuthTest.kt`

**Interfaces:**
- Consumes: `SecretStore`, `SecretKeys` (Task 5).
- Produces:

```kotlin
object Pkce { fun verifier(random: SecureRandom = SecureRandom()): String  // 64 chars, [A-Za-z0-9-._~]
              fun challenge(verifier: String): String }                      // base64url(SHA-256), no padding
const val SPOTIFY_REDIRECT_PORT = 43821
const val SPOTIFY_REDIRECT_URI = "http://127.0.0.1:43821/callback"
val SPOTIFY_SCOPES = listOf("user-read-playback-state", "user-modify-playback-state",
                            "user-read-currently-playing", "user-read-private")
class LoopbackReceiver(port: Int = SPOTIFY_REDIRECT_PORT) {   // JDK com.sun.net.httpserver.HttpServer on 127.0.0.1
    val boundPort: Int
    suspend fun awaitCode(expectedState: String, timeout: Duration = 5.minutes): Result<String>
}   // replies with a small localized "You can close this window" HTML page; stops the server afterwards
data class AccessToken(val value: String, val expiresAtEpochMs: Long)
sealed interface SpotifyAuthStatus { data object LoggedOut; data object LoggedIn; data class LoginNeeded(val reason: String) }
sealed interface LoginResult { data object Success; data object TimedOut; data class Failed(val reason: String) }
class SpotifyAuth(http: HttpClient, secrets: SecretStore, clientId: () -> String?, clock: () -> Long,
                  openBrowser: (String) -> Unit, receiverFactory: () -> LoopbackReceiver,
                  accountsBaseUrl: String = "https://accounts.spotify.com") {
    val status: StateFlow<SpotifyAuthStatus>
    fun authorizeUrl(challenge: String, state: String): String
    suspend fun login(): LoginResult
    suspend fun accessToken(forceRefresh: Boolean = false): AccessToken?   // null → login needed (or transient failure)
    fun logout()
}
```

Refresh happens when the cached token expires within 60 s. Concurrent callers share one refresh
(`Mutex`).

- [ ] **Step 1: Write the failing tests:**
  - `PkceTest.rfc7636Vector`: `challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"`; `verifier().length == 64`.
  - `LoopbackReceiverTest.returnsCode`: receiver on port 0, HTTP GET `/callback?code=abc&state=s1` → `awaitCode("s1")` == success("abc").
    `wrongStateFails` (state=s2 → failure), `accessDeniedFails` (`?error=access_denied&state=s1` → failure).
  - `SpotifyAuthTest` (ktor `MockEngine`, `InMemorySecretStore`, fake clock):
    - `authorizeUrlHasExactParams`: contains `client_id=<id>`, `response_type=code`,
      `redirect_uri=http%3A%2F%2F127.0.0.1%3A43821%2Fcallback`, `code_challenge_method=S256`, and the four scopes space-joined.
    - `loginExchangesCodeAndStoresRefreshToken`: the token POST form has `grant_type=authorization_code`, `code`, `code_verifier`,
      `client_id`, `redirect_uri`; afterwards `secrets.get(SPOTIFY_REFRESH_TOKEN) == "r1"`, status `LoggedIn`.
    - `refreshPersistsRotatedTokenBeforeReturning`: an expired token, response `refresh_token = "r2"` → the secret is "r2"
      at the moment `accessToken()` returns (assert inside the MockEngine handler of the *next* request too).
    - `refreshWithoutNewRefreshTokenKeepsOld`: response without `refresh_token` → secret stays "r1".
    - `invalidGrantLogsOut`: 400 `{"error":"invalid_grant"}` → `accessToken()` null, secret removed, status `LoginNeeded`.
    - `networkErrorDuringRefreshKeepsLogin`: the engine throws `IOException` → `accessToken()` null, secret **still "r1"**, status still `LoggedIn`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.core.spotify.auth.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: Spotify PKCE login with rotating refresh tokens`.

---

### Task 7: Spotify Web API client and error mapping

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/spotify/SpotifyApi.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/spotify/SpotifyApiTest.kt` (+ JSON fixtures in `src/jvmTest/resources/spotify/`)

**Interfaces:**
- Consumes: a token lambda backed by `SpotifyAuth.accessToken()` (Task 6), `Track` (Task 2).
- Produces:

```kotlin
sealed interface SpotifyResult<out T> {
    data class Ok<T>(val value: T) : SpotifyResult<T>
    data object NoActiveDevice : SpotifyResult<Nothing>; data object PremiumRequired : SpotifyResult<Nothing>
    data class RateLimited(val retryAfterMs: Long) : SpotifyResult<Nothing>
    data object LoginNeeded : SpotifyResult<Nothing>; data class Failed(val message: String) : SpotifyResult<Nothing>
}
data class CurrentlyPlaying(val track: Track?, val progressMs: Long, val isPlaying: Boolean) // track null for ads/episodes
interface SpotifyPlayer {            // the port PlaybackController uses (Task 8 fakes it)
    suspend fun currentlyPlaying(): SpotifyResult<CurrentlyPlaying?>   // Ok(null) on 204
    suspend fun userQueueTrackIds(): SpotifyResult<List<String>>
    suspend fun addToQueue(trackUri: String): SpotifyResult<Unit>
    suspend fun skipNext(): SpotifyResult<Unit>
    suspend fun pause(): SpotifyResult<Unit>
    suspend fun resume(): SpotifyResult<Unit>
}
interface SpotifyCatalog {
    suspend fun searchTracks(query: String, limit: Int = 5): SpotifyResult<List<Track>>  // limit coerced to 1..10
    suspend fun track(id: String): SpotifyResult<Track>
    suspend fun isPremium(): SpotifyResult<Boolean?>  // from GET /me "product" when present, else Ok(null)
}
class SpotifyApi(http: HttpClient, token: suspend (forceRefresh: Boolean) -> String?,
                 baseUrl: String = "https://api.spotify.com/v1") : SpotifyPlayer, SpotifyCatalog
    // AppGraph passes { force -> auth.accessToken(force)?.value }; tests pass a lambda that counts forced refreshes
```

Mapping (one shared request helper):
- 401 → `token(true)` and retry **once**; still 401 or null token → `LoginNeeded`.
- 403 with `error.reason == "PREMIUM_REQUIRED"` → `PremiumRequired`.
- 404 with `error.reason == "NO_ACTIVE_DEVICE"` → `NoActiveDevice`.
- 429 → `RateLimited(Retry-After seconds × 1000)`, or 5_000 when the header is missing or unparseable.
- IO error, timeout or other non-2xx → `Failed`.

`Track` mapping: `artists[].name`, `album.images` → the URL whose `width` is closest to 300 (null if
there are none).

- [ ] **Step 1: Write the failing tests:** `parsesCurrentlyPlaying` (fixture → track id, name,
  `artists == listOf("M83")`, `progressMs = 141000`, `isPlaying`), `noContentIsOkNull` (204),
  `unauthorizedRefreshesAndRetriesOnce` (401 then 200 → Ok, and exactly one forced refresh),
  `premiumRequired`, `noActiveDevice`, `rateLimitedUsesRetryAfter` ("7" → 7000),
  `rateLimitedDefault` (no header → 5000), `searchLimitCappedAt10` (ask 25 → request has
  `limit=10`, `type=track`), `addToQueueSendsUri` (POST `/me/player/queue?uri=spotify%3Atrack%3A<ID>`),
  `isPremiumNullWhenProductMissing`, `albumArtClosestTo300`.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*SpotifyApiTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: Spotify Web API client with error mapping`.

---

### Task 8: `PlaybackController` (the hand-over loop)

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/core/playback/PlaybackController.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/core/playback/PlaybackControllerTest.kt` (+ `FakeSpotifyPlayer.kt`)

**Interfaces:**
- Consumes: `SpotifyPlayer`, `SpotifyResult`, `CurrentlyPlaying` (Task 7); `QueueStore`, `PersistedPlayback`, `PlayedEntry` (Task 4); `RequestQueue`, `Request` (Task 2).
- Produces:

```kotlin
data class NowPlaying(val track: Track, val progressMs: Long, val isPlaying: Boolean, val request: Request?) // null = streamer's own music
sealed interface SpotifyHealth { data object Ok; data object NothingPlaying; data object NoActiveDevice; data object LoginNeeded
    data object PremiumRequired; data class RateLimited(val untilEpochMs: Long); data class Unreachable(val message: String) }
data class PlaybackConfig(val handoverLeadMs: Long = 15_000, val idlePollMs: Long = 3_000,
                          val nearEndPollMs: Long = 1_000, val nearEndWindowMs: Long = 20_000)
class PlaybackController(player: SpotifyPlayer, store: QueueStore, requestsOpen: StateFlow<Boolean>,
                         paidPriority: () -> Boolean, scope: CoroutineScope, clock: () -> Long,
                         config: PlaybackConfig = PlaybackConfig()) {
    val queue: StateFlow<RequestQueue>; val nowPlaying: StateFlow<NowPlaying?>
    val history: StateFlow<List<PlayedEntry>>; val health: StateFlow<SpotifyHealth>
    fun start(); fun stop()
    fun enqueue(request: Request); fun remove(id: String); fun move(id: String, toIndex: Int)
    suspend fun skip(); suspend fun togglePause()
}
```

**Tick algorithm** (decided here; the spec leaves the timing to the builder):

```
cp = player.currentlyPlaying()                     // non-Ok → health; RateLimited → next tick after retryAfterMs
trackChanged = cp?.track?.id != lastTrackId
if trackChanged:
    if playing != null && playing.track.id != cp?.track?.id: history += playing; playing = null
    if handedOver != null && cp?.track?.id == handedOver.track.id: playing = handedOver; handedOver = null
    else if handedOver != null && lastTrackId != null:          // a track ended, and it wasn't ours
        ids = player.userQueueTrackIds()
        if Ok && handedOver.track.id !in ids: queue = queue.addFirst(handedOver); handedOver = null
nowPlaying = cp?.track?.let { NowPlaying(it, cp.progressMs, cp.isPlaying, playing?.takeIf { p -> p.track.id == it.id }) }
remaining = cp.track.durationMs - cp.progressMs
if requestsOpen && handedOver == null && queue.head != null && cp?.track != null && remaining <= handoverLeadMs:
    when (player.addToQueue(head.track.uri)) { Ok -> handedOver = head; queue = queue.remove(head.id)
                                               NoActiveDevice -> health = NoActiveDevice (keep head) }
persist PersistedPlayback(queue, handedOver, playing, history) whenever any of them changed
next tick after (remaining <= nearEndWindowMs ? nearEndPollMs : idlePollMs)
```

On `start()`, load `PersistedPlayback` first. The first tick then reconciles a persisted
`handedOver`/`playing` against `cp`. Nothing auto-starts playback when Spotify is idle: health
becomes `NothingPlaying` and requests wait. `skip()`: if `requestsOpen` and a head exists and
nothing is handed over, `addToQueue(head)` first, then `skipNext()`.

- [ ] **Step 1: Write the failing tests** with `runTest` virtual time, `clock = { testScheduler.currentTime }` and a
  scriptable `FakeSpotifyPlayer` that records calls. Track A is 200_000 ms long.
  - `handsOverHeadWithin15Seconds`: progress 184_000 (16 s left) → no `addToQueue`; advance → progress 185_000 → `addToQueue(R1.uri)` once; queue empty.
  - `noHandoverWhenRequestsClosed`: `requestsOpen = false`, 5 s left → no `addToQueue`.
  - `handedOverBecomesNowPlayingWithRequester`: after the hand-over the fake reports R1's track → `nowPlaying.request == R1`, and the store holds `playing == R1`.
  - `finishedRequestGoesToHistory`: then own track B plays → `history.first().request == R1`, `nowPlaying.request == null`.
  - `noActiveDeviceKeepsRequestAndRetries`: `addToQueue` → `NoActiveDevice` → health `NoActiveDevice`, R1 still the head; the next tick returns Ok → handed over.
  - `handedOverRequestMissingFromSpotifyQueueReturnsToHead`: R1 handed over, the track changes to C (not R1), `userQueueTrackIds()` = `[]` → queue head is R1, `handedOver` null.
  - `handedOverStillInSpotifyQueueStaysHandedOver`: same, but the ids contain R1 → still handed over.
  - `rateLimitedWaitsRetryAfter`: `RateLimited(7000)` → no call at +6_999 ms, a call at +7_000 ms.
  - `restoresHandedOverRequestAsNowPlaying`: the store starts with `handedOver = R1`, the fake plays R1 → the first tick gives `nowPlaying.request == R1`.
  - `skipHandsOverHeadThenSkips`: R1 queued, `skip()` → calls in order `addToQueue(R1)`, `skipNext()`.
  - `sameTrackTwiceOnlyFirstIsPlaying`: R1 and R2 with the same track; R1 handed over and playing → R2 still queued.
  - `pollsFasterNearEnd`: 30 s left → the next call after 3_000; 19 s left → after 1_000.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*PlaybackControllerTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.** One coroutine loop in `scope` (cancelled by `stop()`). State changes
  are serialized through a `Mutex`, because UI calls such as `enqueue`/`remove`/`move` race with the
  tick.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: playback controller hands requests to Spotify`.

---

### Task 9: Theme: album-art colour, custom colour, source chips

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/theme/ThemeSelection.kt` (pure)
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/theme/AlbumSeed.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/theme/RequestifyTheme.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/theme/SourceStyle.kt`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/theme/ThemeSelectionTest.kt`, `AlbumSeedTest.kt`

**Interfaces:**
- Consumes: `ThemeMode`, `DarkMode` (Task 4); `RequestSource` (Task 2).
- Produces:

```kotlin
val BrandSeed = Color(0xFF7C5CFF)                         // fallback + default custom colour (builder's call)
data class SchemeSeeds(val app: Color, val nowPlaying: Color)
fun selectSeeds(mode: ThemeMode, albumSeed: Color?): SchemeSeeds
fun resolveDark(mode: DarkMode, systemDark: Boolean): Boolean
fun albumSeedFrom(image: ImageBitmap): Color?             // MaterialKolor quantize + score; null when no usable colour
@Composable fun RequestifyTheme(seed: Color, dark: Boolean, content: @Composable () -> Unit)
    // MaterialKolor DynamicMaterialExpressiveTheme(seed, isDark = dark, animate = true, motionScheme = MotionScheme.expressive());
    // if absent in 5.0.1: MaterialExpressiveTheme(colorScheme = animated rememberDynamicColorScheme(seed, dark)).
@Composable fun SourceChip(source: RequestSource, label: String, modifier: Modifier = Modifier)
object SourceColors { fun container(s: RequestSource): Color; fun content(s: RequestSource): Color }
```

Fixed source colours (from the approved mockups): CHANNEL_POINTS `#3B2A63`/`#DCCBFF`, BITS
`#2C3266`/`#C9D0FF`, STREAMELEMENTS `#1D4636`/`#A8F0D0`, DONOLINK `#5B2F20`/`#FFCDB6`. CHAT and
MANUAL use the theme's `surfaceContainerHighest`/`onSurfaceVariant` and are deliberately neutral.

- [ ] **Step 1: Write the failing tests:**
  - `albumModeUsesAlbumEverywhere`: `selectSeeds(Album, red) == SchemeSeeds(red, red)`.
  - `albumModeWithoutArtFallsBackToBrand`: `selectSeeds(Album, null) == SchemeSeeds(BrandSeed, BrandSeed)`.
  - `customModeThemesChromeOnly`: `selectSeeds(Custom(green), red) == SchemeSeeds(green, red)`.
  - `customModeWithoutArt`: `selectSeeds(Custom(green), null) == SchemeSeeds(green, green)`.
  - `resolveDark`: System follows `systemDark`; Light → false; Dark → true.
  - `AlbumSeedTest.redImageGivesRedSeed`: a 64×64 `#E53935` bitmap → hue within 20° of 2°.
  - `AlbumSeedTest.greyImageGivesNull`: a 64×64 `#808080` bitmap → null.
  - `sourceColoursAreDistinct`: the 4 coloured sources have pairwise different containers.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.ui.theme.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: album-art and custom colour theming`.

---

### Task 10: Home screen (queue left, Now Playing right)

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/HomeViewModel.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/home/HomeScreen.kt`, `QueuePane.kt`, `NowPlayingPane.kt`, `AddTrackField.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/common/Format.kt`, `SpotifyStatusChip.kt`
- Modify: `src/commonMain/composeResources/values/strings.xml`, `values-nl/strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/home/HomeViewModelTest.kt`, `QueuePaneTest.kt`, `NowPlayingPaneTest.kt`, `.../ui/common/FormatTest.kt`

**Interfaces:**
- Consumes: `PlaybackController` (Task 8), `SpotifyCatalog` (Task 7), `classifySpotifyInput` (Task 3), `SettingsStore` (Task 4), `RequestifyTheme`/`selectSeeds`/`albumSeedFrom`/`SourceChip` (Task 9).
- Produces:

```kotlin
sealed interface AddMessage { data object NoResults; data class NotATrack(val kind: String); data object SpotifyUnavailable }
data class HomeUiState(val queue: List<Request>, val queueDurationMs: Long, val history: List<PlayedEntry>,
                       val nowPlaying: NowPlaying?, val health: SpotifyHealth,
                       val results: List<Track>, val addMessage: AddMessage?)
class HomeViewModel(controller: PlaybackController, catalog: SpotifyCatalog, settings: SettingsStore,
                    scope: CoroutineScope, clock: () -> Long, newId: () -> String = { UUID.randomUUID().toString() }) {
    val state: StateFlow<HomeUiState>
    fun onAddInput(text: String)   // TrackId → catalog.track(id) → results = [it]; SearchText → debounce 300 ms → searchTracks(q, 5); NotATrack → message; Empty → clear
    fun onAddTrack(track: Track)   // Request(source = MANUAL, requester = Requester(""), payment = null) → controller.enqueue; clears input + results
    fun onRemove(id: String); fun onMove(id: String, toIndex: Int)
    fun onSkip(); fun onTogglePause()
}
fun formatDuration(ms: Long): String   // "m:ss"; 200_000 → "3:20", 3_600_000 → "60:00"
@Composable fun HomeScreen(vm: HomeViewModel)
```

Layout (the approved mockup, "B mirrored"):
- Window width ≥ 900 dp: `Row(QueuePane(weight 1f), NowPlayingPane(width 320.dp))`. Narrower:
  NowPlaying above the queue.
- **QueuePane:** headline "Queue"/"Wachtrij"; segmented buttons "Queue · n" / "Played"; status
  chip; `AddTrackField` (M3 search bar, placeholder "Search or paste a Spotify link", results
  dropdown); `LazyColumn` with `sh.calvin.reorderable` drag handles. Rows are a connected list
  (4 dp gap; outer corners 20 dp, inner 6 dp) showing art (Coil `AsyncImage`), title, artist,
  `SourceChip`, requester (MANUAL → localized "You"/"Jij"), a bolt for `isPaid`, duration, and a
  remove button. The Played tab lists `history` newest first.
- **NowPlayingPane:** label "NOW PLAYING", large art (asymmetric 36/36/12/36 dp corners and an
  expressive "pop" on track change), title (emphasized display style), artist, requester +
  source chip or "Your own music"/"Je eigen muziek", `LinearWavyProgressIndicator` (progress
  interpolated locally between polls), times, and a `ButtonGroup` with play/pause (primary, wide)
  and skip.
- The whole Home screen is wrapped in `RequestifyTheme(seeds.app)`, and NowPlayingPane in
  `RequestifyTheme(seeds.nowPlaying)`. The album seed is computed from the loaded art bitmap.
- `SpotifyStatusChip` maps `SpotifyHealth` to text + icon: Ok "Spotify connected", NothingPlaying
  "Spotify is not playing", NoActiveDevice "Open Spotify on a device", LoginNeeded "Log in to
  Spotify again", PremiumRequired "Spotify Premium required", RateLimited "Spotify busy, retrying",
  Unreachable "Spotify unreachable".

- [ ] **Step 1: Write the failing tests:**
  - `FormatTest`: `formatDuration(200_000) == "3:20"`, `formatDuration(59_999) == "0:59"`, `formatDuration(3_600_000) == "60:00"`.
  - `HomeViewModelTest` (fake catalog + a controller over `FakeSpotifyPlayer`, virtual time):
    `linkResolvesTrackWithoutSearch` (link input → one `track(id)` call, zero `searchTracks`),
    `textSearchIsDebounced` ("d","da","daft" within 300 ms → exactly one `searchTracks("daft", 5)`),
    `notATrackShowsMessage` (playlist link → `AddMessage.NotATrack("playlist")`),
    `noResultsShowsMessage`, `addTrackEnqueuesManualFreeRequest` (source MANUAL, payment null, appended at the end, results cleared),
    `removeAndMoveDelegate`.
  - `QueuePaneTest` (`runComposeUiTest`, `Locale.setDefault(Locale.ENGLISH)`): a row shows "Blinding Lights", "The Weeknd",
    "3:20", the chip text "DonoLink €5"; clicking the row's remove button calls `onRemove("r1")`.
  - `NowPlayingPaneTest`: `request == null` → shows "Your own music"; with a BITS request → shows "500 Bits" and the requester name.
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "nl.diments.requestify.ui.*"`. Expected: FAIL.
- [ ] **Step 3: Implement** the view model, panes and strings (en + nl for every new string).
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: Material 3 Expressive home screen`.

---

### Task 11: Spotify setup, Settings, navigation and app wiring

**Files:**
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/setup/SpotifySetupViewModel.kt`, `SpotifySetupScreen.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/settings/SettingsScreen.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/ui/AppScaffold.kt`
- Create: `src/jvmMain/kotlin/nl/diments/requestify/AppGraph.kt`
- Modify: `src/jvmMain/kotlin/nl/diments/requestify/Main.kt`, both `strings.xml`
- Test: `src/jvmTest/kotlin/nl/diments/requestify/ui/setup/SpotifySetupViewModelTest.kt`, `src/jvmTest/kotlin/nl/diments/requestify/AppGraphTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces:

```kotlin
fun isValidSpotifyClientId(s: String): Boolean          // ^[0-9a-fA-F]{32}$ after trim
sealed interface PremiumCheck { data object Premium; data object NotPremium; data object Unknown }
data class SpotifySetupState(val clientId: String, val clientIdValid: Boolean, val auth: SpotifyAuthStatus,
                             val loggingIn: Boolean, val loginError: String?, val premium: PremiumCheck?, val health: SpotifyHealth)
class SpotifySetupViewModel(settings: SettingsStore, auth: SpotifyAuth, catalog: SpotifyCatalog,
                            controller: PlaybackController, scope: CoroutineScope) {
    val state: StateFlow<SpotifySetupState>
    fun onClientIdChange(s: String)   // saves to settings only when valid
    fun onLogin(); fun onLogout()
}
class AppGraph(paths: AppPaths, secrets: SecretStore, httpEngine: HttpClientEngine = CIO.create(), scope: CoroutineScope)
    // builds HttpClient (ContentNegotiation json, HttpTimeout request 15 s), stores, SpotifyAuth (clock = System::currentTimeMillis,
    // openBrowser = Desktop.browse), SpotifyApi, PlaybackController(requestsOpen = MutableStateFlow(true), paidPriority = { settings.paidPriority }),
    // HomeViewModel, SpotifySetupViewModel
enum class Destination { Home, Connections, Settings }   // later plans add Rules, Blacklist, Log
```

- **Navigation rail** (Expressive): FAB "Add" (focuses `AddTrackField` on Home), Home,
  Connections ("Koppelingen"), Settings. Start destination: `Connections` when there is no valid
  client ID or auth is `LoggedOut`/`LoginNeeded`, otherwise `Home`.
- **Spotify setup screen**, numbered steps:
  1. Open developer.spotify.com/dashboard (button), create an app, tick "Web API".
  2. Copy the redirect URI (button copies `SPOTIFY_REDIRECT_URI` to the clipboard).
  3. Paste the Client ID (field with validation).
  4. "Log in with Spotify" (`SpotifyAuth.login()`).
  5. Result: Premium ✓ / "Requestify needs Spotify Premium" (NotPremium) / "Premium is checked
     when the first track plays" (Unknown), plus a device hint from `health`.
- **Settings → Appearance:** colour "Album art" or "Custom" (8 preset swatches incl.
  `BrandSeed`), theme System/Light/Dark, language System/English/Nederlands (applied at next
  start via `Locale.setDefault` before composition; the UI says so).
- **Main:** `AppPaths.default()` → `AppGraph` → apply the language → `application { Window(size 1180×760 dp, min 720×560) { AppScaffold(graph) } }`;
  on close: `controller.stop()`, then `exitApplication()`.

- [ ] **Step 1: Write the failing tests:**
  - `validClientId` (32 hex → true; 31, 33 or a non-hex char → false; surrounding spaces trimmed → true).
  - `invalidClientIdIsNotSaved`; `validClientIdIsSaved`.
  - `freeAccountShowsNotPremium` (`isPremium()` → `Ok(false)` after login → `PremiumCheck.NotPremium`).
  - `missingProductIsUnknown` (`Ok(null)` → `Unknown`).
  - `loginTimeoutShowsError` (`LoginResult.TimedOut` → `loginError != null`, `loggingIn == false`).
  - `AppGraphTest.buildsWithTempDir` (`AppGraph(AppPaths(tempDir), InMemorySecretStore(), MockEngine{…}, scope)` constructs, and the Home state starts with an empty queue).
- [ ] **Step 2: Run** `./gradlew jvmTest --tests "*SpotifySetupViewModelTest" --tests "*AppGraphTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the screens, rail, wiring and strings (en + nl).
- [ ] **Step 4: Run the full suite** `./gradlew jvmTest`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat: Spotify setup, settings and navigation`.

---

### Task 12: End-to-end check against real Spotify (with the user)

Needs the user's own Spotify developer app and Premium account. Nothing is automated here; this
is the acceptance check for plan 1.

- [ ] **Step 1:** `./gradlew run`. The app opens on Connections. Follow the setup with a real Client ID → logged in, Premium shown.
- [ ] **Step 2:** Start the user's own playlist in Spotify. Home shows it as "Your own music" and the app takes the album colours.
- [ ] **Step 3:** Add one track by search and one by pasted link. About 15 s before the current track ends, the first request is handed over and then shows as Now Playing with "You". After it, the user's playlist continues by itself.
- [ ] **Step 4:** Reorder and remove a queued track and check it sticks. Close and reopen the app: the queue is restored.
- [ ] **Step 5:** Close Spotify on all devices. The chip shows "Open Spotify on a device" and the queue stays intact. Reopen Spotify: hand-over resumes.
- [ ] **Step 6:** Switch to a custom colour. Only the Now Playing panel still follows the album.
- [ ] **Step 7:** `./gradlew packageExe` produces an installer under `build/compose/binaries/`. Install that exe, launch the **installed** app (not `./gradlew run`) and log in to Spotify once: this proves the packaged runtime has every JDK module the app needs (e.g. `jdk.httpserver` for the login). Signing and updates come in plan 4.
- [ ] **Step 8:** Note any findings in the plan, fix with a test first, and commit.
