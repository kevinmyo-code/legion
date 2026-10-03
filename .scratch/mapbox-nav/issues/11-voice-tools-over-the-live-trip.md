---
map: mapbox-nav
ticket: "11"
title: "Voice tools over the live trip, and the Google hand-off retired"
type: build
status: open
status-detail: ""
blockers: ["04", "05", "10"]
blocked-by: ["[[04-voice-tool-surface]]", "[[05-who-speaks-the-turns]]", "[[10-route-and-guidance]]"]
open-blockers: 3
ready: false
tags: [ticket]
---

# Voice tools over the live trip, and the Google hand-off retired

## Build

The tools from 04, calling the same controller the nav screen calls. Spoken turns per 05.

**In the same change:** `open_navigation`'s Google intent path, `NavigationController`, its test and
the `google.navigation` / `geo` `<queries>` entries are removed (ADR 0054). `tools/voice_guide_copy.py`
updated for every new tool.

## Verification

- Unit: every tool's failure result says what did not happen; trip status with no trip says
  "not navigating", never zeros.
- `python tools/voice_guide.py` exits clean.
- On the phone: each tool by voice, while guiding.
