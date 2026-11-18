# StreamElements API notes (for plan 3)

Checked 2026-11-18 against StreamElements' own documentation. "Confirmed" means read on an official page; anything else
is marked. The REST reference at https://dev.streamelements.com/ is a single-page app that could not be read by a
fetcher; nothing below that depends on it is treated as confirmed.

## Astro WebSocket gateway — confirmed

Sources: https://docs.streamelements.com/websockets, https://docs.streamelements.com/websockets/topics/channel-tips,
https://docs.streamelements.com/websockets/topics/channel-tips-moderation,
https://docs.streamelements.com/changelog/post/2026-03-20-astro-protocol-docs

- Connect: `wss://astro.streamelements.com/`. Resume: the same URL with `?reconnect_token=<token>`.
  A draining server answers HTTP 502; too many connects answer HTTP 429 with `Retry-After: 2`.
- Welcome: `{"type":"welcome","data":{"message":…,"client_id":…}}`.
- Subscribe (the docs show `channel.activities`; the same shape for `channel.tips`, which needs scope `tips:read`):
  `{"type":"subscribe","nonce":"…","data":{"topic":"channel.tips","room":"<channel id>","token":"<jwt>","token_type":"jwt"}}`.
  Token types: `jwt` (dashboard JWT), `apikey` (overlay token), `oauth2`.
- Answer: `{"type":"response","nonce":…,"data":{"message":"successfully subscribed to topic","topic":…,"room":…}}`;
  the returned `room` is the canonical one. An error answer carries an `error` field; codes include
  `err_unauthorized`, `err_bad_request`, `err_internal_error`, `err_deadline_exceeded`, `rate_limit_exceeded`,
  `invalid_message_type`. The exact error JSON is not shown: Requestify treats any `error` as a failure and one that
  mentions `unauthorized` as a refused token.
- Tip: envelope `id`, `ts`, `type` ("message"), `topic` ("channel.tips"), `room`, `data`. `data`: `_id`, `channel`,
  `provider`, `approved` (`pending|allowed|rejected`), `status` (e.g. "success"), `createdAt`, `updatedAt`,
  `transactionId`, `donation` = `{ user: { username, … }, message, amount (number), currency, paymentMethod }`.
- Keepalive: the server sends WebSocket PING frames every 30 s; no PONG within 70 s closes the connection (Ktor answers
  pings itself). Only text frames are accepted. Commands: at most 1 per 100 ms.
- Graceful shutdown: a `type: "reconnect"` message carries a `reconnect_token`; reconnecting with it restores the
  subscriptions (no new subscribe). The exact JSON is not shown: Requestify reads `reconnect_token` from `data` or the
  top level.

## Not confirmed (community sources only)

- The JWT is shown in the dashboard under the channel's settings ("Show secrets"); whether it expires is not stated.
- `GET https://api.streamelements.com/kappa/v2/channels/me` with `Authorization: Bearer <jwt>` returns the channel
  (`_id`, `username`, …) — from a community OpenAPI file (github.com/c4ldas/streamelements-api), not an official page.
  Requestify uses it only to show the channel name and find the room; the channel id is also read from the JWT's
  `channel` claim when that call fails.
- Listing tips (`GET /kappa/v2/tips/{channel}`) and whether it can return "tips after time X": **not confirmed**.

## Decision (per the plan-3 design)

No confirmed way to fetch tips made while Requestify was off → StreamElements is **live-only with gap logging**:
`DonationHealth.canCatchUp = false`, the Donations card says tips made while Requestify is off are not picked up, and the
Log gets a "possible missed tips" gap for every time the source was on but not connected (app off, connection lost).
A tip whose moderation says `rejected` is not a request; `pending` and `allowed` ones are (the money is in).
