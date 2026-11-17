---
map: engine-brain
ticket: "02"
title: "Runtime and evals, dark: the agent package, graph, guards, traces, eval runner, day read twins"
type: build
status: open
size: L
blockers: ["00"]
blocked-by: ["[[00-rulings]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Runtime and evals, dark: the agent package, graph, guards, traces, eval runner, day read twins

Stage 1. Engine only, dark behind a flag.

## Build
- `server/agents/runtime/` and `server/agents/graph/`:
  - the loop (max rounds, per-call and per-turn timeouts, streaming, cancel, token and cost budget);
  - the typed `TurnState`;
  - nodes, conditional edges, and a checkpoint per node;
  - the `ModelClient` protocol with a Gemini client and a fake.
  - **No LEGION imports.** An import-boundary test fails on any import of `household`, `engine_mcp`, `assistant` or another LEGION app (ADR 0100).
- `server/agents/legion/`:
  - a tool gateway over `run_engine_tool`;
  - guards: allowlist, taint write-guard, confirm by args hash, cost cap, honesty flag;
  - `specs/generalist.yaml` carrying the locked safety text from code.
- Tables `agents_conversation`, `agents_turn`, `agents_checkpoint`, `agents_model_call`, `agents_tool_call`:
  - tenant tables, member-private, in the leak and visibility tests;
  - retention 30 days for turns and traces, 7 for checkpoints.
- Engine twins of the phone's `day read_calendar` (the `DueList.says` wording) and `manage_item read_list`.
- The eval runner (`manage.py agent_eval`):
  - case format, deterministic graders, several runs per case, pass-rate bars;
  - the `voice21` suite on an invented household fixture ported from the phone's eval fixture;
  - cassettes recorded from the live run.
- The ship script runs the live suites and refuses on a bar miss (ADR 0100).

## Verification (gates)
- [ ] `voice21` at its bar on the engine (3 runs per case).
- [ ] CI (no key): import-boundary, router, spec, guard and cassette-replay tests green.
- [ ] Tenancy leak test and `test_visibility.py` cover the new tables.
- [ ] Server suite green apart from the known baseline failures.
