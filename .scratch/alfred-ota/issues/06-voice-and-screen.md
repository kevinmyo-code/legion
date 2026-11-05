---
map: alfred-ota
ticket: "06"
title: "Updates by voice and on a screen"
type: build
status: open
status-detail: "charted 2026-11-05; after 05"
blockers: ["05"]
blocked-by: ["[[05-phone-updater]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Updates by voice and on a screen

## What

- **Voice tool `app_update`**, with actions `check`, `install` and `whats_new`.
  - `install` runs only after Kevin's yes in that same turn.
  - The result says in words what did and did not happen (§7 outcome verbs).
- **Settings screen "Updates".** ADR 0035: every voice capability has a hands path, and both call the same controller. The screen shows:
  - the current version
  - the latest available version and its notes
  - Check now and Install buttons
  - an "Allow mobile data" toggle
  - "Alfred's wording: pack v<N> from engine|cache|built-in"
- **User guide.** Add copy for `docs/voice.html` in `tools/voice_guide_copy.py`.

## Verification

- JVM tests cover the tool's refusals.
- The voice guide script passes.
