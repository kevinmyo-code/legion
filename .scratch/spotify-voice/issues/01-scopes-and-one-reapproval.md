---
map: spotify-voice
ticket: 01
title: "Every scope this map needs, taken in one re-approval"
type: task
status: open
status-detail: "Built (ad353a2). SCOPES 4 -> 13. THE GRANT IS NOW STALE - re-approve in Setup before driving. NOT installed, NOT verified on the phone."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Every scope this map needs, taken in one re-approval

## Question

`isAuthorized` compares the granted scope string against `SCOPES` by equality
(`media/SpotifyWebApi.kt:152`), deliberately, so a stale grant reads as unauthorized rather than
minting tokens the API will refuse. **Every scope added therefore invalidates Kevin's current grant
and hard-fails music until he re-approves in a browser** - the one thing that cannot be done from
the driver's seat.

Drive-test ticket 05 hit this exact trap and answered it the same way: re-auth happens at a desk,
deliberately, never discovered in the car. This ticket makes that true **once** for the whole map
instead of once per feature.

## Scope of the build

1. **Take every scope this map will ever need, in one edit.** Current:
   `user-read-private user-library-read user-read-recently-played user-top-read`. Add:
   - `user-modify-playback-state` - every playback WRITE (tickets 04, 06)
   - `user-read-playback-state` - device and player reads (ticket 07)
   - `user-read-currently-playing` - the queue read needs it AND read-playback-state
   - `user-library-modify` - like/unlike and follow-as-save (ticket 05)
   - `playlist-read-private`, `playlist-read-collaborative` - his own and friend-shared playlists
   - `playlist-modify-private`, `playlist-modify-public` - add-to-playlist (ticket 08)
   - `app-remote-control` - App Remote (ticket 02)
2. **No scope is added later in this map.** A second re-approval mid-map is the failure this ticket
   exists to prevent. If a later ticket discovers a missing scope, that is a defect in THIS ticket.
3. **Setup says it in words before he drives**, reusing the existing `hasStaleGrant` path
   (`ui/SpotifyScreen.kt:94`), which already renders "Needs re-approving". Confirm the copy still
   reads correctly for a much larger scope jump.
4. **Both music tools fail in words naming the same cause** while the grant is stale - already the
   shape `play_music` uses; confirm `control_music` does too.

## Verification

- [ ] Grant is stale immediately after the scope change, and Setup says so **before** any tool is
      called. `on-device`.
- [ ] Both music tools fail with the re-approve message, not a generic error. `on-device`.
- [ ] The browser hop completes and both recover. `on-device`.
- [ ] Confirm on the Spotify Dashboard whether LEGION's Client ID predates 2026-02-11 - it decides
      whether the Feb-2026 endpoint cull applies to us today. Flagged unresolved in the research and
      cheap to settle.
