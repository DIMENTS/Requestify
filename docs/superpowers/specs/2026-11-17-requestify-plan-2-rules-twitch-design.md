# Requestify plan 2: rules + Twitch (design)

Date: 2026-11-17
Status: approved by the user in chat on 2026-11-17
Builds on: `2026-11-16-requestify-design.md` (the main spec; everything there still holds unless this
document says otherwise) and `2026-11-17-requestify-playback-ui-additions-design.md`.
Author: DIMENTS

## Intent and why

Plan 1 made Requestify play a queue the streamer fills by hand. Plan 2 makes it what it is for:
**viewers request songs through Twitch** (chat `!sr`, Channel Points, Bits). One set of rules decides
what gets in, and the app opens and closes requests with the stream on its own. The bar is the main
spec's: set it up once and never look at it again. Nothing gets lost silently, least of all a paid
request or a viewer's Channel Points. A blacklisted or over-long song must never play because some
path skipped the rules.

## Goals

- Log in to Twitch once with a device code (public client, no secret). The login keeps itself fresh.
  A login that really died asks for one click.
- Chat requests (`!sr`), viewer commands (`!song`, `!queue`, `!wrongsong`) and mod commands (`!skip`,
  `!remove`, `!blacklist`) work, with short replies in chat from the broadcaster's account.
- A Channel Points reward that Requestify creates and owns. Every redemption ends up fulfilled or
  refunded, also across crashes, restarts and network blips.
- Bits cheers with a Spotify track link become paid requests.
- One rules engine for every Twitch source. Its decisions are visible: the Rejected tab, and replies
  that give the reason.
- Requests follow the stream: open when live, closed when offline. A switch in the title bar
  overrides this. A short drop does not wipe the queue.
- Rules and Blacklist screens. Blocking from Home (Now Playing and queue rows).
- The plan-1 follow-ups the final reviews deferred to this plan (see "Follow-ups folded in").

## Non-goals (plan 2)

- StreamElements and DonoLink (plan 3), including their minimums and catch-up.
- Gap logging for Bits ("possible missed Bits between X and Y"). It moves to plan 3 together with
  the other sources' gap logging and the Log it is written to. Plan 2 does catch up Channel Points,
  because their refunds depend on it.
- The Log screen, tray, autostart, the full first-run wizard, Windows notifications (plan 4).
- A mod panel, reorder commands, viewer voting (main spec non-goals).
- Kick/YouTube.

## Anti-goals (failure even if it technically works)

- Two `!sr`s that arrive at the same moment both pass a limit that only one of them should pass.
- A viewer loses Channel Points for a request that never played (rejected, removed, cleared, or a
  refund that failed once and was never retried).
- A paid request disappears without trace: Spotify unreachable at that moment, an album link, below
  the minimum, or from a blocked viewer.
- A blacklisted song plays because it was queued before it was blacklisted.
- A stream that drops for a minute clears the free requests.
- The streamer has to re-login to Twitch every stream, or reconnect by hand after a network blip.
- A troll gets an audience: every blocked request answered in chat, or the Rejected tab flooded.

## Constraints

### Twitch (checked against dev.twitch.tv on 2026-11-17, changelog up to 2026-11-10)

The verified details (endpoints, fields, error shapes, doc links) are in
`2026-11-17-twitch-api-notes.md` next to this file. This section keeps only what shapes the design.

