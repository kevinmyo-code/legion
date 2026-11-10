---
map: nav-quality
ticket: "12"
title: "Build: NAV_AUDIT in the engine's device_logs render"
type: build
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: NAV_AUDIT in the engine's device_logs render

NAV_AUDIT lines reach the engine in `reason=conversation` batches. But `devicelogs/render.py` calls
`voice_audit.timeline`, which reads only `LIVE_AUDIT` and `LIVE_LATENCY`. So `--render` and
`GET /api/device-logs/<id>/render` do not show NAV_AUDIT lines, though they are stored.

## Change

- Copy `tools/nav_audit_report.py` into `server/devicelogs/` byte for byte, with the same
  drift test `voice_audit.py` has.
- Add `manage.py device_logs --nav`, which prints the report over the selected batches. Read-only.
- **Never edit `devicelogs/voice_audit.py`.** It is a byte copy of the tool, and a test fails on drift.

## Proven by

Running `device_logs --reason conversation --nav` on the engine prints the same report the tool
prints on the same batches.
