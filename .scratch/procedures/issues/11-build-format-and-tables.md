---
map: procedures
ticket: "11"
title: "Build: procedures app, definition schema, built-in loader"
type: build
status: open
status-detail: ""
blockers: ["01", "02", "03", "06"]
blocked-by: ["[[01-procedure-format-and-storage]]", "[[02-decision-step-small-model]]", "[[03-ask-step-by-voice]]", "[[06-trace-content-and-retention]]"]
open-blockers: 0
ready: true
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
