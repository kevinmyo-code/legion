---
map: wake-word
ticket: "03"
title: "What always-on Vosk actually costs the A25 in a day"
type: task
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-the-settings-toggle]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# What always-on Vosk actually costs the A25 in a day

## Question

Kevin's call, 2026-08-20: **always on, but measure first.** This ticket produces the number that
scopes the rest of the map. It decides nothing itself.

Measure, on the real A25, not an emulator:

1. **Battery drain per hour** with the wake word on, screen off, phone idle, on battery. Compare
   against a matched control window with the wake word off. `dumpsys batterystats` and
   `dumpsys batterystats --charged com.kevin.legion` give the per-uid figure; Battery Historian is
   optional, the raw delta is enough.
2. **CPU time** the Vosk thread accumulates over that window.
3. **Whether it survives the window at all** - a recognizer that dies quietly after twenty minutes
   makes the drain number meaningless and is itself the finding.
4. **Thermal**, if anything shows up. The A25 is not a fast phone.

Run it long enough to be real. **An hour minimum, overnight preferred**, and say which it was.

**Report the number even if it is embarrassing.** The point of measuring first is that the number is
allowed to change the plan; a measurement taken to confirm a hope is not a measurement. Record the
exact window, the starting and ending battery percentage, and whether the phone was left alone.

Assumptions ledger required, per `CLAUDE.md` sec 8: tag every claim `built` / `tested` / `traced` /
`reasoned` / `on-device`.
