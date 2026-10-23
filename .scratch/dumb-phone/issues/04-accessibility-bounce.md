---
map: dumb-phone
ticket: "04"
title: "Build: accessibility bounce"
type: build
status: built
status-detail: "Suite green 2026-10-22. Owed on the A25: the bounce itself and its loop-freedom, Allow restricted settings for a sideloaded build, the contacts and folder pickers during the grace window. OPEN QUESTION for Kevin: does a chosen person's notification tap open the app? (BounceAllowance.ALLOW_APP_AFTER_CHOSEN_PERSON, off)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: accessibility bounce

`AccessibilityService` on TYPE_WINDOW_STATE_CHANGED. Bounce ONLY a package with a LAUNCHER activity,
not kept, not LEGION. Never a package without a launcher entry, never during a call, never Settings
(friction, not lock). Bounce shows LEGION's Bounce screen, then Back to home.

Verify: bounce decision function tests. Owed on the phone: the bounce itself; the "Allow restricted
settings" step for sideloaded apps.

## What was decided while building (not in Kevin's rulings; each is one line to undo)

- **Never bounced, beyond the brief's list:** the system Settings AND whichever app answers the Home
  intent. Bouncing the home app (if LEGION is not the default home) would loop: Home, bounce, Home.
- **A grace window (2 minutes) opens when LEGION itself launches a picker** (the contacts picker for
  chosen people, the Books folder picker). The picker is another package with a launcher entry and
  would otherwise be bounced. In memory, lapses by itself.
- **Per-package debounce (1.5 s)**: one app opening fires several window events; the bounce screen
  appears once.
- **Sequence**: Home is pressed first, then the bounce screen is shown over LEGION's home, so Back on
  the bounce screen cannot return to the hidden app.
- **OPEN QUESTION (not answered by the rulings):** when a chosen person messages on a hidden app
  (WhatsApp), does tapping that notification open the app, or bounce? Built literally (bounce). The
  switch is `BounceAllowance.ALLOW_APP_AFTER_CHOSEN_PERSON` (false); true opens the app for 10 minutes
  after each chosen-person message on it.
