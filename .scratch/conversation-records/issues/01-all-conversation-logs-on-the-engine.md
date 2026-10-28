---
map: conversation-records
ticket: "01"
title: "Decision: all conversation logs go to the engine, from both clients"
type: decision
status: resolved
status-detail: "Resolved 2026-10-28, Kevin: all conversation logs on the engine, both clients; audio stays on the phone for now."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Decision: all conversation logs go to the engine, from both clients

## Question

The phone writes its conversation audit to logcat (`LIVE_AUDIT`, async-lookups ticket 05), readable
only over ADB. The head unit has no ADB and wrote a partial audit into its drive log. Where should a
conversation record live, and for which client?

## Resolution

Kevin, 2026-10-28: *"all logs on engine. both headunit and phone."*

- Both clients upload their `LIVE_AUDIT` and `LIVE_LATENCY` lines to the household's engine
  (`POST /api/device-logs/`, `reason=conversation`).
- One line format for both, so one parser renders either.
- Audio stays on the phone for now (ticket 04); it is not uploaded.
- Section 7 still binds: third-party content is redacted before a line is written.

Build tickets: [02](02-headunit-audit-parity-and-upload.md) (head unit),
[03](03-phone-conversation-spool-and-upload.md) (phone), [04](04-phone-user-turn-audio-clips.md)
(phone audio), [05](05-engine-conversation-view.md) (engine view).
