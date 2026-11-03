---
map: procedures
ticket: "07"
title: "Recruiter visits: run_procedure and the run inspector"
type: grilling
status: open
status-detail: ""
blockers: ["06"]
blocked-by: ["[[06-trace-content-and-retention]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Recruiter visits: run_procedure and the run inspector

## Question

Does a recruiter's assistant get `run_procedure`, and may a recruiter see the inspector for their
own visit's runs?

## Facts

- `assistant/surface.py` `RECRUITER_TOOLS` is default deny; `tests/test_recruiter.py` fails
  until every registry tool sits in `RECRUITER_TOOLS` or `RECRUITER_EXCLUDED`.
- **The recruiter fence lives in `AssistantToolView` (`refusal_for`, `RECRUITER_TABLES`
  narrowing), not in `run_tool`.** A runner step that called `run_tool` directly would bypass it.
  So the runner must call `surface.refusal_for(step.tool, user, step.args)` per step, whoever the
  caller is. Not optional.
- Visit households are deleted whole (ADR 0069).

## Recommendation

- **Yes to `run_procedure`** for recruiters, limited to procedures whose every step tool is in
  `RECRUITER_TOOLS` (computed at list time, enforced per step at run time).
- **Yes to the inspector on their own visit's runs** (read-only, a "How LEGION answered that"
  panel beside the chat). It shows the engineering (steps, branch, timings, what did not run)
  on fake data, which is the showcase. Never any other household's runs, never the branch report
  across households, never distillation proposals.
- Isolation tests added to `tests/test_recruiter_isolation.py`, prompt-injection case included
  ("run Kevin's money check").
