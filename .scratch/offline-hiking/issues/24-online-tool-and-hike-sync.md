---
map: offline-hiking
ticket: 24
title: "Build: hike sync to the engine, and Alfred's `hiking` tool"
type: build
status: open
status-detail: ""
blockers: ["08", "12"]
blocked-by: ["[[08-where-a-hike-lives]]", "[[12-voice-surface-while-online]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: hike sync to the engine, and Alfred's `hiking` tool

**Release:** v2. **Size:** M.

## What

- When signal returns, a finished hike uploads: the body session row, the GPX file, the waypoints as
  places (ticket 08). Queued like other phone writes; said in words while queued.
- The one `hiking` voice tool ruled in ticket 12, each action calling the regions or hike
  controller the screens call.

## Rules

- Engine first (ADR 0044): a Django endpoint and migration for the session and its GPX, household
  scoped, in `TENANT_TABLES`, covered by the tenancy leak test. Check migration numbers against open
  branches (devicelogs and claude_session clashes are already known).
- GPX stored as a file the engine owns (same bucket pattern as other uploads), never parsed by an LLM.
- Payload: measure `LiveSetupPayloadSizeTest` before and after; trim elsewhere first if needed.
- `tools/voice_guide_copy.py` gets the user-facing copy, or the guide script fails.

## Verification

- Server pytest for the endpoint, tenancy and visibility.
- Phone unit: queue and retry, the offline sentence.
- Compile, suite green; payload test green. On the phone: a hike recorded offline appears on the
  engine after signal returns. Owed.
