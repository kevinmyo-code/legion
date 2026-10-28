---
map: conversation-records
title: "Map: every conversation reaches the engine, from both clients"
charted: 2026-10-28
charted-by: "Kevin + Opus"
effort: "`.scratch/conversation-records/`"
tickets: 5
open: 4
status: open
tags: [map]
---
# Map: every conversation reaches the engine, from both clients

## Destination

**Every voice conversation, on the phone and on the head unit, is readable on the household's own
engine as a timeline:** who said what, each tool call with its arguments, result and time, and how
long each reply took. Kevin, 2026-10-28: *"all logs on engine. both headunit and phone."* Audio
stays on the phone for now.

## Notes

**Domain:** LEGION (root `CLAUDE.md` binds) and `headunit/` (its own `headunit/CLAUDE.md`). One
format for both devices: the phone's `LIVE_AUDIT` and `LIVE_LATENCY` lines (async-lookups ticket
05, `app/.../service/LiveAudit.kt`, `tools/voice_audit.py`). One wire: `POST /api/device-logs/`
with `reason=conversation`, one batch holding only those lines. One reader:
`manage.py device_logs --reason conversation --render`, which runs the same timeline parser.

Section 7 (third-party content is read-through only) binds every sink: a turn that ran a tool in
`EPISODIC_EXCLUDED_TOOLS` logs `[redacted: third-party content]` for the assistant's speech and that
tool's result. The head unit has no mail tool and the engine's MCP exposes none today.

## Decisions so far

- [All conversation logs on the engine](issues/01-all-conversation-logs-on-the-engine.md) - Kevin,
  2026-10-28: both clients upload; audio stays on the phone for now.

## Built alongside

- [Developer tools on the head unit](issues/06-headunit-developer-tools.md) - Kevin, 2026-10-28:
  open Developer options and Wireless debugging from Settings, ADB status, a root attempt. Not part
  of the destination; it rode the same branch.

## Not yet specified

- Retention for conversation batches. Today they share `devicelogs.RETENTION_DAYS` (60) with drive
  logs.
- Whether a conversation batch should also be readable by a member without the operator command
  (ticket 05).

## Out of scope

- Uploading audio. Ticket 04 keeps user-turn clips on the phone only.
