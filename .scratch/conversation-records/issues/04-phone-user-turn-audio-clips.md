---
map: conversation-records
ticket: "04"
title: "Build: phone keeps user-turn audio clips with end-of-turn labels, capped and pullable"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-all-conversation-logs-on-the-engine]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Build: phone user-turn audio clips

Kevin, 2026-10-28: audio stays on the phone for now.

## What to build

- Keep each user turn's mic audio on the phone as a clip, labelled with how the turn ended (the
  phone's end-of-turn reason: local end of turn, server VAD, barge-in) and the session and turn it
  belongs to, so a clip can be matched to its `LIVE_AUDIT` lines.
- Capped by count and bytes, oldest dropped first.
- Pullable by hand (Settings, or `adb pull` from app storage), never uploaded.

## Open

- The cap, and whether clips are on by default.