- **Twitch application:** registered by DIMENTS in the Twitch developer console as Client Type
  **Public** (choose it at creation; it may not be changeable later). Its Client ID is not a secret
  and lives in the source as a constant: `wu2hg6lvxw5lqb2qbs046ou19nupnp` (registered by the user on
  2026-11-17: OAuth redirect URL `http://localhost`, which is unused; category "Application
  Integration"). A public client has
  no app token, so **everything runs on the broadcaster's user token**, and EventSub uses WebSocket.
- **Login:** Device Code Grant Flow.
  - `POST https://id.twitch.tv/oauth2/device` gives a user code, an expiry and a polling interval.
    Its `verification_uri` already carries the code, so "Open browser" opens it directly.
  - Polling is `POST https://id.twitch.tv/oauth2/token` with grant type
    `urn:ietf:params:oauth:grant-type:device_code`. Errors come as `{"status":400,"message":…}`.
    Only `authorization_pending` means keep polling; any other answer ends the attempt. The app
    stops by itself when the code expires.
  - **Refresh tokens rotate (single use).** Persist the newest one atomically before using the new
    access token, and never run two refreshes at once, as for Spotify. The session dies after 30
    days without a refresh, so a streamer who does not run the app for longer must log in again.
  - A dead refresh token answers 400 "Invalid refresh token" or 401. Anything else (network, 5xx)
    is transient: keep the tokens and retry.
  - A Helix 401 triggers one refresh, then the call is retried.
  - The access token is validated at startup and every hour (`GET /oauth2/validate`); Twitch
    requires this and audits it.
- **Scopes:** `user:read:chat user:write:chat channel:manage:redemptions bits:read
  moderator:read:followers`.
  - The last one is needed only for the "followers" level of `!sr`. It is requested up front, so
    changing that setting never needs a new login. Without it, Twitch silently answers "not a
    follower" for everyone.
  - Stream status and user lookups need no scope.
- **EventSub over WebSocket** (`wss://eventsub.wss.twitch.tv/ws`), one connection.
  - **Subscriptions:** created right after `session_welcome`, within its keepalive timeout (10 s by
    default):
    - `channel.chat.message`;
    - `channel.channel_points_custom_reward_redemption.add`, filtered on the app's reward id;
    - `channel.channel_points_custom_reward.update` and `.remove`, for the app's reward;
    - `channel.cheer`;
    - `stream.online` and `stream.offline`.
  - **Responses:** a subscription is created with 202. A 409 (already exists) counts as success. A
    401 means the login is dead, not a reason to reconnect.
  - **Keepalive:** a keepalive timeout means reconnect.
  - **Reconnecting:**
    - On `session_reconnect`, connect to the new URL before closing the old one; the subscriptions
      move with it, so **do not** resubscribe.
    - On a fresh reconnect, resubscribe and re-sync over REST (live status, Channel Points
      catch-up). Twitch does not replay events missed while disconnected.
    - A `revocation` with `authorization_revoked` means the streamer disconnected the app: the
      login is dead.
  - **Duplicates:** notifications can arrive twice, so they are de-duplicated on
    `metadata.message_id`.
- **Bits:** `channel.cheer` (not also `channel.bits.use`, which would count every cheer twice). It
  carries `is_anonymous`, the Bits, and the message text including the cheermote (e.g. "Cheer100
  https://open.spotify.com/track/…").
- **Live status at startup and after a fresh reconnect:** `GET /helix/streams?user_id=` with the
  user token.
- **Chat replies:**
  - `POST /helix/chat/messages` as the broadcaster (`sender_id` = broadcaster), as a reply to the
    viewer's message (`reply_parent_message_id`). At most 500 characters.
  - A 200 can still mean "dropped" (`is_sent: false`, e.g. AutoMod); that is logged and not retried.
  - The app's replies come back as chat events from the broadcaster. They are recognised by the
    message ids the send call returned and ignored. The broadcaster's own `!sr` still counts.
- **Channel Points:** only the client that created a reward may update it and its redemptions.
  - The app therefore creates its own reward (`POST /helix/channel_points/custom_rewards`, user
    input required, redemptions not skipping the request queue) and never adopts one made by hand.
  - The title must be unique among all the channel's rewards; a duplicate is a plain 400.
  - Updating a redemption's status (`PATCH …/redemptions?id=&broadcaster_id=&reward_id=`) answers
    404 when it is no longer unfulfilled: already settled, or gone.
  - Deleting a reward makes Twitch mark all its unfulfilled redemptions fulfilled; they can no
    longer be refunded.
  - Channel Points and Bits exist only for Affiliates and Partners (`GET /helix/users` →
    `broadcaster_type`; a reward call for other channels answers 403). For other channels those two
    sources show as unavailable, and chat requests still work.
- **Mods, VIPs, subscribers** are recognised from the chat message's `badges` (not
  `source_badges`):
  - `broadcaster`;
  - `moderator`, and `lead_moderator`, which replaces `moderator` for lead mods since 2025-12;
  - `vip`;
  - `subscriber`, and `founder`, which replaces `subscriber` for founders.

  "Follower" needs `GET /helix/channels/followers?broadcaster_id=&user_id=`. The answer is cached
  per viewer for the session.
- **Shared chat:** only messages from the broadcaster's own channel count. A message whose source
  channel is another broadcaster is ignored.

### Existing app (plan 1 + playback additions)

- `PlaybackController` owns the queue, the handed-over request, the playing request and the history.
  It guards them with two mutexes: `spotifyMutex` for Spotify sequences and `stateMutex` for state
  and saves, never held across a network call. Every save is atomic, and a store that cannot be
  read is never overwritten.
- `requestsOpen` is already an input of the controller, hard-wired to `true` in `AppGraph`. Closed
  means nothing is handed over.
- Persisted JSON stays forward compatible: new fields get defaults, and `AppJson` ignores unknown
  keys. `Payment` and other saved sealed types keep their stable `@SerialName`s.
- Every user-facing string, chat replies included, lives in Compose resources in English and Dutch.
  Chat replies use the app language.

## Approaches considered

| | Approach | Trade-offs |
|---|---|---|
| **A** | **One intake in front of the queue** (chosen) | Every source turns its input into a `Request` and hands it to one `RequestIntake`. The rules engine is pure code. The controller gets one atomic "decide and add" entry point that runs the rules on its state under `stateMutex`, so two requests at once can never both pass a limit, and it returns the outcome (position or reason) for the chat reply. The controller reports request lifecycle events (started playing, removed) that the Channel Points side turns into fulfil/refund. The proven hand-over logic stays untouched. |
| B | Rules inside `PlaybackController` | Least wiring. But the controller (762 lines) grows further, against the final review's follow-up, and rules would mix with Spotify timing. |
| C | Split the queue out of the controller (two owners) | Cleanest long term. But hand-over and "back to the head when Spotify lost it" would have to cross two locks. That is a big intervention in the logic that now runs stably against real Spotify. |

**Chosen: A**, by the user.

## Decisions

### Architecture (builder's calls within approach A)

- **`core/rules`:** `Rules` (the settings values), `RuleEngine.decide(request, context) → Decision`,
  a pure function. `context` is a snapshot of queue, handed-over, playing, history, the per-viewer
  cooldown memory, the blacklist and "requests open".
- **`PlaybackController.submit(request, decide)`** runs `decide` under `stateMutex` on the loaded
  state and adds the request only when the decision accepts it. It returns the outcome: accepted
  with its 1-based queue position and the wait until it plays, or rejected with the reason. It waits
  for the saved state to be loaded, like every edit. The existing fire-and-forget `enqueue` stays
  for the streamer's own adds.
- **Lifecycle events** from the controller, as a `SharedFlow` (replay-free, buffered, nothing
  dropped):
  - `Started(request)`, when Spotify confirms the request is playing;
  - `Removed(request, why)`, where `why` is one of: the streamer's X, `!wrongsong`, `!remove`, the
    end-of-stream clear, or moved to Rejected by a blacklist sweep.
- **`core/intake/RequestIntake`:**
  - resolves the input to a track (link → `track(id)`, text → best search match);
  - calls `submit`;
  - records rejections in the **Rejected list**;
  - keeps the per-viewer cooldown memory;
  - returns the outcome to the source.
- **`core/twitch`:**
  - `TwitchAuth` (device code, refresh, validate);
  - `TwitchApi` (Helix calls, typed results like `SpotifyResult`);
  - `EventSubClient` (port of Signal-Catchall's session handling: welcome, keepalive, reconnect,
    revocation, backoff);
  - `TwitchChat` (command parsing, permission check, replies through a sequential sender);
  - `ChannelPoints` (reward lifecycle, redemption lifecycle, outbox, catch-up);
  - `StreamStatus` (live state, open/closed, offline grace).
- **`core/blacklist`:** `Blacklist` (tracks, artists, viewers) in its own crash-safe JSON store.
- Wiring stays manual constructor injection in `AppGraph`.

### Who asked: viewer identity

- `Requester` gets `twitchUserId: String? = null` (default keeps old files readable). Per-viewer
  limits, cooldown, `!wrongsong`, `!queue` positions and the viewer blacklist match on the Twitch user
  id, never on the name, so a renamed viewer is still the same viewer.
- `Track` gets `artistIds: List<String> = emptyList()`, filled from Spotify's track object, so artist
  blacklisting matches on id. Tracks saved before this have no ids and are matched on artist name.

### The rules (main spec table; defaults unchanged)

| Rule | Kind | Default | Control on the Rules screen |
|---|---|---|---|
| Max queue length | limit | 25 | stepper 1–100 |
| Max requests per viewer waiting at once (queued + handed over) | limit | 2 | stepper 1–10 |
| Cooldown per viewer (since their last accepted request) | limit | off | stepper off, 1–60 min |
| No duplicates: already queued, handed over or playing, or played in the last N minutes | limit | on, 60 min | switch + stepper 15–240 min |
| Min track duration | content | 0:30 | stepper 0:00–2:00, steps of 0:15 |
| Max track duration | content | 7:00 | stepper 2:00–15:00, steps of 0:30 |
| Block explicit tracks | content | off | switch |
| Blacklist (tracks, artists, viewers) | content | empty | Blacklist screen |
| Paid requests go first | ordering | on | switch (moves from Settings to Rules) |

- **Paid requests skip the limits. Content filters always apply** (main spec). Requests being
  closed is a limit too: paid requests while closed go into the queue and play once requests open.
- **Order of checks**, first failure gives the reason:
  1. input (no track link for a link-only source, not a track, no search match, track unplayable);
  2. viewer blacklisted;
  3. track blacklisted;
  4. artist blacklisted;
  5. explicit;
  6. too short;
  7. too long;
  8. then for free requests only: requests closed, queue full, per-viewer limit, cooldown,
     duplicate.
- **The streamer's own adds skip every rule** (user confirmed). This covers the Home add field and
  "Add anyway" from the Rejected tab.
- **Rules apply when a request comes in** (user's choice). Tightening a rule (explicit on, a lower
  maximum length, …) leaves requests already queued alone. Clicking a stepper never triggers a run
  of refunds; the streamer removes single songs by hand. The blacklist is the one exception (below),
  because blocking is a deliberate act on one item.
- **Cooldown memory** (*builder's call*): kept in memory only, so it resets when the app restarts. A
  cooldown is about pacing within a stream, not across restarts.
- **Blacklist sweep** (*builder's call*, for the "queued before it was blacklisted" anti-goal): when
  a track, artist or viewer is added to the blacklist, queued viewer requests that match move to the
  Rejected list with reason "on the blacklist". Free ones are refunded; paid ones wait for the
  streamer. The streamer's own adds and added-anyway requests are left alone. The request already
  handed to Spotify cannot be pulled back (known limitation).

### Resolving the track (*builder's calls*)

- `!sr` and Channel Points take a link or search text. Search takes Spotify's best track match
  (limit 1–5, first playable result). Bits are **link-only**; a cheer without a Spotify link is not a
  request and is ignored. A cheer with a non-track Spotify link (album, playlist) is a rejected paid
  request.
- **Spotify unreachable while resolving:**
  - a free request is rejected with "Spotify is not reachable, try again in a moment" (Channel
    Points refunded);
  - a **paid** request is never rejected for this. It waits in a persisted "to resolve" list and is
    retried with backoff until Spotify answers, then goes through the rules as usual.
- A bot or viewer who pastes a link to a track that cannot be played in the streamer's country is
  rejected with that reason, if Spotify's track object says so (builder to verify the field after
  the February 2026 API changes).

### Requests open/closed and the stream

States and transitions (user's decisions plus confirmed *builder's calls*):

- **Twitch connected:** requests follow the live status.
  - `stream.online` → open.
  - `stream.offline` → **closed at once**: the Channel Points reward is paused, `!sr` answers that
    requests are closed, and nothing is handed to Spotify.
  - The live status is read at startup (`GET /helix/streams`) and again after every EventSub
    reconnect, so a change missed while disconnected is caught.
- **Manual override:** the title-bar toggle flips open/closed. The override lasts until the stream
  next **really** starts or ends. After that, Requestify follows the stream again. The override
  survives an app restart.
- **Offline grace (user's choice):** the end-of-stream clear runs only after **10 minutes** without
  the stream coming back. Going live again within that time cancels it and everything carries on.
  The offline moment is saved, so a restart during the grace still clears at the right time. If the
  10 minutes have already passed, it clears at once on startup.
- **A short drop counts as nothing happened** (*builder's call*, following the user's choice):
  - During a drop (offline, then online again within the grace) requests are closed, so nothing is
    handed over.
  - On return, the state from before the drop comes back, manual override included. A streamer who
    closed requests by hand does not see them reopen because OBS hiccuped.
  - The stream counts as ended only when the grace runs out. Only then does an override end.
- **End-of-stream clear:** removes queued **free viewer requests** (chat and Channel Points; Channel
  Points refunded). **Paid requests and the streamer's own adds stay** (user confirmed). The Rejected
  list is untouched.
- **Opening by hand after the stream postpones the clear** (user's choice). The streamer opens
  requests while offline to let the queue play out. The free requests stay and play for as long as
  requests are open. When the streamer closes them again while still offline, the clear runs at once.
  Going live again in the meantime cancels it, as during the grace.
- **Twitch not connected (or logged out):** the toggle is a plain switch whose state Requestify
  remembers, **default open** (keeps plan 1's behaviour for streamers who only add songs themselves).
- **Re-check (follow-up):** with real open/closed, verify that requests still play before Spotify's
  own upcoming tracks when requests reopen mid-song.

### The Rejected list ("Geweigerd" tab)

- Holds every rejected request: free and paid, every source, with requester, source chip, the reason
  in the error colour, and the track when one was resolved. Each row has **"Add anyway"** and
  **"Dismiss"** (user's choice A: one list, all rows alike).
  - "Add anyway" queues it as the streamer's own decision (no rules), at the position its kind gets
    (paid priority still orders it).
  - A row without a resolved track (not a track, no match) only offers "Dismiss".
- **Badge:** the number of **paid** rejected requests still in the list (not yet added or
  dismissed).
- **Kept:** persisted, at most 50 entries. When full, the oldest free entry goes first; a paid entry
  is never dropped automatically.
- **Not listed** (*builder's calls*, against troll floods):
  - free requests from **blocked viewers**: no chat reply, no Rejected entry, but their Channel Points
    are refunded;
  - `!sr` from viewers below the permission level: they get a short reply, but no entry.

  Paid requests from blocked viewers are listed, because money is involved.
- Rejecting a free Channel Points request refunds it at once. Adding it anyway later does not
  charge points again.

### Blacklist

- **Tracks** by Spotify id, **artists** by Spotify id (and name), **viewers** by Twitch user id (and
  login for display). Each entry records when, by whom (the streamer, or `@mod via !blacklist`) and
  from where.
- **Adding from Home** (user's choice B: a small menu, so nothing lands on the blacklist by
  accident):
  - The Now Playing **block button** opens a menu: "Block song and skip", "Block artist and skip",
    "Block @requester" (only for a request from a Twitch viewer).
  - Every queue row's **⋮ menu** has: Remove, Block song, Block artist, Block viewer. Blocking from a
    row also removes that request (refunded when free).
- **Blacklist screen** (user's choice A): segmented tabs **Songs · n / Artists · n / Viewers · n**.
  - The add field adapts to the tab: song search or link, artist search or link, Twitch name.
  - The list uses the queue's connected-row style with "blocked by … · date" and an X to remove.
  - A Twitch name is looked up to its user id (`GET /helix/users?login=`) when added; this needs
    Twitch connected.
- Mods' `!blacklist` blocks the **current track** and skips it (main spec).

### Chat commands

Names are configurable (Twitch card → Commands); defaults below. Replies are short, in the app
language, sent as a reply to the viewer's message. The builder writes the texts.

| Command | Who | Does | Reply |
|---|---|---|---|
| `!sr <link or text>` | permission setting: everyone (default) / followers / subscribers / VIPs / moderators; higher roles always may | resolves, runs the rules | added at position N (≈ wait), or the reason; "requests are closed" while closed; a usage hint without input |
| `!song` | everyone | — | current track + requester, or "the streamer's own music" |
| `!queue` | everyone | — | the request Spotify plays next, then the next few numbered positions, plus the viewer's own position(s); within 500 characters |
| `!wrongsong` | everyone | removes the viewer's most recent **queued** request (not one handed over); Channel Points refunded | which song was removed, or that there is none |
| `!skip` | moderators, broadcaster | as the app's skip (a request next is handed over first) | which song was skipped |
| `!remove <n>` | moderators, broadcaster | removes queue position n as numbered by `!queue`; refunded when free | which song was removed, or "no position n" |
| `!blacklist` | moderators, broadcaster | blocks the current track and skips it | confirmation |

- Commands are matched case-insensitively at the start of a message. The app's own replies never
  start with `!`, so they are never read back as commands.
- Replies go out one at a time through a small sender that backs off when Twitch rate-limits. A
  reply that cannot be sent is dropped after a few tries (chat is ephemeral; the request itself is
  unaffected).

### Channel Points

- **Which reward (user's choice: scan, copy, or link directly).** When Channel Points is switched
  on, the Twitch card offers a choice, with "Create new reward" first:
  - **Create new reward:** Requestify creates its own. Defaults (*builder's calls*):
    - title "Song request" / "Nummerverzoek" (in the app language);
    - cost 500;
    - user input required, with the prompt "Spotify link or song name";
    - redemptions do not skip the request queue.
  - **The channel's existing rewards** (scanned with `GET /helix/channel_points/custom_rewards`), in
    two groups:
    - **Made by Requestify** (Twitch reports them as manageable by this app, e.g. from before a
      reinstall or on another PC): linked again directly, with the full lifecycle below. No
      duplicate is created.
    - **Made by hand or by another app.** Each offers two actions:
      - **"Copy as Requestify reward"** (recommended in the UI): creates Requestify's own reward
        with the same title, cost and prompt. Twitch requires unique titles, so the app first asks
        the streamer to remove or rename the old one in the Twitch dashboard. The title field stays
        editable, so a different name also works.
      - **"Link directly"**, behind a warning that lists exactly what does not work (below). The
        Twitch card keeps showing "Linked without management: Requestify cannot refund points", with
        "Copy as Requestify reward" to switch later.
- **Name, cost and prompt** of Requestify's own reward are editable on the Twitch card and written to
  Twitch at once. A duplicate title is shown as a field error. Changes made in the Twitch dashboard
  are taken over (see "Reward edited on Twitch").
- **A directly linked reward that Requestify did not create** (*Twitch's limits*, verified):
  Requestify only receives its redemptions (EventSub) and queues them through the rules like any
  other. Twitch lets only the creating app manage a reward, so:
  - **No refunds and no fulfil.** Points of rejected, removed or cleared requests are not returned;
    the streamer can do that by hand in the Twitch dashboard. Redemptions stay in Twitch's request
    queue unless that reward skips it.
  - **Not paused when requests close.** Redemptions while closed are rejected with a chat reply, and
    their points are not refunded.
  - **No catch-up.** Redemptions made while Requestify was off or disconnected are missed.
  - **Read-only settings.** Title, cost and prompt are shown read-only; they are edited in the Twitch
    dashboard.
  - Rejected-tab rows and chat replies for such requests never claim a refund.
  - The outbox is not used for it.

  Everything below about pausing, the lifecycle, the outbox and catch-up applies to Requestify's own
  reward.
- **Paused** whenever requests are closed and on a graceful app exit. Unpaused when they open.
- **Redemption lifecycle** (*builder's call*, refining the main spec): a redemption stays
  `UNFULFILLED` while its request waits.
  - It becomes `FULFILLED` when the request **starts playing**, as Spotify confirms (the `Started`
    event), not at hand-over. A handed-over request can still return to the queue when the streamer
    clears Spotify's queue, and must stay refundable until then.
  - It becomes `CANCELED` (refund) when the request is rejected or removed before playing (X,
    `!wrongsong`, `!remove`, blacklist sweep, end-of-stream clear), and when it arrives while
    requests are closed.
- **Outbox:** every status change goes into a persisted outbox first and is retried with backoff
  until Twitch accepts it. A redemption Twitch says is already settled (or gone) is dropped from the
  outbox. A dead login waits for the next login.
- **Catch-up** at startup and after every EventSub reconnect: read the reward's `UNFULFILLED`
  redemptions and treat each by what the app knows of it:
  - queued, handed over or in the outbox: leave it;
  - playing or in history: fulfil;
  - unknown: a new redemption, run through the rules (refunded if requests are closed).
- **Switching Channel Points off in the app** never deletes the reward (*builder's call*). It
  disables it on Twitch (`is_enabled = false`, hidden from viewers), so redemptions still waiting
  stay refundable and their lifecycle runs on. Switching it on again re-enables the same reward.
- **Reward deleted on Twitch by the streamer** (*builder's call*): Twitch then marks every unfulfilled
  redemption as fulfilled itself, so those points cannot be refunded any more.
  - Queued Channel Points requests stay in the queue as ordinary free requests. Their outbox entries
    are dropped when Twitch reports the redemption gone.
  - The reward is not recreated automatically. The Twitch card shows "The reward was removed on
    Twitch" with a "Create again" button, which makes a new reward (new id).
  - Requestify notices this through `channel.channel_points_custom_reward.remove`, and otherwise at
    the next catch-up.
- **Reward edited on Twitch** (`.update`): a new title or cost is taken over into the app's settings.
  The paused/enabled state stays the app's own, and is written back when it differs.
- **Channel without Affiliate/Partner:** the Channel Points and Bits rows are disabled with an
  explanation.

### Bits

- `channel.cheer` with a Spotify **track link** in the message and at least the **minimum** (default
  100 Bits, *builder's call*) is a paid request (`Payment.Bits`).
- Below the minimum, with a track link → a rejected paid request ("below the minimum of N Bits"):
  Rejected list + chat reply.
- An anonymous cheer is shown as "Anonymous" and gets no chat reply addressed to a name.

### Twitch login and connection

- **Twitch card** on Connections (user's choice A: one card):
  - Not logged in: a "Log in with Twitch" button. While logging in: the user code shown large with
    "Copy" and "Open browser" (`twitch.tv/activate`), and an Expressive loading shape while waiting,
    with "Cancel". The code's expiry is shown as an error when it runs out.
  - Logged in: the account (name, Affiliate/Partner tag), "Log out", then one row per source with its
    main setting and an on/off switch:
    - Chat: `!sr` permission level.
    - Channel Points: which reward (create, choose from the scan, copy, link), its name and cost
      (read-only for a directly linked one), and its status (e.g. "Paused: stream is offline", or
      "Linked without management").
    - Bits: the minimum.
  - A collapsible **Commands** row holds the command names.
- **Dead login:** a refresh token Twitch rejects (expired after 30 days, or revoked) shows "Twitch:
  log in again" in the status chip and on the card. One click starts the device flow; nothing else
  is lost (reward, settings, blacklist stay). A network error is never a dead login: it retries with
  backoff.
- **EventSub reconnects on its own** with backoff (1 s doubling to 30 s). After a reconnect it
  re-subscribes, re-reads the live status and runs the Channel Points catch-up. The status chip
  shows a problem only when the connection has been down for more than 30 s (*builder's call*, so a
  routine reconnect is invisible).

### UI summary (from the mockups, user's choices)

- **Title bar** (choice C): the status chip, then on the right an **Expressive toggle button** for
  requests:
  - with Twitch: "Live · requests open" / "Offline · requests closed", and in an override
    "Offline · requests open" / "Live · requests closed";
  - without Twitch: "Requests open" / "Requests closed".

  It changes shape and colour when it flips. A tooltip says why it is in that state and, during the
  offline grace, when the free requests will be cleared.
- **Status chip** (one chip): "All connected" while every configured connection is fine. Otherwise it
  shows the one item that needs attention, most important first. Spotify: not connected, login
  needed, Premium required, no active device, unreachable, rate limited. Twitch: login needed,
  disconnected for more than 30 s, reward removed. A click opens Connections. "Nothing playing" is
  not a connection problem; the Now Playing card already shows it.
- **Queue pane:** tabs **Queue · n / Played / Rejected (badge)**; Rejected rows as above.
- **Rules screen** (choice A): one scrolling page in groups ("Queue", "Songs"), with an info note on
  top: "Paid requests skip the limits. Blacklist, length and explicit always apply."
- **Navigation rail:** Home, Connections, Rules, Blacklist, Settings (Log follows in plan 4).

### Follow-ups folded in (from the plan-1 final reviews)

1. `window.minimumSize` scales with the screen's density (at 150% it is now too small).
2. Before adding more: extract the last-played/upcoming bookkeeping out of `PlaybackController`, and
   the row composables out of `QueuePane.kt`.
3. Near-end polling (1 s) only while Spotify is actually playing.
4. At startup, a handed-over request that played while the app was closed is settled from Spotify's
   recently played (to history, not back to the head), so it does not play twice.
5. `requestsOpen` becomes real (above).

### Testing and review focus

- Two `!sr`s from the same viewer at the same moment with the per-viewer limit at 1: exactly one is
  accepted.
- A paid request is never lost: Spotify unreachable (waits, then resolves), album link (Rejected),
  below the minimum (Rejected), blocked viewer (Rejected), requests closed (queued).
- Channel Points:
  - a refund that fails is retried after a restart (outbox persisted);
  - catch-up settles the redemptions of requests that played, queued or were removed while the app
    was down;
  - a request that returns from Spotify's queue to the head is still refundable;
  - the scan relinks Requestify's own reward instead of creating a duplicate;
  - a directly linked foreign reward is never paused, refunded or fulfilled, and no reply or row
    claims a refund.
- Offline grace: offline then online within 10 min keeps the free requests. Offline for 10 min clears
  them and refunds. A restart during the grace still clears on time.
- Overrides:
  - a manual close survives a short drop;
  - a manual open after the stream postpones the clear until requests are closed again;
  - a manual open while offline ends when the stream next really starts and ends.
- EventSub:
  - keepalive timeout → reconnect;
  - `session_reconnect` → no gap and no duplicate subscriptions;
  - a duplicate `message_id` is handled once;
  - a 401 on subscribe → login needed, not a reconnect loop.
- Twitch token refresh persists the rotated refresh token before use; a network error keeps the
  login.
- Blacklist sweep moves matching queued viewer requests to Rejected and refunds the free ones.

### Milestones

One plan, one branch, **with a mid-way live test** (*builder's call*):

1. **After** the follow-ups, rules, Rejected tab, Rules and Blacklist screens, the title-bar toggle,
   Twitch login, EventSub chat, `!sr` and the viewer commands: the user tests with their channel.
2. **Then** mod commands, Channel Points, Bits and the live-status lifecycle, followed by the final
   live test.

## Left to the builder

- Exact chat reply texts (en + nl), tooltip texts, motion specs of the toggle button and the menus.
- Package layout inside `core/intake`, `core/rules`, `core/twitch`, `core/blacklist`; how the new
  state is split over files (settings vs. own stores). Requirement: everything listed as "persisted"
  above survives a restart and a corrupt file, like plan 1's stores.
- Re-check each Twitch call against `2026-11-17-twitch-api-notes.md` (and the live docs where the
  notes flag something as undocumented) while coding. Corrections that change behaviour come back to
  the user; others are noted in the plan.
- Whether `spotify.link/…` short links resolve with a plain HTTP redirect. If so, support them; if
  not, reply "use the open.spotify.com link".
- How the "unplayable in your country" check is read from Spotify's current track object.
