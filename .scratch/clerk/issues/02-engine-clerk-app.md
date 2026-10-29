---
map: clerk
ticket: "02"
title: "Build: engine clerk app (tasks, workers, files, status)"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: engine `clerk` app

Server only (`server/`). Branch `feat/clerk-engine` off `dev`. Implements the API table in the map
exactly; the phone (ticket 04) and the worker (ticket 03) are built against that table in parallel,
so a field name change here is a change to the map first.

## Files

- New Django app `server/clerk/`: `models.py` (`ClerkTask`, `ClerkTaskFile`, `ClerkWorker`),
  `serializers.py`, `views.py`, `urls.py`, `migrations/0001_initial.py`, `admin.py`.
- `server/legion/urls.py`: `path("api/clerk/", include("clerk.urls"))`.
- `server/legion/settings.py`: `INSTALLED_APPS`.
- `server/household/tenancy.py`: `TENANT_TABLES` gets `clerk_tasks`, `clerk_task_files`,
  `clerk_workers`. Read the module docstring: the tenancy leak test reads this tuple.
- Pattern to copy: `server/devicelogs/` (multipart upload, `household_of`, `scoped`, no body
  logging) and whichever app has a list endpoint with `limit`.
- Tests: `server/tests/test_clerk.py` covering the status machine, claim ordering (oldest first,
  followup waits for parent), the 90 s offline rule, the 10 min worker-lost rule, cancel in both
  states, tenancy (a second household sees nothing), file download scoped.
- Docs: `docs/adr/0066-the-clerk-runs-headless-claude-code-on-the-laptop.md` (format
  `.claude/skills/domain-modeling/ADR-FORMAT.md`, references ADR 0044, ADR 0035, ADR 0031, ticket
  01), `memory/library/decisions.md` entry dated 2026-10-29, `docs/architecture/` container doc
  gets the clerk as a client of the engine if that doc lists clients. `python tools/docs_check.py`.

## Rules

- `summary` and `question` are text up to 16 KB; `request` up to 4 KB. Files up to 25 MB each,
  stored under `MEDIA_ROOT/clerk/<household>/<task>/`, served only through the scoped endpoint.
- Nothing in this app logs a request body or a summary (the devicelogs posture).
- `status/` is cheap: one query per table.
- Household-scoped at `household_of`, every queryset through `scoped`.
- The worker-lost rule (10 min silent while `running`) and the 90 s offline rule are computed at
  read time from `last_seen_at`, no scheduler.

## Verification

- Server tests: the suite needs Postgres. The only `DATABASE_URL` on Kevin's laptop is production
  and must never be used. Run against a throwaway local Postgres (docker `postgres:16` on a free
  port, or `uv run --no-sync pytest` with `DATABASE_URL` pointing at it). If no local Postgres can
  be started, say so and leave the tests written and unrun; Kevin runs them.
- `python tools/docs_check.py`, `python tools/obsidian_sync.py`, `python tools/pending_wiki.py`.
- Deploy is Kevin's call ("deploy OK"); the report names the migration.
