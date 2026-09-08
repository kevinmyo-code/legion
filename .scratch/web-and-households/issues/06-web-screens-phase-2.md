---
map: web-and-households
ticket: "06"
title: "Web screens, phase 2: Ledger, Pantry, Body, Fleet, Places, Voice notes, and a glanceable home"
type: build
status: open
blockers: ["05", "11"]
blocked-by: ["[[05-web-screens-phase-1]]", "[[11-report-endpoints]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Web screens, phase 2

| Route | Reads | Writes | The trust rule on this screen |
|---|---|---|---|
| `/ledger` | transactions, categories, budget targets, `reports/ledger/monthly` | category and rule edits, budget targets | Every figure containing an `UNRECONCILED` row prints `unverified` beside it in the same font. Provisional rows carry the word on the row |
| `/pantry` | receipts, line items, staples, `reports/pantry/spend` | receipt photo upload to the gate (needs django-engine 05, R2); staples | Macros are labelled `estimate`; `unaccounted_cents` shown as "unaccounted", never folded into tax |
| `/body` | weight, sleep, meals, workouts, `reports/body/*` | log entries | Targets vs actual as meters, numbers beside |
| `/fleet` | vehicles, service history, maintenance due, drives | service entry, schedule edit | Vehicle edits are a known no-op on Django until the `origin_guid` decision lands (MEMORY); the screen says "not saved to the engine" rather than pretending |
| `/places` | places | add, rename, delete | - |
| `/notes` | voice notes (transcript, summary) | delete | Audio is not on the wire (django-engine 04); say so |
| `/` gains a home strip | the phase-1 Today plus one glanceable tile per aspect from ticket 11 | - | quant-viz's locked calls carry over: sparklines, meters, bars, small multiples; no donuts, no pies |

## Verification

- [ ] Each screen against the live engine with Kevin's household; screenshots in the report, kept.
- [ ] One `UNRECONCILED` ledger row: the word is visible, not a colour, on the row and on any total
      that includes it.
- [ ] A parent's household with no data: every screen renders its empty state in words, no chart
      with an empty axis.
