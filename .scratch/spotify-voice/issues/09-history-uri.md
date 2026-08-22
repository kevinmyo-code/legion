---
map: spotify-voice
ticket: 09
title: "legion_history can name a track it can never replay"
type: task
status: closed
status-detail: "Closed 2026-08-22 (Kevin): verified on a real drive - all Spotify functions work."
blockers: ["02"]
blocked-by: ["[[02-app-remote-spine]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# legion_history can name a track it can never replay

## Question

`MusicPlayHistoryEntry.spotifyUri` is **always null**, and the code says why
(`media/NowPlayingController.kt:161-163`): MediaSession metadata carries no URI and App Remote's
player state was never wired in. So `browse_my_music(legion_history)` can tell Kevin what he heard on
a drive last week and **can never play it back**.

Ticket 02 wires `subscribeToPlayerState`, which carries the URI. This ticket spends it.

## Scope of the build

1. **Fill `spotifyUri` from App Remote player state** when Spotify is the source. Non-Spotify audio
   keeps writing null, correctly - there is no URI to have.
2. **"Play that thing from Tuesday" works** off a history row that carries a URI.
3. **Old rows stay null and are NOT backfilled by searching their titles** (Kevin, 2026-08-19). A
   search-derived URI is a guess, and a guess that plays the wrong song is worse than an honest
   refusal.
4. **A null-URI row is spoken as nameable but not replayable**, in words. Never silently skipped,
   never silently searched.

## Verification

- [ ] Play something through LEGION, then confirm the new row carries a URI. Pull the DB with its
      `-wal` and `-shm`.
- [ ] Ask to replay something from history; the right track plays. `on-device`.
- [ ] Ask to replay a pre-existing null row; the refusal is honest. `on-device`.
- [ ] Non-Spotify audio still logs with a null URI and does not error.
