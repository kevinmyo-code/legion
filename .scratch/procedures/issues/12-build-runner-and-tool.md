---
map: procedures
ticket: "12"
title: "Build: engine runner and the run_procedure MCP tool"
type: build
status: built
status-detail: "built 2026-11-03 on feat/procedures-runner, suite green bar the known dev failures; owes deploy and a real MCP client running car_check (ticket 15 ships it)"
blockers: ["04", "07", "11"]
blocked-by: ["[[04-how-clients-reach-run-procedure]]", "[[07-recruiter-visits]]", "[[11-build-format-and-tables]]"]
open-blockers: 0
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

## Built (2026-11-03, branch `feat/procedures-runner`)

- `procedures/runner.py`: walks a validated active version from its first step, strictly in
  sequence (the format has one `next` per step, and the ORM stays on the request's thread, so
  no parallel siblings in v1). Kinds: `tool`, `compute`, `check` (false ends with its
  `fail_sentence`, an answer, run `ok`), `decide`, `ask`, `say`.
- **The fence on every `tool` step** (`_tool_step`): `assistant.surface.refusal_for(name,
  request.user, args)` with the filled-in args, then `write_refusal(request)` when the tool
  writes, then `run_engine_tool`. A recruiter is also refused, before any step, a procedure
  using any tool outside `RECRUITER_TOOLS` (`procedures/listing.recruiter_refusal`).
- `run_procedure {name, inputs?, resume_token?, answer?, conversation_id?}` in
  `engine_mcp.tools.TOOLS`, `writes=False`. Its `name` enum is per caller
  (`procedures/listing.tool_for`, `dataclasses.replace`), on `/mcp` `tools/list`
  (`engine_mcp/server.py`) and in `assistant.surface.tools_for`; no empty enum when nothing is
  runnable. Not hinted read-only on `/mcp`.
- `ask` (ruling 03): `needs_answer`, the question, the answers and a single-use resume token
  (only its SHA-256 stored), 2 minutes; a wrong answer, a late one, or any other
  `run_procedure` call by the same user cancels, said in words.
- `decide` (ruling 02): `procedures/decide.py`, the household key and `GEMINI_MODEL`, JSON
  constrained to the branch names; anything else takes `on_fail`, chosen by code. Labelled in
  the result and on the trace; tokens and the labelled cost estimate (`brief.news.usage_of`). A
  recruiter's call is charged to the demo budget first. Tests mock the HTTP.
- Time (ruling 04): stops between steps at twice the budget (3 s, 8 s with a `decide`), partial,
  unrun steps named.
- Result honesty: steps that ran, the stopping step and why, steps not run, "Written (the tool
  said committed)" only for ok write steps, else "Nothing was written". An unexpected engine
  error mid-run still reports the writes already observed.
- Classified in `RECRUITER_TOOLS`. `X-Legion-Client: phone|headunit` labels a device token's
  run (a label, never a privilege).

## Verify, accounted for (CLAUDE.md L11)

- Unit: every step kind, a failed tool mid-run, a refused step for a recruiter, resume/cancel of
  an ask, partial on timeout: **done** (`tests/test_procedures_runner.py`,
  `tests/test_recruiter_isolation.py`).
- `tests/test_recruiter.py` classification passes; isolation test for a recruiter naming a
  member-only procedure: **done** (plus the injected-input case and the declaration).
- `/mcp` `tools/list` shows it: **done** (`test_tools_list_names_the_callers_procedures_only`).
- A real MCP client (Claude Code) runs `car_check` end to end: **deferred to ticket 15**; no
  built-in exists yet. Stand-in: fixture procedures over `/mcp` with read and write tokens.
- On the deployed engine: **owed** (Kevin's next deploy).
