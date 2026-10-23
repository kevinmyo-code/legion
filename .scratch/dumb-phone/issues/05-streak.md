---
map: dumb-phone
ticket: "05"
title: "Build: streak and time-in-dumb counter"
type: build
status: built
status-detail: "Suite green 2026-10-22. Grayscale was ruled in and dropped the same day (Kevin), so nothing here touches system settings. Owed on the A25: the status line reads right across a real midnight"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: streak and time-in-dumb counter

**Grayscale was part of this ticket and is dropped (Kevin, 2026-10-22).** It was never committed: no
secure-settings write, no `WRITE_SECURE_SETTINGS`, no adb step. The look moved to ticket 06 (Matrix
code rain).

Streak day = dumb mode on for at least 60 min total that local day; consecutive days. The streak
measures time AWAY from the phone, the one streak CLAUDE.md section 7 allows.

Verify: streak tests across midnight and the 60-min rule.

## What was decided while building

- **A streak counts consecutive qualifying days ending today, or ending yesterday while today has not
  yet reached an hour.** A day he is still on his way to qualifying does not read as a broken streak.
  A fully missed day resets it to zero. A run across local midnight is split at midnight.
- **The status line reads "dumb for 2h 14m" and adds " · N days in a row" only from one day on.**
