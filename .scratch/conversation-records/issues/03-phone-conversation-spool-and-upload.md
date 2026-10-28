---
map: conversation-records
ticket: "03"
title: "Build: phone spools LIVE_AUDIT and LIVE_LATENCY to disk and uploads them as conversation"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/phone-conversation-upload: spool, upload (reason=conversation, session end + 3 min + app start + Send now), two-switch setting, JVM tests incl. voice_audit.py render. Owes the A25 run: a conversation, then manage.py device_logs --reason conversation --render."
blockers: ["01"]
blocked-by: ["01"]
open-blockers: 0
ready: false
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

## Status (2026-10-28)

Built on `feat/phone-conversation-upload`, suite green, owing the A25 run.

- Spool: `app/.../service/conversationlog/ConversationSpool.kt` (4 MB cap, 512 KB batches, oldest
  first), written by `ConversationRecorder` on one thread from `LiveLatency.log` and
  `LiveLatency.audit`; only the four conversation tags.
- Upload: `ConversationUploader.kt` through `EngineHttp.postMultipart`; triggers in
  `ConversationLog.kt` (session end, every 3 minutes while live, app start, "Send now").
- Setting: "Conversation log" row, two switches, "Send now" with the result in words.
- Verification step 1 (JVM): `ConversationRecorderTest` proves a spooled batch is byte-identical to
  the lines `LiveAudit` emitted and renders it through `tools/voice_audit.py` (the same parser the
  engine's `--render` uses). Done.
- Verification step 2 (A25, then `manage.py device_logs --reason conversation --render`): owed.
