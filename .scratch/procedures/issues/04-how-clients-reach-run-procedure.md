---
map: procedures
ticket: "04"
title: "How each client reaches run_procedure (phone, head unit, web, recruiter, laptop)"
type: grilling
status: resolved
status-detail: "resolved 2026-11-03, Kevin: approve all 9 as recommended"
blockers: ["01"]
blocked-by: ["[[01-procedure-format-and-storage]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# How each client reaches run_procedure (phone, head unit, web, recruiter, laptop)

## Question

Portability is MCP: one engine tool `run_procedure`. How does each client declare it, and does
it go through a sub-agent?

## What is true today ([../research/call-sequences-today.md](../research/call-sequences-today.md))

- Phone: engine reads go through `ask_engine` (`service/AskEngine.kt`): `tools/list`, then up to 4
  sub-agent model POSTs, BLOCKING, 40 s budget. ADR 0065's test keeps `ask_engine` OUT of
  `NON_BLOCKING_READ_TOOLS` (`LiveToolbox.kt:3101`).
- Head unit: one `ask_engine` only (`headunit/CLAUDE.md:185`), same shape, no NON_BLOCKING at all.
- Web and recruiter: the browser calls registry tools directly through `AssistantToolView`
  (`assistant/views.py:423`); no sub-agent.
- Laptop worker: not on dev (Claude session map parked).

## Recommendation

- **Phone and head unit declare `run_procedure` DIRECTLY** to Live, not behind `ask_engine`.
  Its `name` parameter is an enum built at session start from `tools/list` (the engine lists
  active procedures in the tool's schema). Behind `ask_engine` it would add the sub-agent hop the
  whole map exists to remove.
- **Read-only procedures run NON_BLOCKING with `WHEN_IDLE`** on the phone, using the computed
  `writes` flag (ticket "Procedure format"). This AMENDS ADR 0065's set from a fixed name list to
  "these four plus any procedure whose `writes` is false"; its disjointness test grows to cover
  it. Any procedure with a write step: BLOCKING, always.
- **Head unit stays all-BLOCKING** (no NON_BLOCKING path exists there); its gain is fewer hops.
- **Web**: `run_procedure` joins the registry, so `assistant/surface.py` `WEB_TOOL_NAMES` picks it
  up automatically.
- **Recruiter**: see ticket "Recruiter visits: run_procedure and the inspector".
- **Laptop worker**: calls `/mcp` with its device token like any MCP client, when that map
  unparks (ticket "Laptop worker").

## Latency budget (proposed, to be measured by the baseline ticket)

- Engine side of a read-only run with no `decide` step: **under 1.5 s p95** (it is ORM reads plus
  composition). With a `decide` step: under 4 s p95.
- Over budget: the run returns what finished, names the steps that did not run, never blocks the
  socket past the client's own tool timeout.

## Answer

**Accepted as recommended.** Kevin, 2026-11-03: *"approve all 9 as recommended"*.

- Phone and head unit declare `run_procedure` DIRECTLY to Live, not behind `ask_engine`; its `name` enum is built at session start from `tools/list`.
- On the phone a procedure whose computed `writes` is false runs NON_BLOCKING with `WHEN_IDLE`; any writing one is BLOCKING. **This amends ADR 0065's fixed set** (ADR 0070); the disjointness test grows to cover it.
- Head unit stays all-BLOCKING. Web picks `run_procedure` up from the registry. Laptop calls `/mcp` when its map unparks.
- Latency budget as proposed (engine side under 1.5 s p95 without `decide`, under 4 s with), to be confirmed or corrected by ticket 10's measurement.
- Build: tickets 12, 16, 17.
