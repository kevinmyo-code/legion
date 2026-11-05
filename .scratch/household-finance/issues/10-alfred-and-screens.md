---
map: household-finance
ticket: "10"
title: "Alfred and the Money screen"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["07", "08"]
blocked-by: ["[[07-net-worth-and-runway]]", "[[08-budgets-and-pace]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Alfred and the Money screen

## What
- Phone and web Money screen:
  - net worth by layer (USD, with an SGD toggle)
  - this month's cash flow
  - budgets with pace
  - runway and floor date
  - transfer-needed card
  - accounts with as_of and estimate tags
  - missing statements
- Voice: Alfred answers "what's our net worth", "are we on budget", "do I need to move money from Singapore" from the engine tools. Same controller as the screen (ADR 0035).
- Monthly review page on the 1st (`deliver: phone`).
## Verification
- JVM and frontend tests.
- The voice guide script.
- Kevin's run.
