---
map: async-lookups
ticket: "04"
title: "Test: the A25 run that proves WHEN_IDLE speaks, and the lookup p50/p95"
type: test
status: built
status-detail: "Gate answered yes: WHEN_IDLE speaks on gemini-3.8-live (run 1 passed the gate, runs 2 and 3 confirm it); run 2 found two failures (late result denied, fleet router spent its round on discovery), both fixed; run 3 (2026-10-28 11:24-11:31) held both fixes, 6 of 8 async lookups answered, eight findings F1-F8 built in ticket 07. Still owes the p50/p95 report pasted into decisions.md; run 3 lookup p50/p95: ask_fleet 1,141/8,994 ms (n=6), ask_goals 772/829 ms (n=2), ask_body 511 ms (n=1)."
blockers: []
blocked-by: []
open-blockers: 0
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

## Runs (A25, gemini-3.8-live)

1. **Run 1: gate passed.** `WHEN_IDLE` speaks: after an async lookup the model ended its turn, the
   result arrived, and the model opened a new turn by itself. One mis-route ("any trouble codes" to
   `clear_codes`), proposed to Kevin in ticket 02.
2. **Run 2: two failures**, both fixed in ticket 02 and not yet heard then: a late `ask_body`
   result was denied ("I don't have that information"), and the fleet router spent its one round on
   `list_vehicles`.
3. **Run 3 (2026-10-28 11:24-11:31): the fixes held.** 6 of 8 async lookups answered from the
   `WHEN_IDLE` result in the model's own next turn; "any trouble codes on the Jeep" reached
   `get_code_history` and read P1282 and P0700 twice. Eight findings, F1-F8, built in ticket 07
   (`07-run3-follow-ups.md`). Result-to-first-audio after a `WHEN_IDLE` result: 0.8 s, 1.8 s, 1.9 s.

### Run 3, `python tools/voice_latency.py`

| tool | n | total p50 | total p95 | router POST p50 | router POST p95 | fan-out p50 | fan-out p95 | fallbacks |
|---|---|---|---|---|---|---|---|---|
| ask_fleet | 6 | 1,141 | 8,994 | 1,109 | 1,301 | 28 | 7,991 | 0 |
| ask_goals | 2 | 772 | 829 | 768 | 818 | 3 | 6 | 0 |
| ask_body | 1 | 511 | 511 | 506 | 506 | 4 | 4 | 0 |
| diagnose_codes (investigate) | 1 | 7,974 | 7,974 | - | - | - | - | 0 |

`reply_ms` p50 1,255, p95 2,059 (n=16). `eot_reply_ms` p50 1,978, p95 3,976, max 13,811 (n=21; the
max is F3). The p50/p95 report this ticket owes (question 5 and the budgets) still needs
`<orchestrator>`: the merged table for decisions.md and any budget change beside it.

