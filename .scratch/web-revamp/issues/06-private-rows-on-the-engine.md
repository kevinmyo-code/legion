---
map: web-revamp
ticket: 06
title: Private rows on the engine
type: build
status: built
status-detail: "Private rows enforced at visible(); 375 related server tests green. Skip routes and their privacy land with ticket 08. make_private on live owed (Kevin)."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Private rows on the engine

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D3. Writes **ADR 0052** "A row may be private to one member" (amends ADR 0045) in the same commit.

## Build (server)

- Migrations: `owner_user_id` on `events`, `checklists`; `created_by_id` on `events`, `checklists`,
  `checklist_items`; `source_credentials.user_id` if absent (backfilled to the household owner).
  Composite FK so an owner is always a member of the row's household.
- `household/tenancy.py`: `visible(model, request)` plus the parent-joined variants for items,
  ticks and skips. Switch `api/events.py`, `checklists/views.py`, `api/changes.py`,
  `engine_mcp/tools.py` to it.
- Wire `visibility` on events and checklists; the who-may-change rule and its 403 sentence; redacted
  tombstones for rows private to someone else.
- Member removal tombstones their private rows.
- `upsert_canvas_task(p_household, p_task, p_read_at, p_owner_user)` and `ingest/canvas.py` passing it.
- `manage.py make_private` with `--origin-prefix` / `--structured-meta-key` / `--dry-run`.
- Regenerate `openapi.yaml`; `npm run gen:api` in the frontend.

## Verification

- [x] `tests/test_visibility.py`: two members of one household, every route and MCP tool for events,
      checklists, items, ticks, skips: list hides, detail/PATCH/DELETE 404, changes feed carries only
      the redacted tombstone, POST defaults shared, who-may-change 403, removal tombstones.
- [x] No `owner_user_id` / `created_by_id` in any response or OpenAPI component.
- [x] `test_tenancy.py` and `test_engine_mcp.py` still green.
- [x] Canvas poll test: new rows private to the credential's user.
- [x] `make_private --dry-run` on a fixture prints the count; a second real run changes 0.
- [ ] Owed on live (Kevin): run `make_private` for `canvas:` and for `course`.
