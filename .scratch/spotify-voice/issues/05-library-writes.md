---
map: spotify-voice
ticket: 05
title: "Like this, and follow them, without touching the phone"
type: task
status: built
status-detail: "Built (b8d5a90). like/unlike/follow/unfollow, getLibraryState read before speaking. NOT installed, NOT verified on the phone."
blockers: ["02", "03"]
blocked-by: ["[[02-app-remote-spine]]", "[[03-tool-surface]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Like this, and follow them, without touching the phone

## Question

Liking the song you are hearing is the single most common reason a driver reaches for the phone. App
Remote does it with **zero Web API calls**: `UserApi.addToLibrary(uri)`, `removeFromLibrary(uri)`,
and `getLibraryState(uri)` for the confirmation wording.

Following an artist is now the same operation. The old follow endpoints are **deprecated**, and
following is expressed as saving `spotify:artist:...` through the library endpoint - URIs, not IDs,
max 40 per call.

## Scope of the build

1. **`control_music` actions `like` and `unlike`**, operating on whatever is currently playing, read
   from `subscribeToPlayerState` (ticket 02).
2. **Follow and unfollow the current artist** through the library endpoint, NOT the deprecated
   follow endpoints.
3. **`getLibraryState` before speaking.** "Already liked" and "liked it" are different sentences and
   the driver can hear the difference; guessing produces a confident lie.
4. **Nothing is liked that the driver did not mean.** "Like this" is the current track only, never an
   album and never a whole queue.

## Verification

- [ ] Like the current track; it appears in Liked Songs in the Spotify app. `on-device`.
- [ ] Say "like this" twice; the second is spoken as already liked. `on-device`.
- [ ] Follow the current artist; confirm in the Spotify app. `on-device`.
- [ ] Unlike, and confirm removal. `on-device`.
