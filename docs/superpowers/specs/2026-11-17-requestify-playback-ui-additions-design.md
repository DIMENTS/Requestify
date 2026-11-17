# Requestify — playback UI additions (short design)

Date: 2026-11-17
Status: awaiting approval
Builds on: `2026-11-16-requestify-design.md` (plan 1 + polish, branch `feat/plan-1-core-spotify`)

## Why

From the user's first real-world test: the app looks right now, but four things are missing to feel
complete while streaming: going back a track, seeing what plays after the requests, seeing the last
song (and its state) the moment the app opens, and a Now Playing card that never jumps around.

## Decisions

### 1. Back button
- Now Playing controls become **Previous · Play/Pause (primary, wide) · Next** in the Expressive button
  group, in both the wide and the narrow layout.
- Uses Spotify's own "previous" command, so it behaves exactly like Spotify: a first press restarts the
  current song when it's a few seconds in, a second press goes to the previous song.
- *builder's call:* a request that already played is **not** put back into the queue by "previous"; if the
  previous song happens to be an earlier request it shows as the streamer's own music. The handed-over
  request (if any) stays in Spotify's queue and still plays next.

### 2. Upcoming tracks from Spotify (user's choice: under the requests)
- **One merged "upcoming" list** on Home, in real playing order (user's follow-up: "merge it nicely with
  the requests, so it feels less empty"): "Up next in Spotify" (the handed-over request) → the queued
  requests → the next tracks Spotify itself has lined up (the streamer's playlist/album/radio and anything
  they queued in Spotify). Requests are always handed over before Spotify's own next tracks, so this order
  is what will actually play.
- All rows share the same row style (art, title, artist, duration, the connected-list shape), so it reads
  as one list. Spotify's own tracks carry a neutral source chip **"Your music" / "Je muziek"** where
  requests show their source, and have no drag handle and no remove button (Spotify's API can't edit
  them). A subtle divider with the caption **"Then in Spotify" / "Daarna in Spotify"** marks where the
  requests end — not a separate block.
- Show up to **15** of Spotify's own upcoming tracks (the user's "155" read as 15; Spotify's queue
  endpoint returns at most about 20). *builder's calls:* leave out the request that is already handed over
  (shown at the top already) and repeats; when Spotify has nothing lined up, the divider and the section
  disappear.
  Refresh the list when the track changes, after a hand-over, after skip/previous, and every 30 s —
  not on every poll, to stay well inside Spotify's rate limits. A failed refresh keeps the last list.
- Real playing order matches what's shown: requests are handed over before Spotify's own next tracks.

### 3. Last played song + its state at startup (user's choice: local + Spotify)
- **Local:** Requestify remembers the last song it saw (track + position + paused/playing) in its saved
  state, so on startup the Now Playing card shows it immediately instead of "Nothing is playing".
- **Spotify:** when Spotify reports nothing active, Requestify asks Spotify's **recently played** for
  the last song and uses it when it's newer than the local one (e.g. the streamer listened while
  Requestify was closed). Asked at startup and then at most once a minute while nothing is active.
- The card's label tells the state: **NOW PLAYING / PAUSED / LAST PLAYED** (en + nl:
  NU SPEELT / GEPAUZEERD / LAATST GESPEELD). In the "last played" state the play button tries to resume;
  when Spotify has no active device the existing "Open Spotify on a device" status explains why.
- This needs one extra Spotify permission (`user-read-recently-played`). New logins get it
  automatically. *builder's call:* existing logins without it keep working; the Connections screen shows
  a short "Log in again to show your last played song" note with the log-in button, and until then only
  the local memory is used. Nothing breaks and nothing nags elsewhere.

### 4. Fixed-space Now Playing card (wide and narrow)
- The card has fixed slots that are always reserved: art, label, title (**2 lines reserved**, longer
  titles end with "…"), artist (1 line), requester/source row (reserved even when empty), progress,
  controls. Every state — playing, paused, last played, nothing playing — uses the same skeleton (idle
  shows a placeholder art and its message in the title slot; controls stay in place, disabled when there's
  nothing to control).
- Result: switching songs, titles of 1 or 2 lines, with or without requester, or going idle never moves
  the progress bar or the buttons, in the big window and in the narrow (minimised-width) layout.
- The earlier fix stays: if a window is too short for even the smallest art, the card scrolls to its
  controls rather than clipping them.

## Non-goals
- Editing, reordering or removing Spotify's own upcoming tracks (Spotify's API doesn't allow it).
- A full listening history beyond the single last-played song.
- Putting a previous request back in the queue.

## Tests (for the builder)
- Controller/API: previous command; upcoming list refresh triggers and the 30 s cadence (virtual time);
  handed-over request excluded; failed refresh keeps the old list; local last-played persisted and shown
  on startup; recently-played used only when nothing is active and only when newer; missing permission
  (403) falls back to local without errors.
- UI: button order; the "Then in Spotify" section rendering + hidden when empty; label per state;
  bounds tests that the progress bar and controls have **identical positions** across playing (2-line
  title + requester), own music (1-line title), paused, last played and nothing playing — wide and narrow.
