---
map: web-revamp
ticket: 18
title: Phone follow-ups for the Android agent
type: task
status: open
status-detail: ""
blockers: ["06", "11", "14"]
blocked-by: ["[[06-private-rows-on-the-engine]]", "[[11-spend-on-the-engine]]", "[[14-reminder-lead-time-on-events]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Phone follow-ups for the Android agent

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

Not built in this map. A handoff list for whoever owns `app/`.

1. **Privacy toggle** on phone event and list editors, writing `visibility` (ADR 0052). The phone is
   already correct without it: unknown keys ignored, redacted tombstones delete locally. Verify the
   tombstone path once on the A25 after ticket "Private rows on the engine" is live.
2. **`remind_minutes_before`** read and written by the phone, so a lead time Mia sets on the web
   shows on Kevin's phone.
3. **Switch the phone's spend figures to `GET /api/ledger/spend`**, retiring the local
   `LedgerBudget.operatingExpenses` path once the parity test has held for a month.
4. **Skips over the wire:** confirm the phone sends and receives `event_skips` through the new routes
   rather than keeping them local.
