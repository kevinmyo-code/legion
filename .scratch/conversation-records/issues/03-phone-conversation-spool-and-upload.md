---
map: conversation-records
ticket: "03"
title: "Build: phone spools LIVE_AUDIT and LIVE_LATENCY to disk and uploads them as conversation"
type: build
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-all-conversation-logs-on-the-engine]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Build: phone conversation spool and upload

To be built on the phone branch (`feat/async-read-tools` or its successor) by another agent.

## What to build

- Every `LIVE_AUDIT` and `LIVE_LATENCY` line the phone writes to logcat is also appended to a
  bounded file spool on the phone, in logcat `threadtime` shape
  (`MM-dd HH:mm:ss.SSS pid tid I TAG: message`), so `tools/voice_audit.py` reads the spool as it
  reads a capture.
- Batches upload to `POST /api/device-logs/` with `reason=conversation` (the engine side is built in
  ticket 02: migration `devicelogs/0003`), gzip body, the same multipart fields the head unit sends
  (`headunit/.../diag/DriveLogUploader.kt`). Seal and send at session end and on a periodic tick;
  delete a batch only on a 2xx; keep it offline and retry.
- The "Conversation audit log" setting already gates the lines; it gates the spool too.
- Section 7: the spool carries the lines `LiveAudit` already redacted; nothing third-party is added.

## Verification

- JVM test: a spooled batch is byte-identical to the lines `LiveAudit` emitted, and the engine's
  parser (`server/tests/test_device_logs_render.py`) renders it.
- On the A25: a conversation, then `manage.py device_logs --reason conversation --render`.
