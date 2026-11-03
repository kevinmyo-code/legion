---
map: procedures
ticket: "11"
title: "Build: procedures app, definition schema, built-in loader"
type: build
status: resolved
status-detail: "built 2026-11-03 on feat/procedures-foundation, suite green; owes deploy (Kevin)"
blockers: ["01", "02", "03", "06"]
blocked-by: ["[[01-procedure-format-and-storage]]", "[[02-decision-step-small-model]]", "[[03-ask-step-by-voice]]", "[[06-trace-content-and-retention]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: procedures app, definition schema, built-in loader

## What

- `server/procedures/` app: models from ticket "Procedure format", migration in `public` with the
  same `search_path` swap as `engine_mcp/migrations/0001_initial.py`.
- JSON Schema for a definition; loader that reads `builtin/*.yaml` on migrate and writes a new
  version only when the file changed (hash).
- Load-time checks: closed step kinds, DAG (no cycles), every `tool` step names a registry tool,
  computed `writes`, write-without-ask rejected, decide-in-a-writing-procedure rejected.
- Tables added to `household.tenancy.TENANT_TABLES`.

## Verify

- `tests/test_tenancy.py` and the leak test cover the new tables.
- Loader idempotent: second migrate writes nothing.
- A definition violating each load-time check is refused with a sentence naming it.

## Built (2026-11-03, branch `feat/procedures-foundation`)

- `server/procedures/`: `public.procedures` (household NULL for a built-in, unique name per
  household with NULLS NOT DISTINCT (Postgres 15+), tool-name shape) and `public.procedure_versions`
  (definition JSON, source `builtin` / `distilled` / `hand`, `content_sha256`, computed `writes`,
  `is_active`, `approved_by` / `approved_at`). Migration `0001` in `public` with the
  `engine_mcp` search_path swap, plus a trigger: a version's content is immutable; only
  activation and approval move. At most one active version, and only an approved one.
- `procedures/definition.py` `validate`: JSON Schema per kind, then the rulings: six kinds, DAG
  (no cycle, no unreachable step, every path ends at `say`), registry tools only, no read-through
  tool (mirror of `EPISODIC_EXCLUDED_TOOLS`, test-pinned to the Kotlin set), no `run_procedure`
  step, `compute` / `check` functions from `procedures/steps.py`, `$steps.x` only from a step that
  runs before, `$inputs.x` only if declared, `decide` falls back to its own branch and never in a
  writing procedure, every write behind an `ask` unless `named_by_request`; `writes` computed.
- `procedures/loader.py` `load_builtins`, run on `post_migrate`: a new version only on a hash
  change, nothing written if any file is broken, a removed file deactivated. `builtin/` ships
  empty (ticket 15 fills it).
- Tenancy: both tables in `TENANT_TABLES` and `DJANGO_MANAGED_TENANT_TABLES`; leak test
  `test_procedures_are_scoped_by_household` at `procedures.models.visible_to`.

## Verify, accounted for (CLAUDE.md L11)

- Tenancy tests and the leak test cover the new tables: **done**.
- Loader idempotent: **done** (`test_a_second_load_writes_nothing`; the `post_migrate` wiring has
  its own tests).
- Each load-time check refused with a sentence naming it: **done**
  (`tests/test_procedures_definition.py`).
- On the deployed engine: **owed**, the migration runs on Kevin's next deploy.
