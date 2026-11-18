# Requestify plan 3 — donations and the Log (design)

Date: 2026-11-18
Status: written after the brainstorm in chat on 2026-11-18 (builder check done); awaiting the user's approval

Parent design: `docs/superpowers/specs/2026-11-16-requestify-design.md` (sections StreamElements,
DonoLink, Missed events, Logboek). Plan 2 design: `docs/superpowers/specs/2026-11-17-requestify-plan-2-rules-twitch-design.md`.
Everything those documents decided still holds unless this one says otherwise.

## Intent and why

Requestify is free and open source for other streamers too. The user's own viewers donate through
**DonoLink**; other streamers still use **StreamElements**. Plan 3 lets a donation with a Spotify
link become a paid song request from either platform, and adds the **Log (Logboek)**: the place the
streamer opens "to get more information about things that go right or wrong" (user's words).

## Goals

1. **One donation source, chosen by the streamer:** none, DonoLink or StreamElements. A streamer
   never uses both at the same time (user's statement), so the choice is exclusive.
2. A donation whose message holds a Spotify link becomes a **paid request** (paid priority, the
   same rules, Rejected list and "Add anyway" as Bits). Link only; short links (`spotify.link`)
   are followed as in plan 2.
3. A **minimum amount** for the donation source. Below it: a rejected paid request, listed in
   Rejected **with its track** so "Add anyway" works (as Bits after the plan-2 fix wave).
4. **Catch-up:** donations made while the app was off or disconnected are picked up afterwards.
5. **The Log:** every request from every source with what happened to it, connection events,
   stream events, ignored donations, and possible gaps, kept 30 days, filterable and searchable.
6. **Gap logging** ("possible missed Bits between X and Y"), moved here from plan 2.

## Non-goals

- Using DonoLink and StreamElements at the same time; deduplicating between them.
- DonoLink's mirrored events (Twitch follows/subs/Bits, imported StreamElements tips, Kick, Ko-fi):
  only DonoLink's **own** donations (`platform=donolink`, `type=donation`). Requestify gets Bits
  straight from Twitch, so mirrored Bits would count twice.
- Refunding money, or acting on DonoLink's `refunded_amount_cents` (a refunded donation's request
  stays).
- Importing donations from before the source was **first** connected.
- Windows notifications (DonoLink key expiry). They come with the system tray in plan 4; plan 3
  warns inside the app.
- Exporting the Log.
- Kick or any other platform.

## Anti-goals (failure even if it technically works)

- A paid donation request lost silently (the parent design's main anti-goal).
- A donation counted twice (catch-up overlap, reconnect, restart).
- A Log that silently misses things: the streamer opens it precisely to learn what went wrong.
- A Log that grows without bound or slows the queue or the window (writes off the UI thread).
- A chat reply that tags the wrong Twitch user (donor names are free text, not Twitch logins).

## Constraints

- Existing pieces to build on (plan 1 + 2): `RequestIntake` (resolve link, rules, pending.json while
  Spotify is down, RejectedStore), `IncomingRequest`, `Payment.Money(amountCents, currency)`,
  `RequestSource.STREAMELEMENTS / DONOLINK` (source chips and `formatMoney` already exist),
  `BitsSource` as the template for a paid link-only source, `ChatReplySender` (broadcaster account),
  `SecretStore` (Windows DPAPI) for tokens, `ConnectionChip` / `TwitchSession.health` for
  connection health, `AppPaths` for files, the `Destination` enum ("a later plan adds Log").
- **DonoLink** facts and a tested reference implementation are in Signal-Catchall:
  `docs/superpowers/specs/2026-09-11-donolink-ingest-design.md`, `src/ingest/DonolinkClient.ts`,
  `src/contract/parseDonolink.ts` (+ tests). Summary: REST `https://donolink.nl/api/v1`, bearer API
  key with scope `activity:read`, keys expire after 30/90/365 days, `GET /me` gives owner + expiry,
  no push → poll `GET /activity?platform=donolink&type=donation&order=asc&since=<recorded_at>&limit=100`
  every 5 s, paginate with `next_cursor`, `since` is inclusive and uses `recorded_at`, 120 req/min,
  401/403 = key refused, 429/503 carry `Retry-After`. Money is integer cents.
- **StreamElements:** live tips over the Astro WebSocket gateway (`wss://astro.streamelements.com`,
  topic `channel.tips`) with the streamer's JWT pasted from the SE dashboard (parent design).
  Catchall's `StreamElementsClient.ts` / `parseStreamElements.ts` use the older socket.io endpoint;
  use them for tip parsing and error lessons only. The user cannot live-test StreamElements (their
  viewers use DonoLink), so this source is built and tested against SE's documentation and recorded
  fixtures; it must say so honestly in its card if a check cannot be made.
- Twitch is optional. Without a Twitch login, donations still queue; there are just no chat replies.
- One Spotify request path: donations go through the same intake as every other source.

## Approaches considered (for the Log; the user picked A)

| | Approach | Trade-off |
|---|---|---|
| **A** (chosen) | **The Log listens.** One Logbook follows what the app already reports: the intake's outcome for every incoming request, the queue's events (handed over, played, removed, refunded), connection states, the requests gate. Only what nothing reports yet gets its own report (ignored donations without a link, gaps). | One place decides what is logged; a new source is logged automatically because everything passes the intake. Cost: the intake must publish its outcomes as events (small addition). |
| B | Every part writes to the Log itself. | Maximum detail, but ~15 call sites and every later change risks a forgotten one: the Log would silently go wrong, which is exactly what the streamer uses it to find. |
| C | The Log merges the existing lists (Played, Rejected, queue). | Least work, but those lists are capped (Rejected holds 50), keep no 30 days, and hold no ignored donations or connection events. Does not meet "more information". Dropped. |

The donation sources themselves follow the plan-2 Bits pattern; there was one sensible way.

## Decisions

### Donation source (user's decisions unless marked)

- **Connections → one "Donaties / Donations" card** with a choice: Geen · DonoLink · StreamElements.
  Choosing one shows its setup; switching stops the old source (its saved token stays, so switching
  back needs no re-paste — *builder's call*).
- **Deliberately off is off** (user's decision after the builder check): switching the source off,
  choosing "Geen" or another source, and later switching it back on **starts at that moment**.
  Donations made during the deliberate off period never become requests and are not a gap. Only
  time when the source was ON but the app was closed, asleep or disconnected is caught up. (So the
  saved cursor and seen-set are reset to "now" when the source is switched back on.)
- **DonoLink setup:** paste the API key, test with `GET /me` (show owner and key expiry), minimum
  amount (default €1,00 — *builder's call*), on/off.
- **StreamElements setup:** paste the JWT, test (connect and show the channel name), minimum amount
  in the channel's currency, on/off.
- A donation **without a Spotify link is not a request**: ignored, but written to the Log
  ("ignored: no Spotify link") so the streamer can see it did arrive (*builder's call*, accepted).
- **Below the minimum:** rejected paid request, reason "below minimum", listed with its track,
  "Add anyway" queues it with its amount.
- **Currency** (*builder's call*): the minimum is in the source's currency (DonoLink: EUR;
  StreamElements: the channel currency). A tip in a different currency than the minimum's cannot
  be compared, so it is a rejected paid request with reason "other currency", listed with its
  track: the streamer decides with "Add anyway" (paid, never lost; a 1-yen tip cannot jump the
  queue on its own).
- **Requester** = the donor name the platform gives ("Anoniem"/"Anonymous" when empty); not a
  Twitch identity, so viewer blocks and per-viewer limits do not apply to it (*builder's call*;
  blocked songs/artists and content rules do apply).
- **Chat reply** (*builder's call*, accepted): when Twitch is logged in, a short chat message from
  the broadcaster's account **without an @-mention**; no reply without Twitch. Every donation reply
  **starts with fixed text, never with the donor name** (e.g. "Donatieverzoek van Jan: Mr.
  Brightside staat op plek 2" / "Donation request from Jan: Mr. Brightside is #2"), so a donor
  named "!skip" or "/me" can never make the broadcaster post a command (plan-2 rule: no reply
  starts with a placeholder or "!"). *builder's call* after the builder check.
- **Catch-up:**
  - DonoLink: persist the `since` cursor and the seen-set across restarts; on start, resume from
    the saved cursor. Only the very first connection starts at "now". (Deliberately differs from
    Catchall's decision 3, as the parent design says.) Because `since` is inclusive, only items
    whose `recorded_at` equals the cursor can come back, so the seen-set holds **every id at the
    cursor's `recorded_at`** (not a fixed 200): no duplicate however many share one timestamp or
    however long the app was off (*builder's call* after the builder check).
  - StreamElements: on start and on every reconnect, fetch tips since the last seen tip through
    SE's REST API (`GET /kappa/v2/tips/<channel>` with date filters — **builder to verify**);
    dedupe by tip id against a persisted seen-set. If SE's docs do not confirm a suitable
    endpoint, StreamElements ships **live-only with gap logging** (not held back), and its card
    says that tips made while Requestify was off are not picked up (*builder's call* after the
    builder check).
- **Health:** the source counts in the title-bar connection chip. DonoLink key expiry warning in
  the chip and on the card from 7 days before expiry ("DonoLink-sleutel verloopt over 6 dagen");
  a refused key (401/403) stops polling and shows "new key needed" with what to do. StreamElements
  refused JWT likewise.
- **Live status:** donations follow the plan-2 gate: while requests are closed, paid donations
  still go into the queue (paid priority) and play once requests open (parent design).

### The Log (user's decisions unless marked)

- **Purpose:** see what went right or wrong, and why, without guessing.
- **Rail:** a "Logboek / Log" destination between Blacklist and Settings (parent design order).
- **What is in it:**
  - every request from every source (chat, Channel Points, Bits, donations, the streamer's own adds)
    with what happened: queued (position), rejected (reason), refunded, handed to Spotify, played,
    removed (by whom: viewer `!wrongsong`, mod `!remove`, streamer X, end-of-stream clear,
    blacklist sweep);
  - donations and cheers without a link ("ignored: no Spotify link");
  - connections: Spotify, Twitch, donation source — connected, lost, restored, login/key expired;
  - stream: live/offline, requests opened/closed (automatically or by hand), the clear after
    10 minutes (how many free requests cleared, how many refunded);
  - possible gaps ("possible missed Bits between 21:10 and 21:14").
- **One row per request, updated through its life** (*builder's call*): the row shows when it came in
  and its latest state ("Gespeeld 21:47"); its details show the timeline (21:42 received → queued
  #2 → 21:46 handed to Spotify → 21:47 played). A connection outage is likewise one row updated
  with "restored after 4 s".
- **View** (text sketch accepted by the user):

  ```
  Logboek            [Alles] [Verzoeken] [Verbindingen] [Problemen]   🔍
  Vandaag
  21:42  ⚡DonoLink  Jan · Mr. Brightside      In wachtrij, plek 2 · €3,00
  21:40  Chat        piet · "despacito"        Geweigerd: staat op de blacklist
  21:31  Twitch      Verbinding verbroken · na 4 s hersteld
  21:05  Stream      Live · verzoeken open
  Gisteren
  23:58  Stream      Offline · 12 gratis verzoeken gewist, 3 terugbetaald
  ```

  Newest first, grouped by day. Filters: All · Requests · Connections · Problems ("Problems" =
  rejected, refund failed or still retrying, connection lost, login/key expired, gaps, Spotify
  unreachable, hand-over failed). Search on viewer/donor name, track title and artist. Clicking a
  row opens its details (source, viewer, amount, link, reason, timeline).
- **Refunds in the Log** (*builder's call* after the builder check): no row per retry. While a
  refund is still being retried, the request's row shows "refund pending" and counts under
  Problems; when it succeeds the row updates to "refunded". A refund that is given up on (plan 2's
  rules) stays a problem.
- **A Log write that fails** (*builder's call* after the builder check): entries wait in a bounded
  in-memory buffer and are retried; while writing keeps failing, the Log screen shows a banner
  "Het logboek kan nu niet opslaan" with the reason. The queue and the window never wait on it.
- **Retention:** 30 days (*builder's call*, accepted). Old entries are deleted on start and once a
  day.
- **No badge** on the Log rail item (*builder's call*, accepted): current problems already show in
  the connection chip.
- **Mockups:** the builder renders the Log screen and the Donations card offscreen (as in plan 2)
  for the user to check before the UI tasks are reviewed.

### Gap logging (parent design, moved from plan 2)

- **Bits:** a gap entry when EventSub was disconnected while the stream was live (from the moment
  the session was lost to the moment a fresh session was welcomed), and when the app was off while
  the stream was live (from `lastSeenLiveMs` / last run to the first live confirmation after
  start). No entry for a `session_reconnect` (no gap by design).
- **StreamElements:** only if catch-up is impossible (see above).
- **DonoLink / Channel Points:** no gaps; they catch up. A catch-up that keeps failing is logged as
  a problem until it succeeds.

## Left to the builder

- Log storage format and file layout (*suggestion*: append-only JSON lines per day under
  `AppPaths`, folded by request id when read), how many rows the view loads at once.
- Event names and the exact shape of the intake's outcome events.
- Exact texts (en + nl) of Log rows, card texts and chat replies; icons; motion.
- StreamElements: Astro subscribe message details, keepalive/reconnect behaviour, REST backfill
  endpoint and paging — all to verify against SE's current docs before building; record findings
  in a short `docs/superpowers/specs/2026-11-18-streamelements-api-notes.md` (as plan 2 did for
  Twitch).
- DonoLink poll backoff details (Catchall's values are a good default).
- Which source chip colour the donation sources use (already defined in plan 1, reuse).
