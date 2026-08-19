---
status: accepted
decided: 2026-08-18
decided-by: Kevin
source: "decisions.md 2026-08-18"
tags: [adr]
---

# 19. Write tools are visible; only reads hide behind dispatchers

## Standing

ACCEPTED. Measured, not reasoned.

## Context

Two mis-routes in two days, both writes: spoken workout sets reached `ask_goals`, groceries reached `manage_item`. Sharpening the tool descriptions did not fix it.

## Decision

Every dispatcher became read-only and the write tools came out from behind them. Reads stay hidden, because reads were implicated in neither mis-route. A model cannot route to a tool it cannot see.

## Consequences

- The live tool block grew from 46 to 54 tools, roughly 8,440 to 9,990 tokens per turn. That cost was accepted for correctness on writes.
- `dispatchBoundaryClause` now tells every sub-agent explicitly that it can record nothing.
- A write that is mis-routed is now refused in words rather than answered around.
