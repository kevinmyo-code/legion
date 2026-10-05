---
map: purchase-log
ticket: "02"
title: "Matching: when is shampoo the same thing as Head & Shoulders?"
type: decision
status: resolved
status-detail: "Kevin: find close matches, say what matched"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer (2026-10-04)

**Kevin: search loosely, answer with the exact entry.**

- Deterministic floor: `TickMatch`'s normalisation (case, trim, collapsed whitespace), then a loose
  layer: the query's words all appear in the entry (so "shampoo" finds "Head & Shoulders shampoo"),
  simple plural folding ("shampoos").
- **Every answer names the entry it matched and its date**: "You logged Head & Shoulders shampoo on
  Sep 20 (Mia)." Never "you bought shampoo on Sep 20" when the entry said something else.
- Several different matches: list them (newest first), never pick one silently.
- No match: "I have no record of buying shampoo." Never "you have never bought it" (ADR 0049's
  absent-record rule carries over).
- The hands path (the log screen's search) shows the same matches.
