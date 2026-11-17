# Twitch API facts for a public-client desktop song-request app (verified 2026-11-17)

Source of truth: dev.twitch.tv. Note: on **2026-11-10** Twitch launched a new docs style. The API reference is now split per category
(`https://dev.twitch.tv/docs/api/reference/<category>`, e.g. `/channel-points`, `/chat`, `/eventsub`), and the EventSub subscription-types
page moved to `https://dev.twitch.tv/docs/eventsub/reference/<group>` (e.g. `/chat`, `/channel-points`, `/channel`, `/stream`).
Old anchors like `/docs/api/reference/#create-custom-rewards` and `/docs/eventsub/eventsub-subscription-types/` now redirect or only show an index.

Legend: CONFIRMED = matches the assumption; CORRECTION = assumption was wrong or incomplete; UNDOCUMENTED = not stated in official docs
(behaviour from community/experience, flagged as such).

---

## 1. Device Code Grant Flow (DCF), public client

Doc: https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/ (section "Device code grant flow")

**Start:** `POST https://id.twitch.tv/oauth2/device` (form-urlencoded)
- `client_id` (required)
- `scopes` (required): space-delimited, URL-encoded scope list
- Response: `device_code`, `user_code`, `verification_uri`, `expires_in` (example 1800), `interval` (example 5). CONFIRMED.
- `verification_uri` already embeds the code, e.g. `https://www.twitch.tv/activate?public=true&device-code=ABCDEFGH`, so the app can open
  it directly in the browser; the user then only confirms (show `user_code` anyway as a fallback).

**Poll:** `POST https://id.twitch.tv/oauth2/token` (form-urlencoded)
- `client_id`
- `scopes` (the parameter table lists it as required; the doc's own worked example sends `scope` instead; docs are inconsistent; send `scopes`
  as the table says, it is the same space-delimited list)
- `device_code`
- `grant_type=urn:ietf:params:oauth:grant-type:device_code`
- No `client_secret`.
- Success (200): `{"access_token": "...", "expires_in": <int>, "refresh_token": "...", "scope": ["..."], "token_type": "bearer"}`
  (access tokens from DCF live 4 hours).

**Error bodies (CORRECTION: Twitch-style, not RFC 8628 `{"error": "..."}` style):**
- Pending: HTTP 400 `{"status": 400, "message": "authorization_pending"}`
- Device code already used or invalid: HTTP 400 `{"status": 400, "message": "invalid device code"}`
- `slow_down`: UNDOCUMENTED. Not mentioned anywhere in Twitch docs. Poll at `interval` and treat an unknown 400 conservatively.
- Expired device code: UNDOCUMENTED as a separate body. The code is valid for `expires_in` seconds and is "one time use only"; expect
  `invalid device code`. Stop polling locally after `expires_in` regardless.
- User denied: UNDOCUMENTED for DCF (the auth-code flow documents `access_denied`; the DCF section does not). Practical rule: keep polling only
  on `authorization_pending`; any other 400 message (and any `slow_down`, if it ever appears, means add 5 s) ends the attempt and offers "try again".

**Public client type:**
- Docs: public clients "don't need a client secret", "can refresh an access token without a client secret", and can only use DCF (not client
  credentials / implicit). Twitch suggests Public for open platforms "such as windows".
- Yes, register the app with **Client Type = Public** in the dev console. That is what makes secret-less refresh work. Community reports
  (forum, not official docs) say the type is fixed at creation (option greyed out), so create a new app if the existing one is Confidential.
- Consequence: a public client **cannot get an app access token** (no client-credentials grant), so everything (Helix + EventSub) must use the
  user token. WebSocket is the only usable EventSub transport (webhooks and conduits need app tokens).
