---
map: household-finance
ticket: "09"
title: "Money alerts"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["07", "08"]
blocked-by: ["[[07-net-worth-and-runway]]", "[[08-budgets-and-pace]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Money alerts

## What
- Engine evaluator, run after each Plaid sync and daily at 07:00 household time, writes `MoneyAlert(kind, key, period, facts JSON, raised_at, spoken_at, silenced)`. Deduped by (kind, key, period), so each alert fires once per period.
- Kinds:
  - category pace
  - 80% and 100% of budget
  - US top-up (7 days ahead)
  - household floor (within 30 days)
  - unusual charge (more than 2x the merchant's usual) or new subscription
  - statement missing on the 5th
  - monthly review on the 1st
- Each alert carries its facts (figures, dates, the rate used). Wording comes from the facts only. It passes the §7 compulsion test (a declared anchor and a silence instruction; registry test).
- Phone:
  - Speaks at most ONE money alert per day, at a natural pause, never mid-conversation, never in Matrix.
  - Every alert also posts a notification and adds a morning-briefing line.
  - "Stop the X alerts" silences that kind.
- Web: an alerts list on the Money page.
## Verification
- pytest: dedupe, one-per-day spoken budget, silence.
- JVM tests: the phone's speak gate.
- Kevin's run.
