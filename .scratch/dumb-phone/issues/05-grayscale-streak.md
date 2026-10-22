---
map: dumb-phone
ticket: "05"
title: "Build: grayscale and streak"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mode-home-leave]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: grayscale and streak

Grayscale via `Settings.Secure` daltonizer (monochromacy), previous values restored on leave; needs
`WRITE_SECURE_SETTINGS` granted by adb, said in words when missing. Streak day = dumb mode on for at
least 60 min total that local day; consecutive days. The streak measures time AWAY from the phone,
the one streak CLAUDE.md section 7 allows.

Verify: streak tests across midnight and the 60-min rule. Owed on the phone: grayscale.
