---
map: wake-word
ticket: "02"
title: "The Settings toggle that nothing currently writes"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The Settings toggle that nothing currently writes

## Question

Nothing to decide. `WakeWordPreferences` is read by `WakeWordEngine` and written by **nothing** -
the feature cannot be turned on. This ticket makes it reachable, and it unblocks the measurement
ticket, which cannot run an engine that will not start.

Add a wake-word row to `ui/SettingsScreen.kt` following the existing `ui/SettingsRows.kt`
conventions and the pattern `service/AssistantIgnition.kt` already sets:

- The toggle handler is the **only** writer of the preference. Consent changes in exactly one place.
- Off by default. It stays a supplement to push-to-talk, never a replacement.
- Flipping it on must call through to `WakeWordEngine.refresh(context)` so the running service picks
  it up without a restart - `AriaForegroundService:391` already exposes that path.
- The row says what it costs in plain words once ticket 03 has a number. Until then it must not
  claim a cost it does not know.

**Verification:** flip it on, flip it off, kill the app, reopen it, confirm the state survives and
that `WakeWordEngine` actually starts and stops. Read the state back from the device, not from the
UI - a toggle that renders On while the engine is dead is precisely the bug
`AssistantIgnition.resumeIfEnabled` exists to fix.
