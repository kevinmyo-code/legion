---
map: spotify-voice
ticket: 06
title: "Shuffle, repeat, and moving inside a track"
type: task
status: closed
status-detail: "Closed 2026-08-22 (Kevin): verified on a real drive - all Spotify functions work."
blockers: ["02", "03"]
blocked-by: ["[[02-app-remote-spine]]", "[[03-tool-surface]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Shuffle, repeat, and moving inside a track

## Question

Three transport capabilities the driver has to reach for the phone to use today.

App Remote is the better route for all three, for a documented reason: it offers **toggle**
(`toggleShuffle`, `toggleRepeat`) while the Web API has only set - which forces a read first to
compute the new state, and the Web API's own pages warn that "the order of execution is not
guaranteed when you use this API with other Player API endpoints."

## Scope of the build

1. **Shuffle**: `shuffle_on` and `shuffle_off` explicit, plus bare "shuffle" mapping to
   `toggleShuffle`. Speak the state that resulted, read back, never the state that was requested.
2. **Repeat**: `repeat_off`, `repeat_track`, `repeat_context`. "Repeat this" is the track; "repeat
   the album" is the context. The distinction is real and the driver means one of them.
3. **Seek**: `seek_forward` and `seek_back` with an optional `seconds` (default 30), plus `restart`.
   Use `seekToRelativePosition` so no read is needed first. **Seeking past the end starts the next
   song** - stated in Spotify's own docs, so either say it or clamp it, but never let it surprise
   the driver.
4. **Every one of these reports the state that actually resulted**, from player state.

## Verification

- [ ] Shuffle on, off, and bare toggle; each spoken state matches the Spotify app. `on-device`.
- [ ] Repeat track and repeat album behave differently and are described differently. `on-device`.
- [ ] "Back 30 seconds" mid-track lands about 30 seconds earlier. `on-device`.
- [ ] Seek forward near the end of a track: what the driver was told matches what happened.
      `on-device`.