- Registering an app requires 2FA on the developer account (https://dev.twitch.tv/docs/authentication/register-app/).

## 2. Refreshing a public client's token

Doc: https://dev.twitch.tv/docs/authentication/refresh-tokens/ (+ DCF section above)

- `POST https://id.twitch.tv/oauth2/token`, `Content-Type: application/x-www-form-urlencoded`
  - `client_id`, `grant_type=refresh_token`, `refresh_token` (**must be URL-encoded**; restricted characters otherwise cause
    "Invalid refresh token"), `client_secret` omitted ("not required if your app's client type is Public").
- Response: `access_token`, `expires_in`, `refresh_token`, `scope[]`, `token_type: "bearer"`. Always store the new `refresh_token`.
- **Rotation: CONFIRMED.** DCF section: refresh tokens are "for one time use only"; a used refresh token becomes invalid.
- **30-day rule, nuance:** the refresh-tokens page says public-client refresh tokens "expire 30 days after they are generated"; the DCF section
  phrases it as expiring after 30 days of being unused. Because every refresh issues a new refresh token, these agree in practice:
  **the session dies if the app does not refresh for 30 days** (the clock restarts on each refresh). Confidential-client refresh tokens do not
  expire. Tokens also die when the user changes password or disconnects the app.
- **Dead refresh token:** HTTP 400 `{"error": "Bad Request", "status": 400, "message": "Invalid refresh token"}` (example on the page); the text also
  says refresh "can fail with HTTP status code 401 Unauthorized if the refresh token is no longer valid". Treat **400 or 401 from the refresh
  call** as "re-run DCF".
- **Transient failure:** not documented. Treat network errors / 5xx / 429 as retryable with backoff and **keep** the stored tokens.
- Twitch recommends refreshing reactively on a Helix 401 rather than proactively from `expires_in` (proactive refresh is still allowed).
- A refresh token can back at most 50 live access tokens; refresh from one place only and distribute the result.
- History: a server-side bug broke public-client refresh in Jan 2025 (twitchdev/issues #1037), fixed 2025-01-08.

## 3. Token validation

Doc: https://dev.twitch.tv/docs/authentication/validate-tokens/

- `GET https://id.twitch.tv/oauth2/validate`, header `Authorization: OAuth <token>` (`Bearer` also accepted).
- **Still required: CONFIRMED.** Any third-party app (desktop explicitly included) that keeps an OAuth session must validate **at startup and
  then every hour**. Twitch audits this and may revoke the client ID or throttle the app if it is not done.
- 200: `{"client_id": "...", "login": "twitchdev", "scopes": ["..."], "user_id": "141981764", "expires_in": 5520838}`. `login` is lowercase.
- 401: `{"status": 401, "message": "invalid access token"}` means refresh (and if the refresh fails, re-run DCF).
- Use `scopes` from validate to detect "token predates a scope we now need" and ask for a re-login.

## 4. EventSub over WebSocket

Docs: https://dev.twitch.tv/docs/eventsub/handling-websocket-events/ ,
https://dev.twitch.tv/docs/eventsub/websocket-reference/ , https://dev.twitch.tv/docs/eventsub/manage-subscriptions/

- URL: `wss://eventsub.wss.twitch.tv/ws`
- **`keepalive_timeout_seconds` IS settable**: `wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30`, integer 10–600; out-of-range
  numeric values are rounded to the nearest valid value.
- `session_welcome`: `payload.session = {id, status: "connected", connected_at, keepalive_timeout_seconds, reconnect_url: null}` (no
  `recovery_url` field). In the welcome, `keepalive_timeout_seconds` is also "the number of seconds that you have to subscribe". Default
  deadline 10 s "unless otherwise specified when connecting"; otherwise close 4003. The limit "is subject to change".
- `session_keepalive`: empty payload, sent when nothing else was sent within the keepalive window. Notifications also reset the timer;
  WebSocket ping/pong does **not**. No notification or keepalive for longer than the window means the connection is dead: open a new one and
  **resubscribe**.
- Ping frames: must answer with Pong (else close 4002). The client must send **nothing else** (else close 4001).
- `notification`: `metadata {message_id, message_type, message_timestamp, subscription_type, subscription_version}`,
  `payload {subscription, event}`. Timestamps are RFC3339 **with nanoseconds**.
- **At-least-once delivery** (https://dev.twitch.tv/docs/eventsub/): a resent message keeps the same `metadata.message_id`; dedupe on it.
- **No replay**: events during a disconnect are lost. Re-sync via Helix after any fresh reconnect.
- `session_reconnect`: `payload.session {id, status: "reconnecting", keepalive_timeout_seconds: null, reconnect_url, connected_at}`. Sent about 30 s
  before the server closes. Connect to `reconnect_url` **unmodified**, keep the old socket open until the new socket's welcome arrives, then close
  the old one. Subscriptions carry over (do not resubscribe). Does not count against the connection limit. Too slow, or staying on the old
  socket, gives close 4004 on the old one.
- `revocation`: `payload.subscription.status` is `user_removed` | `authorization_revoked` | `version_removed`; sent once, then nothing more for
  that subscription. Twitch "may also revoke a subscription at any time".
- Close codes: 4000 internal server error; 4001 client sent inbound traffic; 4002 client failed ping-pong; 4003 connection unused (no
  subscription in time); 4004 reconnect grace time expired; 4005 network timeout (transient); 4006 network error (transient); 4007 invalid
  reconnect URL.
- On disconnect, subscriptions become disabled with statuses such as `websocket_disconnected`, `websocket_failed_ping_pong`,
  `websocket_network_timeout`, `websocket_failed_to_reconnect`; `GET /helix/eventsub/subscriptions` keeps disabled websocket subs for >= 1 minute.
- **Limits per user token (client ID + user ID pair):** max **3** WebSocket connections *with enabled subscriptions*; max **300** enabled
  subscriptions per connection; `max_total_cost` **10**; max **3** subscriptions with the same `type` + `condition`. Recommendation: one
  connection. Cost: subscriptions that require user authorization cost 0; ones that only need a user ID (e.g. stream.online) cost 1 unless that
  user has authorized your client, in which case they cost 0. For this app everything is about the authorizing broadcaster, so the total cost is 0.
- Only **user access tokens** work for WebSocket subscriptions (an app token gives 400).

## 5. Creating subscriptions (WebSocket transport)

Doc: https://dev.twitch.tv/docs/api/reference/eventsub (Create EventSub Subscription)

- `POST https://api.twitch.tv/helix/eventsub/subscriptions`, headers `Authorization: Bearer <user token>`, `Client-Id`,
  `Content-Type: application/json`
- Body: `{"type": "...", "version": "1", "condition": {...}, "transport": {"method": "websocket", "session_id": "<welcome id>"}}`
  (`is_batching_enabled` must be omitted/false except for drop.entitlement.grant).
- **Success: 202 Accepted** (not 200/201). For WebSocket the status is `"enabled"` immediately. Body:
  `{data: [subscription], total, total_cost, max_total_cost}`.
- 400: bad/missing fields, unknown type/version, deprecated subscription, transport fields that don't match the method, or token type that
  doesn't match the transport (app token + websocket).
- 401: missing/invalid token, or Client-Id doesn't match the token.
- 403: "The user access token doesn't meet the subscription type's authorization requirements, such as the required scope."
- 409: an identical enabled/pending subscription already exists. **Since 2026-04-17 the error `message` contains the existing subscription's ID.**
- 410: `channel.follow` v1 is gone (use v2).
- 429: more than 3 enabled/pending subs with the same type+condition, exceeding `max_total_cost`, exceeding WebSocket connection limits, or the
  WebSocket `max_total_cost` of 10.

## 6. `channel.chat.message`

Doc: https://dev.twitch.tv/docs/eventsub/reference/chat#channel-chat-message

- version **1**; condition `{broadcaster_user_id, user_id}` (both required; `user_id` = user to read chat as, i.e. the token user = broadcaster).
- Scope (user token): **`user:read:chat`** only. `user:bot` / `channel:bot` apply only to app-token (bot badge) setups.
- Event fields: `broadcaster_user_id/_login/_name`, `chatter_user_id/_login/_name`, `message_id` (UUID), `message {text, fragments[]}`,
  `message_type` (`text`, `channel_points_highlighted`, `channel_points_sub_only`, `user_intro`, `power_ups_message_effect`,
  `power_ups_gigantified_emote`), `badges[] {set_id, id, info}`, `cheer {bits}` (nullable), `color`, `reply` (nullable:
  `parent_message_id`, `parent_message_body`, `parent_user_id/_name/_login`, `thread_message_id`, `thread_user_id/_name/_login`),
  `channel_points_custom_reward_id` (nullable), shared chat: `source_broadcaster_user_id/_name/_login`, `source_message_id`, `source_badges`
  (all null when the message is from the broadcaster's own channel), `is_source_only` (nullable boolean, added 2025-04-10).
- Fragment types: `text`, `cheermote {prefix, bits, tier}`, `emote {id, emote_set_id, owner_id, format[]}`, `mention {user_id, user_name,
  user_login}`, **`gif {id, url}`** (added 2026-07-16; use the URL unmodified).
- `badges[].info`: only used for subscriber badges (months subscribed).
- **Badge set_id values:** the docs do NOT enumerate them (examples show `moderator`, `subscriber`, `sub-gifter`). Known values: `broadcaster`,
  `moderator`, **`lead_moderator`** (introduced 2025-12-18, https://discuss.dev.twitch.com/t/lead-moderator-role-and-chat-badge-introduced/64488:
  it *replaces* the `moderator` badge for lead mods by default, though a lead mod may choose to show the plain moderator badge; Twitch says to
  check for either), `vip`, `subscriber`, `founder` (`founder` replaces the `subscriber` badge for founders; from IRC experience, not in the
  EventSub doc). Robust checks: broadcaster = `chatter_user_id == broadcaster_user_id`; mod = `moderator` or `lead_moderator`; subscriber =
  `subscriber` or `founder`.
- Shared chat: `badges` are the chatter's badges in *your* channel; `source_badges` are those in the source channel.

## 7. Sending chat

Docs: https://dev.twitch.tv/docs/api/reference/chat (Send Chat Message), https://dev.twitch.tv/docs/chat/ (rate limits)

- `POST https://api.twitch.tv/helix/chat/messages`, user token with **`user:write:chat`** (sender = broadcaster; no `channel:bot` needed).
- Body: `broadcaster_id` (req), `sender_id` (req, must equal token user), `message` (req, **max 500 chars**; emotes by name, no colons),
  `reply_parent_message_id` (opt), `for_source_only` (opt, shared chat; **returns 400 if set with a user access token**), **`pin`** (opt, new
  2026-05: pins for 20 min, needs `moderator:manage:chat_messages`, can't combine with reply/for_source_only).
- 200: `{"data": [{"message_id": "...", "is_sent": true|false, "drop_reason": null | {"code": "...", "message": "..."}}]}`. A 200 does not mean
  the message was sent; check `is_sent` (dropped for duplicate, ban/timeout, slow mode, AutoMod, etc.).
- 400 invalid/missing; 401 token/scope; 403 "The sender is not permitted to send chat messages to the broadcaster's chat room."; **422 "The
  message is too large."**; 429 rate limit.
- Rate limits: chat limits are 20 msgs/30 s for normal users and 100 msgs/30 s for broadcaster/mod/VIP (exceeding them makes Twitch ignore the
  account's messages for 1 hour, except in chats where it is broadcaster/mod/VIP). The Send Chat Message API has its own `Ratelimit-*` bucket
  (header may show a much larger number). With a user token there is no "Chat Bot" badge; messages appear as the broadcaster.

## 8. Channel Points custom rewards

Doc: https://dev.twitch.tv/docs/api/reference/channel-points

- Scope: **`channel:manage:redemptions`** for create/update/delete/update-redemption; get needs `channel:read:redemptions` or manage.
- **Create:** `POST https://api.twitch.tv/helix/channel_points/custom_rewards?broadcaster_id=<token user>`, JSON body: `title` (req, 1–45 chars,
  unique case-insensitively among *all* the broadcaster's rewards, including dashboard-made ones), `cost` (req, 1–1,000,000,000), `prompt`,
  `is_enabled` (default true), `background_color` (#RRGGBB), `is_user_input_required` (default false), `is_max_per_stream_enabled` +
  `max_per_stream`, `is_max_per_user_per_stream_enabled` + `max_per_user_per_stream`, `is_global_cooldown_enabled` + `global_cooldown_seconds`,
  `should_redemptions_skip_request_queue` (default false; true means redemptions are auto-FULFILLED and can't be refunded/canceled by you).
  **Success: 200** (not 201) with `data[0]` = reward. Max **50 rewards per channel** (enabled + disabled).
- Prompt length: the current docs no longer state a limit; older docs and typings said 200 characters. Keep the prompt at 200 characters or fewer.
- **Duplicate title:** HTTP 400 ("The title must be unique (case-insensitive) amongst all of the broadcaster's custom rewards."). No distinct
  error code (e.g. `CREATE_CUSTOM_REWARD_DUPLICATE_REWARD`) is documented, so match on 400 plus message text. 400 also covers an AutoMod
  rejection of title/prompt and the 50-reward limit.
- **Not affiliate/partner:** HTTP **403** "The broadcaster is not a partner or affiliate." (also on get/update/delete/redemption endpoints).
- **Update:** `PATCH .../custom_rewards?broadcaster_id=&id=<reward id>`; body any subset (`title`, `prompt`, `cost`, `background_color`,
  `is_enabled`, `is_user_input_required`, limit pairs, `is_paused`, `should_redemptions_skip_request_queue`). Each limit flag must be sent with its
  value (sending only the value turns the limit off). 403 "The ID in the Client-Id header must match the client ID used to create the custom
  reward."; 404 reward not found.
- **Get:** `GET .../custom_rewards?broadcaster_id=[&id=...x50][&only_manageable_rewards=true]`; `only_manageable_rewards=true` returns only rewards
  this client ID created. 404 if none of the given `id`s exist.
- **Delete:** `DELETE .../custom_rewards?broadcaster_id=&id=`, 204; asynchronously marks the reward's UNFULFILLED redemptions **FULFILLED**.
- **Ownership: CONFIRMED.** Only the client ID that created a reward can update/delete it, read its redemptions, or update redemption status (403
  otherwise). Viewers can still redeem other rewards; EventSub redemption events still arrive for them (scope-based), but you can't
  fulfill/cancel them.
- Reward object: `broadcaster_id/_login/_name`, `id`, `title`, `prompt`, `cost`, `image`, `default_image`, `background_color`, `is_enabled`,
  `is_user_input_required`, `max_per_stream_setting`, `max_per_user_per_stream_setting`, `global_cooldown_setting`, `is_paused`, `is_in_stock`,
  `should_redemptions_skip_request_queue`, `redemptions_redeemed_current_stream`, `cooldown_expires_at`.

## 9. Redemptions

Doc: https://dev.twitch.tv/docs/api/reference/channel-points

- **Get:** `GET https://api.twitch.tv/helix/channel_points/custom_rewards/redemptions?broadcaster_id=&reward_id=&status=UNFULFILLED[&sort=OLDEST|NEWEST][&first=1..50][&after=cursor]`
  - `reward_id` **required**; `status` required unless `id` given (CANCELED/FULFILLED/UNFULFILLED, case-sensitive); `sort` default OLDEST; `first`
    default 20, **max 50**; with `id` (<= 50) the status/sort/first/after params are ignored and there is no pagination.
  - Only works for rewards your client ID created (403 otherwise); 404 if reward_id not found.
- **Update status:** `PATCH .../custom_rewards/redemptions?id=<r1>&id=<r2>...(<=50)&broadcaster_id=&reward_id=`, body `{"status": "FULFILLED"}` or
  `{"status": "CANCELED"}` (CANCELED refunds the points). Only **UNFULFILLED** redemptions can be updated.
  - Already fulfilled/canceled or nonexistent: **404** if *none* of the ids are UNFULFILLED redemptions of that reward; with a mix, the bad ones are
    silently omitted from `data`, so compare the returned ids to the requested ids.
  - 400 missing params / bad status; 403 client-ID mismatch or non-affiliate.
- Redemption object: `broadcaster_id/_login/_name`, `id`, `user_id/_login/_name`, `user_input`, `status`, `redeemed_at`, `reward {id, title, prompt, cost}`.

## 10. `channel.channel_points_custom_reward_redemption.add` (+ reward update/remove)

Doc: https://dev.twitch.tv/docs/eventsub/reference/channel-points

- version **1**; condition `{broadcaster_user_id (req), reward_id (optional)}`; scope `channel:read:redemptions` **or** `channel:manage:redemptions`.
- Event: `id`, `broadcaster_user_id/_login/_name`, `user_id/_login/_name`, `user_input` (empty string if none), `status`
  (`unknown` | `unfulfilled` | `fulfilled` | `canceled`, **lowercase** here vs UPPERCASE in Helix), `reward {id, title, cost, prompt}`, `redeemed_at`.
- `channel.channel_points_custom_reward_redemption.update` (v1) fires when status becomes fulfilled/canceled.
- **Yes**, Twitch has `channel.channel_points_custom_reward.update` and `channel.channel_points_custom_reward.remove` (v1, same scopes,
  optional `reward_id`), plus `.add`. The payload is the full reward (`id`, `is_enabled`, `is_paused`, `is_in_stock`, `title`, `cost`, `prompt`,
  `is_user_input_required`, `should_redemptions_skip_request_queue`, limits, `global_cooldown`, `background_color`, `image`, `default_image`,
  `cooldown_expires_at`, `redemptions_redeemed_current_stream`). Use these to notice the streamer deleting/pausing/editing the app's reward in
  the dashboard.

## 11. `channel.cheer` vs `channel.bits.use`

Doc: https://dev.twitch.tv/docs/eventsub/reference/channel

- `channel.cheer` v1, condition `{broadcaster_user_id}`, scope **`bits:read`**. Event: `is_anonymous`, `user_id/_login/_name` (null if anonymous),
  `broadcaster_user_*`, `message` (plain string, *includes* the cheermote text such as "Cheer100"), `bits`. Not deprecated.
- `channel.bits.use` v1 (beta 2025-02-06, v1 GA 2025-02-27), condition `{broadcaster_user_id}`, scope `bits:read`. Fires for cheers, Power-ups
  and Custom Power-ups (not the streamer's free self power-ups, not Extension bits). Event: `broadcaster_user_*`, `user_id/_login/_name` (not
  nullable, no `is_anonymous`), `bits`, `type` (`cheer` | `power_up` | `custom_power_up`), `message` (nullable object `{text, fragments[]}`
  with fragment types text/cheermote/emote), `power_up` (nullable: `type` `message_effect` | `celebration` | `gigantify_an_emote`, `emote`,
  `message_effect_id`), `custom_power_up` (nullable: `title`, `reward_id`). Combos were added 2025-05 and **removed 2026-04-02**; Custom
  Power-ups added 2026-04-02.
- Preference: the docs do not tell you to prefer either. For "bits buy a song request", `channel.bits.use` filtered to `type == "cheer"` gives the
  message as structured fragments (you can strip cheermote fragments cleanly) and covers all bit spend; `channel.cheer` is simpler and still
  supported. Pick one; subscribing to both double-counts cheers.

## 12. Live status

Docs: https://dev.twitch.tv/docs/eventsub/reference/stream , https://dev.twitch.tv/docs/api/reference/streams

- `stream.online` / `stream.offline` v1, condition `{broadcaster_user_id}`, **no authorization/scope required** (still needs a user token for
  WebSocket; cost 0 because the broadcaster authorized the client). Online event: `id`, `broadcaster_user_*`, `type` (`live`, `playlist`,
  `watch_party`, `premiere`, `rerun`), `started_at`. Offline: `broadcaster_user_*` and, since 2026-06-26, `id`.
- Startup: `GET https://api.twitch.tv/helix/streams?user_id=<id>` (any token, no scope). Live means `data[0]` with `type: "live"`; offline means
  `data: []`. Use the **user** token: streams restricted from discovery are excluded except your own when using a user token. `is_mature` and
  `tag_ids` are deprecated.

## 13. Follower check

Doc: https://dev.twitch.tv/docs/api/reference/channels (Get Channel Followers)

- `GET https://api.twitch.tv/helix/channels/followers?broadcaster_id=<b>&user_id=<u>`, user token with **`moderator:read:followers`**, token user
  = broadcaster (or a mod).
- Response: `{"data": [{"user_id", "user_login", "user_name", "followed_at"}], "pagination": {...}, "total": N}`. Following means one entry
  (with `followed_at`, useful for "followed at least X days"); not following means `data: []`.
- If the scope is missing or the user isn't broadcaster/mod, `data` is **silently empty** (only `total` returned) rather than an error. Verify the
  scope via `/validate`, or every user looks like a non-follower.
- (`GET /helix/channels/followed` with `user:read:follows` is the viewer-side variant; not usable here.)

## 14. `GET /helix/users`

Doc: https://dev.twitch.tv/docs/api/reference/users

- `GET https://api.twitch.tv/helix/users` with no params + user token returns the token's user (or `?id=`/`?login=`, up to 100 combined).
- `broadcaster_type`: `"affiliate"`, `"partner"`, or `""` (normal). CONFIRMED. `type`: `"admin"`, `"global_mod"`, `"staff"`, or `""`.
  `view_count` is deprecated.

---

## (a) Minimal scope set

`user:read:chat user:write:chat channel:manage:redemptions bits:read moderator:read:followers`

- `user:read:chat`: channel.chat.message (and other chat EventSub types)
- `user:write:chat`: Send Chat Message as the broadcaster
- `channel:manage:redemptions`: create/update/delete reward, get/update redemptions, and all channel-points EventSub types (so
  `channel:read:redemptions` is redundant)
- `bits:read`: channel.cheer / channel.bits.use
- `moderator:read:followers`: Get Channel Followers with `user_id`
- None for stream.online/offline, Get Streams, Get Users.
- Not needed: `user:bot`, `channel:bot` (app-token bot pattern), `chat:read`/`chat:edit` (IRC).

## (b) Wrong / changed / deprecated (2025–2026)

- DCF errors are `{"status":400,"message":"..."}`; only `authorization_pending` and `invalid device code` are documented. No `slow_down` /
  `access_denied` / `expired_token` codes are documented.
- DCF token request uses `scopes` (docs inconsistent with `scope` in one example).
- Public refresh-token expiry: "30 days after generated" (refresh page) vs "30 days unused" (DCF section). Rotation makes it effectively 30 days
  since the last refresh. Dead token = 400 "Invalid refresh token" **or** 401.
- Create EventSub Subscription success is **202**; 409 now carries the existing subscription ID (2026-04-17).
- Create Custom Reward success is **200**; duplicate title is a plain 400 (no named error code); prompt length limit no longer documented.
- Update Redemption Status on non-UNFULFILLED ids: 404 only when none qualify; otherwise silently omitted.
- channel.chat.message: `gif` fragment (2026-07-16), `is_source_only` (2025-04-10), power-ups message types; `lead_moderator` badge
  (2025-12-18) replaces `moderator` for lead mods.
- Send Chat Message: `pin` param (2026-05); `for_source_only` + user token = 400.
- channel.bits.use is GA v1 (2025-02-27); Combos removed and Custom Power-ups added 2026-04-02; no `is_anonymous`.
- stream.offline includes `id` (2026-06-26).
- PubSub is decommissioned (migration guide removed 2026-06-12); IRC non-secure WebSocket decommissioned 2025-08-15. Use EventSub + Helix only.
- Docs restructured 2026-11-10 (per-category API reference pages, `/docs/eventsub/reference/<group>`).

## (c) Gotchas for the desktop app

1. Register the app as **Public** (type is effectively locked at creation). No app access token is possible, so use the user token everywhere.
2. Refresh tokens are **single-use**: persist the new pair atomically *before* using it; never refresh concurrently (two parallel refreshes means
   one fails and may kill the session). A crash between receiving and saving the new refresh token forces a re-login.
3. If the app doesn't refresh for 30 days, the session is gone. Refresh on startup if the access token is expired, and refresh on any Helix 401.
4. Validate at startup and **hourly** (required; audited).
5. Subscribe within the welcome's `keepalive_timeout_seconds` (default 10 s, "subject to change"). Connect with
   `?keepalive_timeout_seconds=30` (or similar) to get more slack, and create all subscriptions concurrently right after the welcome.
6. Treat silence for more than keepalive + a margin as a dead socket. Open a **new** connection and **resubscribe** (subs don't survive a fresh
   connection). On `session_reconnect`, don't resubscribe; swap sockets after the new welcome.
7. Never send text frames to the server (close 4001); always answer pings (4002).
8. One connection only (limit 3 with enabled subs per user token). A crashed previous instance's subs become disabled, not counted, but avoid
   opening multiple sockets in reconnect loops; use exponential backoff on 4000/4005/4006.
9. At-least-once delivery: dedupe on `metadata.message_id` (plus `event.message_id` for chat, redemption `id` for rewards).
10. No replay after a disconnect: on every fresh (re)connect re-sync with Helix (GET UNFULFILLED redemptions for your reward, paginated;
    GET streams for live status).
11. 409 on create means "already exists" (message includes the ID). Treat it as success. 403 on create means a scope is missing, so re-login.
12. `revocation` with `authorization_revoked` means the user disconnected the app: log out and prompt for DCF.
13. Your own Send Chat Message output comes back as `channel.chat.message` (chatter = broadcaster). Ignore own bot replies to avoid loops.
14. Shared chat: messages from partner channels arrive with non-null `source_broadcaster_user_id`. Decide whether to ignore them (probably yes for
    requests); use `badges` (your channel), not `source_badges`, for permissions.
15. Mod check must accept `moderator` **and** `lead_moderator`; subscriber check `subscriber` **and** `founder`; broadcaster check by ID.
16. Send Chat Message returns 200 even when dropped. Check `is_sent` / `drop_reason`. Keep messages <= 500 chars (422 otherwise).
17. Custom reward: check `broadcaster_type` first (403 for non-affiliates); title must not collide with any existing reward (400); store the
    reward ID and find it with `only_manageable_rewards=true`; recreate if `channel.channel_points_custom_reward.remove` fires or the GET returns
    404. Keep `should_redemptions_skip_request_queue=false` so you can CANCEL (refund) rejected requests. Deleting the reward auto-FULFILLS
    pending redemptions (no refunds).
18. Redemption status: EventSub uses lowercase (`unfulfilled`), Helix UPPERCASE (`UNFULFILLED`). PATCH can partially succeed silently.
19. Follower check returns empty data (not an error) when the scope is missing.
20. `channel.cheer` `message` contains the cheermote text ("Cheer100 song name"). Strip it, or use `channel.bits.use` fragments. Don't
    subscribe to both or cheers are double-counted.
21. EventSub timestamps carry nanoseconds; Helix `Ratelimit-Reset` tells how long to wait on 429.
