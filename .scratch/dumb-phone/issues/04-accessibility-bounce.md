---
map: dumb-phone
ticket: "04"
title: "Build: accessibility bounce"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-mode-home-leave]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: accessibility bounce

`AccessibilityService` on TYPE_WINDOW_STATE_CHANGED. Bounce ONLY a package with a LAUNCHER activity,
not kept, not LEGION. Never a package without a launcher entry, never during a call, never Settings
(friction, not lock). Bounce shows LEGION's Bounce screen, then Back to home.

Verify: bounce decision function tests. Owed on the phone: the bounce itself; the "Allow restricted
settings" step for sideloaded apps.
