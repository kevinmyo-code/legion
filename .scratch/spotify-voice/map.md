---
map: spotify-voice
title: "Map: Spotify voice control"
charted: 2026-08-19
charted-by: "Kevin + Opus"
effort: "`.scratch/spotify-voice/`"
tickets: 12
open: 12
status: open
tags: [map]
---
# Map: Spotify voice control

## Destination

**Music is fully drivable by voice, and the assistant never claims a playback action it did not
take.** Built, installed on the A25, and ready for Kevin to test on the unit. Not a decision map -
the end state is a hash-verified install he can drive with.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v26), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** Kevin, 2026-08-19: *"lets work on improving the spotify voice control
experience."* Scope call, asked and answered the same session: **widest** - podcasts and audiobooks,
playlist writes, follows. Not the conservative no-re-auth version.

### What exists today - traced 2026-08-19, not remembered

| Piece | State |
|---|---|
| Voice tools | Four: `control_music` (play/pause/next/previous), `control_volume`, `play_music` (by name), `browse_my_music` (5 sources) |
| Scopes held | `user-read-private user-library-read user-read-recently-played user-top-read` (`media/SpotifyWebApi.kt:120`) |
| **Playback scopes held** | **NONE.** No `user-modify-playback-state`, no `user-read-playback-state`. |
| How playback happens | App Remote (`media/SpotifyController.kt`) or MediaSession transport (`media/MusicController.kt`). **Never the Web API.** |
| Web API endpoints called | `/v1/search`, `/v1/me/albums`, `/v1/me/player/recently-played`, `/v1/me/top/{type}` |
| LEGION's own history | `MusicPlayHistoryEntry`, written by `media/NowPlayingController.kt:155` from MediaSession observation |
| Re-auth surface | `ui/SpotifyScreen.kt:94` reads `hasStaleGrant`, renders "Needs re-approving" |

**The structural fact that shapes every ticket: holding no playback scopes means LEGION can only
drive the Spotify app that is bound on THIS phone.** It cannot see what is playing on another
device, cannot transfer playback to the car, and cannot act at all when nothing is currently
playing and App Remote will not bind.

### Known defects carried in, both already documented in the tree

1. **`MusicPlayHistoryEntry.spotifyUri` is always null** (`NowPlayingController.kt:161-163`).
   MediaSession metadata carries no URI and App Remote's player state was never wired in, so
   `legion_history` can NAME a track it saw but can never replay one.
2. **Nothing in the Spotify layer has ever run against a real account.** The four library endpoints,
   album and playlist search, and the re-auth flow are all `reasoned`, never `tested`. `LIBRARY_LIMIT
   = 10` was inherited by inference rather than probed.

### Standing preferences for this effort

- **Kevin is at the abstraction layer.** Bring him forks with real cost or taste; decide
  implementation without asking.
- **A tool result must reflect what actually happened.** CLAUDE.md sec 7's outcome-verb rule
  (`ai/AriaBrain.kt` `CANNOT_CLAUSE`, ADR 0031) binds every tool this map adds: a play, a queue, a
  like or a seek that did not happen must be reported as not having happened.
- **The driver is driving.** A capability that needs a tap is close to useless here.
- **Install and drive.** Nothing on this map is done because it compiles.

### Settled, carried in - binding on every ticket

| # | Decision | Consequence |
|---|---|---|
| 1 | **Widest scope** (Kevin, 2026-08-19, asked directly): podcasts and audiobooks, playlist writes, follows are all in. | More scopes, one more re-approval, a bigger surface to get wrong in a car. |
| 2 | **A re-auth is acceptable and is done at a desk, never discovered in the car.** Precedent: drive-test ticket 05, same trap, same answer. | Any scope change ships with the Setup screen telling him in words, before he drives. |
| 3 | **No Kevin-hosted anything** (CLAUDE.md sec 7). PKCE on the driver's own device only. | Nothing needing a client secret or a server is available, however convenient. |
| 4 | **App Remote is the SPINE, not the fast path** (2026-08-19, from the research). It creates an active device rather than needing one. | Web API resolves a name into a URI; App Remote makes sound come out. The cold case - asking for music with Spotify closed - is solvable only this way. |
| 5 | **The connection is HELD in the foreground service** (Kevin), against Spotify's documented guidance to disconnect when backgrounded. | A per-command connect costs latency on every utterance. The violation is deliberate and is written into the code. |
| 6 | **Clone-and-run is satisfied by BYO client ID** (Kevin): each user registers their own Spotify app. | Dev mode's 5-user cap, owner-Premium requirement and permanently-closed extended quota stop being LEGION's problem. Nothing is hosted and nothing is shared. |
| 7 | **Premium is assumed** (Kevin): "no point for non premium". | Every `/me/player` WRITE is Premium-gated; the UX is designed for it rather than degrading around it. `canPlayOnDemand` exists only to TELL a non-Premium account, not to build a second experience. |
| 8 | **Podcasts and audiobooks are OUT** (Kevin, 2026-08-19: "forget podcasts for now"). | Also removes the unverified question of whether App Remote plays episode URIs at all. |
| 9 | **Creating playlists by voice is OUT; adding to one is IN.** Following an artist is IN. | A playlist created at speed is a thing done twice. |
| 10 | **The recommendation engine is BUILT BY US** (Kevin: "we build our own rec engine, we take the effort now"). | Spotify's discovery surface is dead for apps registered after 2024-11-27. Ticket 10 decides what "like this" may mean; 11 builds it. |

## Decisions so far

<!-- one line per closed ticket -->

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **A preferred-device memory** (the car's own speaker), so playback follows the driver into the car
  without being asked. Ruled out of this map: it needs device naming, and it fails silently when the
  car is off. Ticket 02's switch-to-local covers the case that actually bites.
- **What a stored dislike is.** Raised inside ticket 10 as a fork; cannot be specified until that
  ticket settles whether a recommendation writes anything at all.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Reintroducing mixtapes or the music-taste ledger.** Both retired in the pivot.
- **Any music source that is not Spotify or the phone's own MediaSession.**
- **Podcasts and audiobooks.** Settled decision 8.
- **Creating playlists by voice.** Settled decision 9.
- **Applying for Spotify extended quota.** Permanently closed to LEGION - since 2025-05-15 Spotify
  accepts applications only from organisations with a registered business and 250k+ MAU. Settled
  decision 6 is the answer instead.
