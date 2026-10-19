---
map: retro-games
ticket: "06"
title: "Build: phone Tetris solo and race, background invite poll, local notification"
type: build
status: built
status-detail: "Suite green 2026-10-19 (phone 4696/0, server 1716 passed); owed on the A25: solo, a race vs the PWA, an invite while backgrounded"
blockers: ["04"]
blocked-by: ["[[04-tetris-rules-and-race]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: phone Tetris and the race

- **Rules** from ticket 04 as pure Kotlin under `games/tetris/`, no Android types; JVM tests read
  `shared/tetris/fixtures.json` (written by ticket 05).
- **Games sheet:** a built-in Tetris tile above the ROM shelves: Solo, and "Play <member>" per
  household member from `GET /api/games/opponents/`.
- **Screen:** Compose canvas board, hold, 5 next, ghost, score/level/lines, touch controls as the
  PWA's; a ViewModel with one `StateFlow<UiState>` (CLAUDE.md section 8).
- **Race:** invite, waiting screen (says declined/cancelled/expired in words), countdown to
  `start_at` corrected by `server_now`, the opponent's live score, result. API contract in ticket 05.
- **Invites to Kevin:** poll `GET /api/games/matches/pending/` every 30 s from a long-lived
  component (`AriaForegroundService` if it is always on; report what it actually is), raise a local
  notification "<name> invited you to Tetris" with Accept opening the match screen. Dedupe by match id.
- **Offline:** engine down means the race is unavailable, said in words; solo always works.

## Verification

compileDebugKotlin, testDebugUnitTest, detekt. Owed on the A25: solo, a race against Mia's PWA,
and an invite from her arriving while LEGION is backgrounded.
