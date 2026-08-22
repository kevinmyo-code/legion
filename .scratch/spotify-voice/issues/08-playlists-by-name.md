---
map: spotify-voice
ticket: 08
title: "My playlists, and the ones friends shared with me"
type: task
status: closed
status-detail: "Closed 2026-08-22 (Kevin): verified on a real drive - all Spotify functions work."
blockers: ["01", "03"]
blocked-by: ["[[01-scopes-and-one-reapproval]]", "[[03-tool-surface]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# My playlists, and the ones friends shared with me

## Question

"Play my Roadtrip playlist" is a thing a driver says constantly and LEGION cannot do it reliably.
`play_music` routes playlist requests through search, which is **the wrong endpoint**: search ranks
the whole public catalogue, its limit is now capped at **10**, and it will not surface Kevin's own
playlist above a hundred public ones with similar names.

Kevin's scope call, 2026-08-19: **his own playlists, plus playlists friends have shared with him.**
Not the public catalogue.

Hard boundary from the research: **a playlist's items can only be read for playlists the user owns
or collaborates on.** A Spotify editorial playlist cannot be read at all, so "shared with me" means
collaborative or owned-by-a-person, never "Discover Weekly".

## Scope of the build

1. **Read his playlists once and cache them** as name to URI, with
   `playlist-read-private` + `playlist-read-collaborative`. Refresh on a schedule that does not cost
   a call per utterance.
2. **Fuzzy-match the driver's words against that cache first.** Fall through to search only when
   nothing matches, and say which one it used when the answer might surprise him.
3. **"Add this to <playlist>"** through the playlist items endpoint - note the migration, `/tracks`
   became `/items` and the response field `tracks` became `items`. Creating playlists by voice is
   deliberately OUT (Kevin, 2026-08-19).
4. **A playlist that cannot be read is said to be unreadable**, never silently skipped - the
   editorial boundary above WILL be hit and it must not read as "you have no such playlist".

## Verification

- [ ] Ask for one of his own playlists by name; the right one plays. `on-device`.
- [ ] Ask for one a friend shared; it plays. `on-device`.
- [ ] Ask for a name matching nothing; it says so. `on-device`.
- [ ] Add the current track to a named playlist; it lands in the Spotify app. `on-device`.
- [ ] Ask for an editorial playlist and confirm the failure is honest about why. `on-device`.
