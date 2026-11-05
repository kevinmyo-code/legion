---
map: alfred-builds
ticket: "02"
title: "Conversation records the worker can read"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-builds-worker; owed: worker restart, engine deploy, the run (ticket 09)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Conversation records the worker can read

## What
- **`GET /api/device-logs/<id>/render`** (household members, so the worker's household token):
  - Returns the rendered conversation (user lines, Alfred lines, tool calls and results, timings), the same format as `manage.py device_logs --render`.
  - Mail stays redacted exactly as it is stored.
- **`GET /api/device-logs/?reason=conversation&since=`** lists recent records (ids, device, time, line count).
- Tenancy-scoped, with a test. Recruiter visit records are never readable by a member token.
## Verification
- pytest: scoping, redaction preserved, render matches the command's output.
