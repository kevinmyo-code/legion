---
map: procedures
ticket: "12"
title: "Build: engine runner and the run_procedure MCP tool"
type: build
status: open
status-detail: ""
blockers: ["04", "07", "11"]
blocked-by: ["[[04-how-clients-reach-run-procedure]]", "[[07-recruiter-visits]]", "[[11-build-format-and-tables]]"]
open-blockers: 3
ready: false
tags: [ticket]
---
# Build: engine runner and the run_procedure MCP tool

## What

- `procedures/runner.py`: walks the DAG; each `tool` step goes through
  `engine_mcp.tools.run_engine_tool` (write fence, table fence, schema) AND
  `assistant.surface.refusal_for(name, user, args)` (recruiter fence). Parallel siblings allowed
  for read steps only.
- `run_procedure` in `engine_mcp/tools.py` `TOOLS`: args `name`, optional `inputs`, `resume`,
  `answer`, `conversation_id`. Its schema's `name` enum lists the active procedures visible to the
  caller. `writes` on the EngineTool is true (some procedures write), so a read-scoped token is
  refused a writing procedure in words by `run_engine_tool`; a read-only one runs.
- Result honesty (CLAUDE.md §7): the result text lists steps that ran, steps that failed or were
  skipped, and says plainly what did NOT happen. Outcome verbs only for write steps whose tool
  returned ok. Estimates labelled as the tools label them.
- Classified in `assistant/surface.py` (`RECRUITER_TOOLS` or `RECRUITER_EXCLUDED`) per ticket
  "Recruiter visits".
- Timeout per run from the latency budget; over budget returns partial with the unrun steps named.

## Verify

- Unit: every step kind, a failed tool mid-run, a refused step for a recruiter, resume/cancel of
  an ask step, partial on timeout.
- `tests/test_recruiter.py` classification passes; isolation test for a recruiter naming a
  member-only procedure.
- `/mcp` `tools/list` shows it; a real MCP client (Claude Code) runs `car_check` end to end.
