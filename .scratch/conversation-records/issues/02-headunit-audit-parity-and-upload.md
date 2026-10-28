---
map: conversation-records
ticket: "02"
title: "Build: head unit conversation audit at phone parity, spooled and uploaded as conversation"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/headunit-conversation-logs. Head unit 757 tests, 0 failures; assembleDebug builds. Server: migration 0003, --render, tests written; the database half was not run (no Postgres credentials in the session). Owed: server suite, engine deploy before APK install, a real drive, and device_logs --reason conversation --render of it."
blockers: ["01"]
blocked-by: ["[[01-all-conversation-logs-on-the-engine]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: head unit conversation audit at phone parity

## What was built

- **Landed from `fix/headunit-logs-and-spend`:** the periodic (3-minute) drive-log upload and the
  `periodic` reason (`5baef243`, `e92eada3`, `b1f4b5cf`). The spend work and the hand-off commit
  stay on that branch.
- `headunit/.../diag/ConvoAudit.kt`: the phone's `LIVE_AUDIT` line set, field order, 400-character
  cut with `...`, JSON quoting and key scrub. `diag/ConvoLatency.kt`: `cold_start_ms`, `reply_ms`,
  `tool_ms`. Wired through `service/GeminiLiveSession.kt`.
- A conversation spool beside the drive log (`DriveLog.conversationStore`, `filesDir/convolog`,
  bounded like the drive log). Lines go straight to disk in logcat `threadtime` shape, never through
  logcat and never Crashlytics.
- Upload as `reason=conversation`: at session end, on the 3-minute tick, at start, at drive end and
  from Settings > System > Drive logs. A batch is deleted only on a 2xx.
- Engine: `conversation` reason (migration `devicelogs/0003`), `manage.py device_logs --render`
  (`devicelogs/render.py`, through a byte-identical copy of `tools/voice_audit.py`).

## Head-unit differences, in values only

- `async=false` and `scheduling=none` always: no non-blocking tools on the head unit.
- `activity_start` / `activity_end` are the server's `voiceActivity` events (server VAD).
- `interrupted source=barge_in` is the driver's talk tap over the companion.
- `reply_ms` on a tool turn spans the whole round trip (the silent call turn keeps the start mark);
  the phone clears it on every turnComplete.
- The periodic tick can split one session across two batches; `--render` shows each batch on its
  own.

## Section 7

No head-unit tool and no engine MCP tool reads mail (`engine_mcp/tools.py`, pinned by its test), and
the engine reports no "touched mail" signal. `ConvoAudit.READ_THROUGH_TOOLS` is empty; the
redaction mechanism is the phone's and is tested with a stand-in tool.

## Owed

1. Server suite against Postgres (`uv run pytest`), including migrations 0002 and 0003.
2. Kevin's OK to deploy the engine (two migrations on the live DB) BEFORE installing the APK: an
   old engine refuses `conversation` with a 400 and the batch stays queued on the unit.
3. A real drive, then `manage.py device_logs --last 3 --reason conversation --render`.
