---
map: async-lookups
ticket: "05"
title: "Build: conversation audit log in logcat (LIVE_AUDIT)"
type: build
status: built
status-detail: "Built 2026-10-28 on feat/async-read-tools. LIVE_AUDIT lines, mail turns redacted, setting default on, tools/voice_audit.py. 5441 tests, 0 failures; assembleFast builds. Owed: an A25 capture read through voice_audit.py (ticket 04 run)."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: conversation audit log in logcat

**Kevin, 2026-10-28**, after testing the non-blocking lookups on the A25: *"cant u like record what
i say in the logs too so u can see everything and audit"*. A `LIVE_LATENCY` capture shows timings
and tool names but not what the user said, what the assistant said, or what a tool was asked and
returned. The `fast` build is release-shaped, so Room is not readable over ADB. This log is how a
run is audited. Phone only, branch `feat/async-read-tools`.

## What was built

- `service/LiveAudit.kt`: one `LIVE_AUDIT` logcat line per event, written through
  `LiveLatency.logcat` (logcat only, never `CarProbeLog`). Free text always last, JSON-quoted,
  400-character cap with a `...` marker; keys, tokens and Authorization values scrubbed.
- `GeminiLiveSession` calls it at each event: transcripts, tool calls and results, cancellations,
  turn complete, interrupted (server and barge-in), activity start/end, mic open/closed, session
  start/end.
- "Conversation audit log (logcat)" in Assistant settings, beside "Answer lookups while talking".
  Default ON (Kevin asked for it). Off writes no `LIVE_AUDIT` line. Read once per socket.
- `tools/voice_audit.py`: a per-session timeline of a capture, and `--summary` (turns, tools,
  async tools, holding lines). `tools/voice_latency.py`'s capture command now includes
  `LIVE_AUDIT`.

## Line formats

```
session_start model=<id> async_set=<a,b|none>
session_end reason="<text>"
user final=<bool> [typed=true] text="<text>"
assistant text="<text>"
tool_call id=<id> name=<name> async=<bool> args=<json>
tool_result id=<id> name=<name> success=<bool> ms=<n> scheduling=<WHEN_IDLE|none> sent=<bool> response=<json>
tool_cancelled id=<id> name=<name>
turn_complete
interrupted source=<server|barge_in>
activity_start
activity_end
mic_open reason=<first_open|turn_handback>
mic_closed reason=<half_duplex|park|session_end|superseded>
```

`user` puts `final=` before `text=` (the brief listed it after) so the free text is always the last
field. Voice chunks carry the transcription's own `finished` flag; at turn complete the whole turn is
logged once more with `final=true`, which `voice_audit.py` drops when it matches the chunks.

## Sec 7, third-party content

A turn that ran a tool in `LiveToolbox.EPISODIC_EXCLUDED_TOOLS`, or that the session marked
read-through (a timed sitrep), logs its assistant speech as one
`assistant text="[redacted: third-party content]"` line, and the mail tool's own result as
`response="[redacted: third-party content]"`. Assistant chunks logged before the mail tool was asked
for stay in clear: no mail content exists yet when they are written. The user's own words and the
mail tool's args always log. Pinned in `LiveAuditTest`.

## Capture

```
adb logcat -v threadtime -s LIVE_AUDIT LIVE_LATENCY LIVE_FOLLOWUP LIVE_TOOL_CANCEL > live.txt
python tools/voice_audit.py live.txt
python tools/voice_audit.py live.txt --summary
```

## Owed

An A25 capture read through `voice_audit.py` (ticket 04's run can carry it).
