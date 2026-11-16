# Requestify — design

Date: 2026-11-16
Status: approved by the user in chat on 2026-11-16
Author: DIMENTS

## Intent and why

Requestify is a free, open-source Windows desktop app that lets a streamer take **Spotify song
requests** from viewers through **Twitch** (Channel Points, chat command, Bits),
**StreamElements** tips and **DonoLink** donations, and plays them through the streamer's own
Spotify.

The why, in the user's words: there is no song-request app for streamers that is **reliable**:
"set it up once, and never have to look at it or fiddle with it again". Existing apps do not
offer this at a high level of quality. So the bar for every decision is:

1. **Set-and-forget reliability.** Once configured, it runs every stream without attention.
   Nothing gets lost silently, least of all a paid request.
2. **High-quality feel.** Material 3 Expressive look, motion and colour, so it feels modern and
   fluid, not like a utility.

Publisher context: DIMENTS (diments.nl) already ships streamer tools: CastIRL (Android, Jetpack
Compose + Material 3 Expressive, MIT) and SignalIRL with its Signal-Catchall server (TypeScript,
ingests Twitch EventSub, StreamElements and DonoLink). Requestify belongs to the same family.

## Goals

- One-time guided setup for every connection, then automatic start, connect and recover.
- Requests from five inputs end up in one queue under one set of rules.
- The app owns the queue: the streamer can add, remove and reorder at any time in the app;
  moderators can skip, remove and blacklist through chat commands (no mod panel, no reorder
  command; *builder's call*).
- Follows the stream automatically: requests open when the channel goes live and close when it
  goes offline (user's decision, see "Live status and stream lifecycle").
- The music never stops because of Requestify: when the app is gone or the queue is empty, the
  streamer's own Spotify music keeps playing.
- Every request, accepted or rejected, is visible with who asked, through which source, and why
  it was rejected.
- Public release as a Windows installer from GitHub, with automatic updates.

## Non-goals (v1)

- OBS overlay / "now playing" browser source. Wanted, but **later** (user's decision).
- Kick, YouTube or other platforms. Kick's events API delivers by webhook to a public server,
  which a standalone .exe does not have. The source layer must make adding one later easy.
- macOS / Linux builds (the stack allows them later; not tested or shipped in v1).
- Refunding money for Bits or donations (impossible through these APIs).
- Viewer voting (vote-skip etc.), playlists of requests, statistics dashboards.
- Importing donation/redemption history from before a source was **first** connected. (Catching up
  on what arrived while the app was off or disconnected *is* a goal; see "Missed events".)
- Paid licensing, accounts, a DIMENTS backend. Everything runs on the streamer's PC.

## Anti-goals (failure even if it technically works)

- The streamer has to babysit it: reconnect by hand, re-login every stream, restart the app.
- A request disappears without trace, especially a paid one.
- Requestify stops the music (crash, hang, lost connection) instead of degrading to "the
  streamer's own music keeps playing".
- Secrets shipped inside the .exe, or tokens stored as readable plain text.
- It looks and moves like a generic web app instead of real Material 3 Expressive.
- A blacklisted or over-long song plays because some path skipped the rules.

## Constraints (found during the brainstorm)

### Spotify
- **Development-mode limits (Feb 2026):** an app in development mode allows at most 5 users and
  its owner must have Premium. Extended quota requires a registered business with 250k MAU. So
  Requestify **cannot ship one shared Spotify Client ID**. Each streamer creates their own free
  Spotify developer app and pastes its Client ID into Requestify (decided with the user). Only
  the streamer talks to Spotify; viewers never do, so the 5-user limit is no problem.
- Since July 2026, quota is counted per developer account (up to 25 Client IDs per account).
  Each streamer has their own account, so quota is per streamer.
- **Premium is required** to control playback. Setup must check it. Third-party summaries of
  Spotify's February 2026 development-mode migration guide say `product` (and `country`) were
  removed from `GET /me`; this could not be confirmed from the guide itself. So: use `product` if
  present, otherwise detect Premium from the `PREMIUM_REQUIRED` (403) error on a player call
  during setup. The same summaries say the search `limit` maximum is now 10. Fine for "best
  match", but do not design around larger result pages. Source:
  <https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide>.
- Auth: Authorization Code with **PKCE** (no client secret), loopback redirect
  `http://127.0.0.1:<port>/callback` (Spotify no longer accepts `localhost`). Refresh tokens
  rotate; persist the newest one every time.
- **The Web API cannot remove or reorder items in Spotify's playback queue**; it can only add
  (`POST /me/player/queue`) and read (`GET /me/player/queue`). This is why the app owns the queue
  (see Playback).
- Playback commands need an **active device**. If there is none, requests keep queuing and the
  app shows a clear warning.

### Twitch
- One Twitch application registered by DIMENTS as a **public client**. Login through
  **Device Code Flow** (no secret, works for a desktop app). Single account: the broadcaster.
- Refresh tokens of public clients **expire 30 days after issue**
  (<https://dev.twitch.tv/docs/authentication/refresh-tokens/>). A streamer who does not run the
  app for more than 30 days must log in again. This cannot be avoided without a secret/backend.
  The app must make this a clear, one-click "log in again" with no other settings lost.
- Live status: EventSub `stream.online` / `stream.offline`, plus `GET /helix/streams` at startup.
- Live input through **EventSub over WebSocket** with the broadcaster's user token:
  `channel.channel_points_custom_reward_redemption.add`, `channel.cheer`, `channel.chat.message`.
- **Only the client that created a custom reward may update its redemptions** (fulfil/cancel =
  refund). So Requestify **creates its own reward** through Helix and owns it; it does not reuse a
  reward the streamer made by hand.
- Chat replies are sent as the **broadcaster's own account** (Helix `POST /chat/messages`).
- Channel Points and Bits require Affiliate/Partner. For other channels, those sources are shown
  as unavailable, and the chat command still works.
- Required scopes (builder to finalise): `channel:read:redemptions`,
  `channel:manage:redemptions`, `bits:read`, `user:read:chat`, `user:write:chat`.

### StreamElements
- Live tips over the **Astro WebSocket gateway** (`wss://astro.streamelements.com`), topic
  `channel.tips`, authenticated with the streamer's JWT/OAuth token pasted from the SE dashboard.
- Signal-Catchall's `StreamElementsClient.ts` uses the older socket.io realtime endpoint
  (`realtime.streamelements.com`). Use it as a reference for parsing tips and handling errors, but
  build Requestify on Astro, which StreamElements' docs now present as the current gateway.

### DonoLink
- REST API at `https://donolink.nl/api/v1`, bearer API key created in the DonoLink dashboard,
  scope `activity:read`. **Keys expire** after 30, 90 or 365 days.
- **No push:** poll `GET /activity?platform=donolink&type=donation&order=asc&since=<recorded_at>`
  every 5 s (12 of the 120 req/min allowed). `GET /me` returns owner + key expiry.
- All API facts and a tested reference implementation are in Signal-Catchall:
  `docs/superpowers/specs/2026-09-11-donolink-ingest-design.md`,
  `src/ingest/DonolinkClient.ts`, `src/contract/parseDonolink.ts` (+ tests).

### Distribution
- Free and open source (MIT, like CastIRL), on GitHub, installer + automatic updates from
  GitHub Releases.
- No secrets in the binary: Spotify Client ID is the streamer's own; Twitch is a public client
  (Client ID only); SE and DonoLink are user-pasted tokens.
- Windows only for v1.

## Approaches considered

| | Approach | Trade-offs |
|---|---|---|
| **A** | **Kotlin + Compose Multiplatform Desktop** (chosen) | Real Material 3 Expressive APIs (`MaterialExpressiveTheme`, expressive `MotionScheme`, button groups, wavy progress, shape morphing; experimental in Compose Multiplatform 1.9+). Dynamic colour from album art via MaterialKolor, same algorithm as Android. Coroutines suit long-lived connections. Same stack as CastIRL. **Cost:** port the Twitch/SE/DonoLink clients from TypeScript to Kotlin (Catchall's tests serve as the spec). ~80–100 MB installer (bundled JRE), ~250 MB RAM. Auto-update needs a packaging tool (e.g. Conveyor) or a small updater. |
| B | Electron + React + TypeScript | Reuses Catchall's clients almost 1:1. Most mature installer/auto-update/tray tooling. **But** no Material 3 Expressive library exists for the web; every component and motion would be hand-built, with a real risk of it not feeling genuine. Similar size and RAM to A. |
| C | Tauri + React + TypeScript | ~10 MB, frugal. Same Expressive problem as B, and connections must run either in Rust (new language) or in the webview, which Windows throttles while the window is in the tray. That conflicts with reliability. |

Dropped early: Flutter (no Material 3 Expressive in the core framework for now), WinUI/.NET
(Fluent design, not Material).

**Chosen: A**, by the user. Reason: the two non-negotiables are genuine Material 3 Expressive and
reliability. A is the only route where Expressive is native rather than imitated, and the team
already knows the stack from CastIRL.

## Decisions (made in the brainstorm)

### Inputs

| Source | How a viewer requests | Track input | Paid | Notes |
|---|---|---|---|---|
| Twitch chat command | `!sr <link or search text>` | link **or** search text | no | Who may use it: setting (everyone / followers / subs / VIP / mods), default everyone. |
| Twitch Channel Points | Redeem the Requestify reward, type in the input box | link **or** search text | no | Reward created and owned by Requestify. Name and cost configurable. Redemption lifecycle below. |
| Twitch Bits | Cheer with a Spotify link in the message | link only (*builder's call*, see below) | yes | Minimum Bits setting. |
| StreamElements tip | Tip with a Spotify link in the message | link only | yes | Minimum amount setting. |
| DonoLink donation | Donate with a Spotify link in the message | link only | yes | Minimum amount setting. |

- Every source can be switched on/off independently. Spotify is required; all others optional.
- Search text uses Spotify search and takes the best track match. A viewer who got the wrong song
  removes it themselves with `!wrongsong`.
- *builder's call:* **Bits are link-only**, like donations. Paid requests cannot be refunded, so a
  wrong search match costs the viewer money. Links eliminate that.
- Link parsing accepts `open.spotify.com/track/<id>` (with or without `intl-xx/` and query
  string) and `spotify:track:<id>`. Non-track links (album, playlist, episode) are rejected with a
  reason.

### One request, one set of rules

Every input becomes the same `Request`: who (Twitch identity or donor name), source, track, paid
or not (amount/currency or Bits), timestamp, and a source reference (e.g. redemption id for
refunds). The rules engine then decides accept/reject and position.

**Rules and defaults** (a streamer should never need to change these):

| Rule | Default |
|---|---|
| Max queue length | 25 |
| Max requests per viewer in queue at once | 2 |
| Cooldown per viewer | off |
| No duplicates: already in queue, or played in the last N minutes | on, N = 60 |
| Max track duration | 7:00 |
| Min track duration | 0:30 |
| Block explicit tracks | off |
| Blacklist | tracks, artists, viewers |
| Paid requests go first | **on** (setting) |
| Requests open/closed | automatic: open while live, closed while offline; big switch on Home overrides |

**Paid priority:** when on, paid requests sit before all free requests; within each group,
first come, first served. Manual reordering by the streamer overrides this.

**Paid requests and the rules.** The rules fall into two kinds:
- **Limits** (queue full, per-viewer limit, cooldown, duplicates): paid requests **skip** these.
  The user decided this for the first three. *builder's call:* duplicates count as a limit too, so a
  paid request for a song already queued or recently played still goes through.
- **Content filters** (blacklist, min/max duration, block explicit): **always apply**, paid or
  not. The user decided this for blacklist and duration. *builder's call:* the explicit filter counts
  as a content filter too.

A rejected paid request:
- gets a chat reply with the reason,
- lands in the **Geweigerd (Rejected)** tab with a notification badge, so the streamer can add it
  anyway or leave it.

*builder's call:* a Bits cheer or donation **with a Spotify link but below the minimum** is treated
as a rejected paid request (reason "below minimum"): Rejected tab + chat reply. A cheer or
donation without a Spotify link is not a request and is ignored.

**Free requests rejected:** chat reply with the reason; Channel Points are refunded
automatically.

**Channel Points redemption lifecycle** (*builder's call*): Twitch can only refund an
**unfulfilled** redemption, so a redemption stays `UNFULFILLED` while its request waits in the
queue. It becomes `FULFILLED` when the request is handed to Spotify. It is `CANCELED` (points
refunded) when the request is rejected, or removed before playing (`!wrongsong`, the X button,
`!remove`, the end-of-stream clear).

### Live status and stream lifecycle (user's decisions)

- **Automatic open/close:** going live opens requests, and the queue plays. Going offline closes
  requests: the Channel Points reward is paused on Twitch, `!sr` answers that requests are
  closed, and **nothing is handed to Spotify**, so the streamer's private listening is untouched.
  The Home switch is a manual override in both directions (e.g. open while offline for a test or a
  listening party, which also lets the queue play).
- **End of stream:** **free requests are cleared** (chat and Channel Points; Channel Points are
  refunded). **Paid requests stay** in the queue for the next stream.
- **Paid requests while closed** (offline, or the app was off and catches up later): they **go into
  the queue** with paid priority and play once requests open again. They are not rejected for
  being closed. The content filters still apply.

### Missed events (app off, asleep, crashed, or disconnected)

Anti-goal: losing a paid request silently. *builder's calls*, per source:
- **DonoLink:** persist the `since` cursor across restarts. On startup, resume from the saved cursor
  (catch up). Only the very first connection starts at "now". This deliberately differs from
  Signal-Catchall, which starts at `now()` on every connect.
- **Channel Points:** pause the reward on a graceful exit. On startup (and after a reconnect), fetch
  the reward's `UNFULFILLED` redemptions and run them through the rules like new ones. If requests
  are closed at that moment, they are refunded.
- **StreamElements:** on startup and reconnect, backfill tips since the last seen tip through SE's
  REST API if it offers a suitable endpoint (builder to verify). If not, log a gap (below).
- **Bits:** Twitch offers no history. While EventSub was disconnected, or the app was off during a
  live stream, write a **Log** entry "possible missed Bits between X and Y", so a gap is never
  silent.

### Chat commands (names configurable)

Viewers: `!sr <link|text>`, `!song` (current track + requester), `!queue` (next few + own
position), `!wrongsong` (remove own most recent queued request).
Moderators (and broadcaster): `!skip` (skip current), `!remove <position>`, `!blacklist` (skip
current and blacklist that track; *builder's call*: track, not artist; artist blacklisting stays
in the app).

All replies go out from the broadcaster's account, short, in the app language.

### Playback (user chose "option 1")

- The **app owns the queue**. Spotify's own queue is only used to hand over the **next** request
  shortly before the current track ends.
- When the queue is empty, or requests are closed (offline), nothing is handed over, and Spotify
  continues with whatever the streamer was playing (their playlist/album/radio).
- If Requestify crashes or is closed, Spotify keeps playing. The music never stops because of the
  app.
- "Now playing" is read from Spotify; when the playing track matches a handed-over request it is
  shown with its requester and source, otherwise as the streamer's own music.
- **Skip from the app / `!skip`:** if a request is next, hand it over (if not already) and skip,
  so the request starts immediately; otherwise just skip.
- **Blacklist button on Now Playing:** skips and blacklists the current track.
- Once handed over, a request can no longer be pulled back from Spotify. Hand-over therefore
  happens as late as is safe, so the queue stays editable for as long as possible.
- The queue survives app restarts (persisted to disk).

### UI (Material 3 Expressive)

Seen and chosen in mockups (`.superpowers/brainstorm/…/content/`):

- **Home:** layout "B mirrored", like Spotify's own app. Left: the **queue**, with the title
  "Wachtrij", segmented tabs **Wachtrij · n / Gespeeld / Geweigerd (badge)**, a search bar
  ("search or paste a Spotify link") to add tracks yourself, drag handles to reorder, an X to
  remove. Each row shows art, title, artist, source chip (fixed colour per source), requester,
  priority mark (lightning bolt) for paid, duration. Right: **Now Playing** panel: large album
  art, title, artist, requester + source chip, wavy progress indicator, button group (blacklist,
  play/pause, skip). On a narrow window, Now Playing moves above the queue.
- **Requests open/closed** switch on Home.
- **Connection status** always visible (e.g. "4/4 connected"; a warning chip when something
  needs attention, such as "DonoLink key expires in 6 days").
- **Navigation rail:** Home, Koppelingen (Connections), Regels (Rules), Blacklist, Logboek
  (Log), plus Settings. A FAB on the rail to add a track.
- **Colour:** by default the **whole app takes its colour scheme from the current album art**,
  with a smooth transition on every track change and an expressive "pop" of the art. Settings →
  Appearance offers a **custom colour**; with a custom colour, **only the Now Playing panel**
  still takes the album colours. Light/dark follows Windows (default dark in mockups). Source chip
  colours never change.
- **Language:** Dutch and English, chosen from the Windows language, switchable in Settings.

### Setup and reliability

- **First-run setup wizard**, one step per connection, each with a live "test" result:
  - **Spotify:** step-by-step guide to create the developer app (exact redirect URI to copy,
    which API to tick), paste Client ID, log in via browser (PKCE), Premium check, active-device
    check.
  - **Twitch:** device-code login (show code + open browser), detect Affiliate/Partner, create
    the reward.
  - **StreamElements:** where to find the token, paste, test.
  - **DonoLink:** where to create the key, paste, test via `GET /me` (show owner + expiry).
- Starts with Windows (on by default), lives in the system tray, single instance.
- Every connection reconnects on its own with backoff; a network blip never needs a user action.
- Tokens refresh automatically. Only genuinely dead credentials (revoked, expired DonoLink key)
  ask the user for action, and they say exactly what to do.
- DonoLink key expiry is warned about in advance (in the app, and as a Windows notification).
- Secrets are stored encrypted with Windows' own protection (DPAPI / Credential Manager), never in
  plain text.
- **Logboek (Log):** every request with its outcome and reason, plus connection events, kept for a
  limited time.
- Automatic updates from GitHub Releases.

## Reuse from Signal-Catchall

Port to Kotlin, keeping behaviour and test cases (vitest → Kotlin tests):
- `src/ingest/EventSubClient.ts`: WebSocket session, keepalive, reconnect handling.
- `src/ingest/DonolinkClient.ts` + `src/contract/parseDonolink.ts`: poll loop, cursor/seen-set,
  backoff, 401/403 vs 429/503 handling, generation-guarded teardown.
- `src/contract/parseEventSub.ts`, `src/contract/parseStreamElements.ts`: payload parsing.
- `src/auth/twitch*.ts`, `src/ingest/tokenRefresher.ts`: refresh lessons (a network blip must
  not become a dead connection).

## Left to the builder

- Module/package structure, DI choice, persistence format (DataStore, JSON, SQLite).
- Spotify polling cadence and exact hand-over timing, within Spotify's rate limits.
- Updater/packaging choice (Conveyor, check its open-source licence terms; or jpackage + own
  updater) and code signing (an unsigned .exe triggers SmartScreen warnings; consider a free
  OSS signing programme). Flag the choice before release.
- Custom title bar vs native window frame.
- Log retention period, exact chat reply texts, motion specs, icon and branding.
- Font choice within Material 3 Expressive (e.g. Roboto Flex).
