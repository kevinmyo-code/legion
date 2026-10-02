---
map: engine-mcp
ticket: "03"
title: "One source of truth for tool definitions"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# One source of truth for tool definitions

## Question

Where is a tool defined - name, description, input schema, read or write, and the code it runs - so
that the MCP surface, the phone's Gemini declarations and the user-facing voice guide cannot disagree?

## Why this is the decision everything hangs on

Today there are already two registries that drift: `LiveToolbox.kt` declares ~100 tools in Kotlin,
and `server/openapi.yaml` describes ~70 REST routes. ADR 0044 named the first one *"welded to
Kotlin"*. Adding MCP without settling this makes three. `tools/voice_guide.py` already shows the
repo's answer to drift for one registry: generate from it and fail the build on a gap.

## Options

| | Source | For | Against |
|---|---|---|---|
| A | **`openapi.yaml`**: one MCP tool per operation, generated | Already exists, already regenerated, already the contract `feat/openapi-clients` generates DTOs from | ~70 CRUD operations is a bad model surface: tool explosion, and descriptions written for a REST client, not a model. Live re-bills every declaration every turn |
| B | **A server-side Python registry of curated tools**, each calling the same service code as the REST views. It produces MCP `tools/list`, and can emit Gemini declarations for the phone | Descriptions written for a model, once. Few tools (`query_ledger`, `whats_due`...) rather than CRUD. A drift test can compare it with `LiveToolbox` | A new layer to keep; must not become a second place business rules live |
| C | **Kotlin stays authoritative** for the phone; MCP gets its own Python registry | No phone change | Two registries, the drift ADR 0044 named, by design |

## Recommendation

**B.** OpenAPI stays the REST contract for the limbs, untouched. The registry is the model-facing
contract: tools are thin, call existing service code, and hold no rule of their own (a rule found
only in a tool is a defect, same as a rule only in Kotlin). Its descriptions carry the honesty rule
06 settles. A test fails when a registry tool has no voice-guide copy, the way `voice_guide.py` does.

## Resolution

Kevin picks. If B, 06 decides the first tool list.
