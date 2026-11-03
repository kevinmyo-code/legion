---
map: procedures
ticket: "12"
title: "Build: engine runner and the run_procedure MCP tool"
type: build
status: open
status-detail: ""
blockers: ["04", "07", "11"]
blocked-by: ["[[04-how-clients-reach-run-procedure]]", "[[07-recruiter-visits]]", "[[11-build-format-and-tables]]"]
open-blockers: 0
ready: true
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
  caller. **`writes=False` on the EngineTool.** `run_engine_tool` checks the write fence on the
  tool's STATIC flag before the handler, so `writes=True` would refuse every procedure to a
  read-scoped token, read-only ones included, and a recruiter visitor's token is read-scoped.
  Instead the runner calls `household.permissions.write_refusal(request)` itself before any step
  whose tool `writes`, refuses that step in words, and the result names it.
- The per-caller `name` enum means a per-request schema; `EngineTool` is frozen and validated at
  import. Use the pattern `assistant/surface.py` `_recruiter_tool` already uses
  (`dataclasses.replace(tool, input_schema=...)`).
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
