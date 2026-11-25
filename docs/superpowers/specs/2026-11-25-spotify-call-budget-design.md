# Spotify call budget: ask at the right moment, wait properly, show it

Approved by the user on 2026-11-25 ("Ja, bouw het").

## Why

The title bar mostly says "Spotify is druk". Requestify asks Spotify what plays every 3 s, always, and since 0.3.2 also
reads the devices every 6 s while nothing is active: 1,300 to 1,900 calls an hour, over 40,000 a day from the tray.
Spotify limits a Development Mode app twice: per rolling 30 s, and since 23 July 2026 by a quota per developer account
(`429` with `"reason": "QUOTA_EXCEEDED"`; limits unpublished, endpoints grouped in unpublished buckets). Extended quota
is out of reach (organizations with 250k users). Waits up to 19 hours are reported. Which of the two limits hits this
user is not known yet: Requestify threw the reason away.

## Goals

- About 80 % fewer calls while a song plays, 95 % fewer in the background, without losing the hand-over moment.
- No call goes out while Spotify asked to wait, whoever makes it (poll, button, search).
- The streamer sees until when, and the Log keeps why and after how many calls.

Non-goals: reading what plays from Windows (only works when Spotify runs on the same PC); a second Client ID (shares
the quota). Anti-goals: a request that misses its turn because of slower polling; hammering an exhausted quota.

## Approaches

1. **Predict from the song's end (chosen).** A poll tells where the song ends, so only the moment before (hand-over)
   and after it need a poll; in between a heartbeat is enough.
2. A fixed slower poll: misses the 15 s before the end in which a request goes to Spotify.
3. Windows' media session: no calls at all, but only with Spotify on the same PC.

## 1. Pace

| Situation | Next poll |
|---|---|
| A song plays | 15 s; sooner at 15 s before the end while a request waits; every 5 s in the last 15 s; 1 s after the end |
| Spotify still reports the ended song | 1, 2, 4, 8, 15 s |
| Paused | 30 s; while a request waits, at 15 s before the end but no sooner than 10 s |
| Nothing active | 5, 10, 20, 40, 60 s (at most 15 s for the first 10 min a request waits) |
| Nobody needs it, nothing active | 5, 10, 20 ... s, at most 5 min (paused: 5 min) |
| Nobody needs it, a song plays | 60 s |
| After a button, or a device made active | 1 s, for at most 4 s |
| A poll failed | 3, 6, 12, 24, 30 s (up to 5 min while nobody needs it) |

"Nobody needs it": not live, the window hidden or minimized, no request queued, and while something is active none handed
over or playing. A request, a
button, requests opening, the stream going live and the window being shown or focused all wake the loop at once (focus
at most every 3 s). Devices are read when nothing is active: at once, then after 6, 12, 24 ... s, at most every 2 min (30 min while the window is
hidden and no stream is on). Spotify's queue: on another song, after a hand-over, skip or previous, and every 2 min while playing, never
while nobody needs it. Spotify's recently played: every 10 min, once while the window is hidden and
no stream is on. The saved position stays every 30 s.

A failed save is still retried every 3 s between polls.

## 2. Waiting

All Spotify Web API calls go through one gate. A `429` without a quota reason and with a wait up to 60 s holds every
call; a quota `429` (or a longer wait, which the 30 s window cannot explain) holds that endpoint only, as Spotify's
buckets are not known. While held nothing is sent and the caller gets the rest of the wait. Without a usable
`Retry-After` the wait grows: 5 s doubling to 15 min (quota: from 60 s). A new login lifts it. The token endpoint's
`429` is honoured too. The wait survives sleep (wall time) and ends when the clock was set back.

A refused extra read (queue, devices, recently played) under a quota no longer stops the polls; under a plain wait the loop
is deaf to wake-ups only for that wait. A logout or a dead login lifts every wait, so it shows at once. A handed-over
request that left Spotify's queue is not settled while the recently played must wait; with that quota used up it goes
back to the head: rather played twice than, paid for, never.

## 3. Showing it

Title bar, tray and Connections: "Spotify-limiet bereikt, weer om 14:32" (with the weekday when not today); a plain wait
under a minute keeps the old text. While adding to the queue is held it stays shown. Log: whichever call was refused, one row per stretch, "Spotify-limiet bereikt: <reason>", with the endpoint, Spotify's
`Retry-After` and the number of calls in the last 30 s and 24 h. No notification: the streamer cannot act on it.

## Known trade-offs

A pause, skip or seek made in Spotify itself shows up to 15 s later (at once when the window gets focus). A song
sought to its last seconds can move a request one song down. With crossfade the next song shows up to 5 s late.

## Measured

`PlaybackControllerPaceTest`, against a simulated Spotify, with the old pace in the same simulation:

| Situation | Old | New |
|---|---|---|
| An hour of music | 1,568 | 326 |
| An hour of music, 17 requests | 1,603 | 361 |
| An hour paused | 1,201 | 122 |
| An hour with nothing active | 1,863 | 102 |
| An hour of music nobody follows | 1,202 | 61 |
| A day in the tray | 43,203 | 349 |
