---
map: procedures
ticket: "01"
title: "Procedure format, step kinds and where a definition lives"
type: grilling
status: resolved
status-detail: "resolved 2026-11-03, Kevin: approve all 9 as recommended"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Procedure format, step kinds and where a definition lives

## Question

What is a procedure, on disk and in Postgres, and which step kinds may it contain?

## Recommendation (for Kevin to accept or change)

- **A new Django app `server/procedures/`.** Postgres only: no graph DB, no new service
  (Kevin's earlier ruling; clone-and-run, CLAUDE.md §2).
- **Built-ins are YAML in the repo** (`server/procedures/builtin/<name>.yaml`), loaded on
  `migrate` / `compose up` by a data step, so a stranger's clone has them with no manual step.
- **Tables** (all in `public`, all carry `household_id`, all listed in
  `household.tenancy.TENANT_TABLES`, all covered by `tests/test_tenancy.py`, same posture as
  `engine_mcp.models.McpCall`):
  - `procedures` (name, household NULL for a built-in, active version).
  - `procedure_versions` (procedure, version int, definition JSON, source `builtin` / `distilled` /
    `hand`, approved_by, approved_at). Immutable once written; a change is a new version.
  - Runs and steps: ticket "Run traces".
- **Step kinds** (closed set; a new kind is a ruling, not a refactor):
  | Kind | Runs | May write? |
  |---|---|---|
  | `tool` | one registry tool via `engine_mcp.tools.run_engine_tool` | only if the tool `writes` |
  | `compute` | a named Python function in `server/procedures/steps.py` (pure, deterministic: sums, date maths, sentence composition like `brief/compose.py`) | no |
  | `decide` | small model picks ONE of the branches the definition names (ticket "The decision step") | no |
  | `ask` | stops the run and asks the user one question (ticket "The ask step by voice") | no |
  | `check` | a validation predicate over earlier step results; failing ends the run with its sentence | no |
  | `say` | composes the final spoken/written result from step outputs, by template | no |
- **A definition's `writes` flag is COMPUTED**, never declared: true when any reachable `tool`
  step's tool `writes`. Clients stamp BLOCKING from it (ticket "How each client reaches
  run_procedure").
- **No loops, no recursion, no procedure calling a procedure** in v1. A DAG with named branches
  only, so "which branches never ran" (branch report) is a finite list.
- Definition schema validated with `jsonschema` (already a dependency: `engine_mcp/tools.py`
  imports `Draft202012Validator`).

## Why

Prior art on dev: `brief/compose.py` already does this by hand ("Deterministic sections are
composed by code. Only News reaches an LLM"), and the phone's `get_sitrep` is "ONE tool, never
six" (`LiveToolbox.kt:1850`). Traced sequences: [../research/call-sequences-today.md](../research/call-sequences-today.md).

## Answer

**Accepted as recommended.** Kevin, 2026-11-03: *"approve all 9 as recommended"*.

- New Django app `server/procedures/`, Postgres only, no graph DB, no new service.
- Built-ins are YAML in `server/procedures/builtin/`, loaded on `migrate`, a new version only when the file changed.
- Tables `procedures` and `procedure_versions` in `public`, carrying `household_id` (NULL for a built-in), in `TENANT_TABLES`, covered by the tenancy tests. Versions are immutable.
- Six step kinds, a closed set: `tool`, `compute`, `decide`, `ask`, `check`, `say`. A new kind is a ruling.
- `writes` is computed from the reachable `tool` steps, never declared.
- DAG only: no loops, no recursion, no procedure calling a procedure (v1). Schema validated with `jsonschema`.
- ADR 0070. Build: ticket 11.
