---
map: spotify-voice
ticket: 07
title: "What's this, and naming what it picked"
type: task
status: closed
status-detail: "Closed 2026-08-22 (Kevin): verified on a real drive - all Spotify functions work."
blockers: ["02", "03"]
blocked-by: ["[[02-app-remote-spine]]", "[[03-tool-surface]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What's this, and naming what it picked

## Question

Two halves of one problem: the assistant does not reliably know what is playing, and it does not say
what it chose.

Now-playing comes from MediaSession metadata (`media/NowPlayingController.kt`), which is whatever
the player app chose to publish. App Remote's `subscribeToPlayerState` is Spotify's own truth,
pushed, no polling, no quota.

The second half is the honesty half. `play_music` searches and plays the top hit. **When search
picks the wrong thing the driver finds out four bars in** - the class of failure ADR 0031 exists
for, except here nothing lies, it is simply silent.

## Scope of the build

1. **Now-playing reads App Remote player state when Spotify is the source**, falling back to
   MediaSession for everything else. MediaSession stays for non-Spotify audio.
2. **`play_music` names what it picked, once, briefly** - "Discovery, Daft Punk" - as it starts.
   Kevin's call, 2026-08-19. Not a paragraph, and not on every track change.
3. **A "what's this" path** answering from player state, including the album, since the driver
   asking usually wants to find it again later.
4. **When search returns nothing, that is spoken as nothing found**, never as a play that happened.

## Verification

- [ ] Ask what is playing while Spotify plays; the answer matches the Spotify app exactly.
      `on-device`.
- [ ] Ask while a NON-Spotify app plays; MediaSession still answers. `on-device`.
- [ ] Ask for an album by name; the spoken confirmation names what actually started. `on-device`.
- [ ] Ask for something that does not exist; it says so. `on-device`.
