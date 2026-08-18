---
map: cyberdeck-ui
ticket: 13
title: "Build: shell, hard keys, status line, boot"
type: task
status: resolved
status-detail: ""
blockers: ["12"]
blocked-by: ["[[12-build-theme]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: shell, hard keys, status line, boot

## Question

Implement ticket 05's shell: five stencil hard-keys (HOME/BIO/LOG/FLEET/CRED) over the existing
NavigationBar wiring, active key inverted amber; global status line (sync/OBD/key + clock + the
one blinking cursor) on every screen; Alfred strip stays in its bottomBar-slot anchor. Ticket
04's motion primitives land here too: cold-start boot sequence (~800ms, tap-to-skip), the shared
one-shot draw-in spec, reduced-motion collapse. Verify: animator scale 0 renders a complete UI.

## Answer

Built 2026-08-08 (coding agent). Hard-key row (HOME/BIO/LOG/FLEET/CRED, presentation-only over
existing routes), global StatusLine mounted once in the shell (SYNC/OBD/KEY worded, 4s poll -
none of the three sources exposes a Flow; clock per-minute), BootOverlay.kt cold-start-once with
tap-to-skip, never composed when motion disabled. compile + tests green (tested). Known honest
gap: SYNC segment means CONNECTED, not recently-succeeded - SyncEngine exposes no persisted
last-sync outcome; follow-up candidate, not invented state. Deferred to ticket 21: boot feel,
status truth on hardware, key-row height vs old NavigationBar.
