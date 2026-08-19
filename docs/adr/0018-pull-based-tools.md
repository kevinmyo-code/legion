---
status: locked
decided: 2026-07-30
decided-by: Kevin
source: "CLAUDE.md §7"
tags: [adr]
---

# 18. New domains get tools, not pre-injected context

## Standing

LOCKED.

## Context

Every domain wants to put its state in the system prompt. That is the cheapest thing to write and the most expensive thing to run: it is paid on every single turn whether or not anyone asks.

## Decision

New domains default to tools and sub-agents that the model pulls from, not context blocks pushed into every prompt.

## Consequences

- `ai/AriaBrain.kt` is a context supplier called once per socket, not a per-turn injector. See [[c3-voice-loop]].
- Sub-agent tool calls re-enter the same `service/LiveToolbox.kt` dispatcher, so a tool written once is reachable from both paths.
- The exception is the small always-on context that makes the first turn coherent. Everything else is pulled.
