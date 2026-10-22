---
map: dumb-phone
ticket: "01"
title: "Build: mode state, Home A, Leave flow, tile, settings row"
type: build
status: built
status-detail: "Suite green 2026-10-22 (4898/0). Owed on the A25: the look at 384dp, the tile, the leave wait across a lock, Maps/Books/Assistant from the list"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: mode state, Home A, Leave flow

Rulings in ADR 0062. `DumbModeController` is a constructor-injected class (no Hilt in the build yet,
architecture ticket 02; a holder on `MidnightApplication` is the injection point). State persisted:
on, startedAt, leaveRequestedAt, and the on/off interval history (slice 5 needs it).

- Home A Compose screen with a ViewModel, replacing HomeScreen inside the HOME destination while on.
- Leave flow: 60 s WALL-CLOCK wait from the tap, persisted (survives backgrounding and process
  death), then the typed sentence "I am choosing to leave dumb mode" (case-insensitive, trimmed).
  "Stay in dumb mode" is the primary button.
- Quick-settings tile (manual toggle) and a Settings row.
- Setup screen shell listing each one-time grant in words with a button to the system page.

Verify: unit tests for wall-clock timer across recreation, sentence match, no leave before 60 s.
Owed on the phone: the look at 384dp, the tile.
