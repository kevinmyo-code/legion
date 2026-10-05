---
map: purchase-log
ticket: "02"
title: "Matching: when is shampoo the same thing as Head & Shoulders?"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Matching: when is shampoo the same thing as Head & Shoulders?

## Question

"When did we last buy shampoo?" has to find entries logged as "shampoo", "Shampoo", maybe "head &
shoulders shampoo", but never assert a date for the wrong thing (ADR 0049's near-miss rule: a wrong
match asserts a purchase that never happened).

- Reuse `TickMatch`'s narrow rule (case, trim, whitespace) as the deterministic floor?
- Let the assistant widen the search (contains, plural) and **read back what it matched**
  ("You logged 'Head & Shoulders shampoo' on Sep 20") rather than answer as if exact?
- Hands path: the log screen's search shows matches, not a single date.
