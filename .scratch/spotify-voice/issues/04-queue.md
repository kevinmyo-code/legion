---
map: spotify-voice
ticket: 04
title: "Add this to the queue, and play this next"
type: task
status: open
status-detail: "Built (d56381a). queue + get_music_queue, the one net-new declaration on this map. NOT installed, NOT verified on the phone."
blockers: ["02", "03"]
blocked-by: ["[[02-app-remote-spine]]", "[[03-tool-surface]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Add this to the queue, and play this next

## Question

The highest-value voice capability in a car, and LEGION has none of it. A driver who hears something
and wants the next thing lined up has to pick up the phone.

`PlayerApi.queue(uri)` needs no active device and costs no Web API quota. The Web API's own queue
endpoint exists too (Premium, `user-modify-playback-state`) as the fallback when App Remote is not
bound.

## Scope of the build

1. **`control_music` action `queue`**, taking a `query` resolved through the same search path
   `play_music` uses, then `PlayerApi.queue(uri)`.
2. **"Play X next" and "add X to the queue" are the same thing** to Spotify - there is no
   insert-at-position. Say so in the description rather than implying an ordering the API does not
   offer.
3. **A queue read for "what's coming up"** - it needs BOTH `user-read-currently-playing` and
   `user-read-playback-state`, which ticket 01 takes.
4. **Success is derived from the call, never assumed** (ADR 0031). A queue that did not land is
   spoken as not landed.

## Verification

- [ ] Queue a named track while something plays; it plays next. `on-device`.
- [ ] Queue with Spotify cold (ticket 02's path): it either works or says it did not. `on-device`.
- [ ] Ask what is coming up; the answer matches the Spotify app's own queue screen. `on-device`.
