---
map: alfred-builds
ticket: "08"
title: "One build at a time, with a heat check"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-builds-worker; owed: worker restart, engine deploy, the run (ticket 09)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# One build at a time, with a heat check

## What
- **Lock.** `tools/claude_session/build_lock.py`, a file lock in the worker root. Every Gradle and full-suite run in a worker task takes it, and other tasks wait (reported as "waiting for another build").
- **Heat check.** Before and during a build, read `laptopctl` status (the agent's local API, or `mcp__laptopctl__thermal_status` if the helper has it). Above 92 C, pause between Gradle tasks until it is under 80 C. No CPU cap (Kevin, 2026-11-05: "dont cap the cpu").
- **Gradle flags:** a private daemon registry dir per worker, `--stop` after.
## Verification
- tools tests: lock contention, the heat pause with a fake reading.
