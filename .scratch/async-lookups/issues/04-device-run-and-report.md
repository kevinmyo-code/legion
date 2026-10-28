---
map: async-lookups
ticket: "04"
title: "Test: the A25 run that proves WHEN_IDLE speaks, and the lookup p50/p95"
type: test
status: open
status-detail: "Waits on 02 and 03 merged into one fast build"
blockers: ["02", "03"]
blocked-by: ["[[02-non-blocking-read-tools]]", "[[03-fallback-model-and-probes]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Test: the A25 run

Kevin installs the merged `fast` build (`assembleFast`, `install -r`,
`adb shell cmd package compile -m speed -f com.kevin.legion`) with
`adb logcat -v threadtime -s LIVE_LATENCY > live.txt` running on the host.

## Questions, in order

1. **Does `WHEN_IDLE` speak on `gemini-3.8-live`?** Ask "any trouble codes?" (or any `ask_fleet`
   question). Expected: a holding line, silence, then the answer in Alfred's voice with no second
   tap and no second question. The `tool_ms ... async=true` line's send time is after the holding
   line's first audio. If the answer never comes, the setting ships OFF and ADR 0065 records that
   3.8-live treats `WHEN_IDLE` as `SILENT`.
2. **Does the holding line stay empty of facts?** Five lookups across fleet, body, goals, pantry.
   Any figure spoken before the result is a prompt bug, ticket 02 step 7.
3. **Barge-in during a lookup.** Interrupt the holding line. Expected: Alfred stops, the lookup's
   result is not spoken over the new turn, and nothing is reported as undone.
4. **Follow-up window.** After the answer, stay silent 8 s. Expected: the window closes as it does
   today. Then ask, get the holding line, stay silent through the lookup: the window must not close
   while the tool is in flight.
5. **Fallback.** Cannot be forced on demand; if a `subagent_fallback` line appears during the
   session, note which tool and whether the answer was good.

## Report

`python tools/voice_latency.py live.txt`: the Lookups table (p50/p95 per tool: total, router POST,
fan-out), `reply_ms`, `eot_reply_ms`. Paste the table into this ticket's status-detail and into
`memory/library/decisions.md` under the 2026-10-28 entry. Only after this does anyone touch the
42 s / 32 s / 45 s budgets, and only with these numbers beside the change.
