---
map: django-engine
title: "Execution plan: the port, vertical slice first"
charted: 2026-09-05
tags: [plan]
---

# The port: Supabase Postgres, Django, the Android app. Vertical slice first.

**Kevin, 2026-09-05:** *"lets do the port. make the implementation plan. supabase holds the pgsql db,
django, then our android app here. then run it end to end."*

This is the order the map's eleven tickets get built in, with the one rule that governs it: **nothing
on the phone is removed until its Django replacement is verified on the A25.** The phone keeps talking
to Supabase directly for every aspect that has not crossed yet. Two transports coexist during the
port, per aspect, never per table.

## Phase 0 - Kevin, once (tonight)

| Item | Why | Done? |
|---|---|---|
| Create `legion_engine` role: full DML on `public`, DDL for Django's own schema, `bypassrls`, `createdb` (pytest creates a test database) | Django is the only writer; it needs a role that can write. Credential creation is blocked for agents | |
| `LEGION_PG_ADMIN_URL=` for that role in `.claude/mcp.env` | Server tests and `inspectdb` run against the real schema | |
| `! gh auth login` | PRs and issues from agents | |
| `gcloud` install (admin winget or the zip) | Phase 4 | not needed before Phase 4 |

SQL for the role (Supabase SQL editor; pick the password):

```sql
create role legion_engine login password 'PICK-A-LONG-PASSWORD' createdb bypassrls;
grant usage, create on schema public to legion_engine;
grant all on all tables in schema public to legion_engine;
grant all on all sequences in schema public to legion_engine;
alter default privileges in schema public grant all on tables to legion_engine;
alter default privileges in schema public grant all on sequences to legion_engine;
```

Django's own tables (`auth_*`, `django_*`, `household_*`) go in a schema named `django` that this
role creates, so Django's migrations never touch `public`. `public` stays owned by
`supabase/migrations/` until ticket 02 hands ownership over table by table.

## Phase 1 - Django reads and writes the real database (tickets 01, 02, 04)

**01, finish verification.** `pytest` against the Supabase Postgres via `LEGION_PG_ADMIN_URL`
(`conftest.py` already refuses anything but Postgres). The four owed checks close. No Docker.

**02, the 41 tables as models.** `manage.py inspectdb --database=... > household/legacy_models.py`
with `managed = False`, then hand-corrected: UUID pks, `provenance` enum as a `TextChoices`,
`structured_meta` as `JSONField`, money as `BigIntegerField` (cents, never Decimal - CLAUDE.md §4
rule 3). One test per table asserting the model round-trips a real row read through `legion_reader`.
The CHECK constraints stay in SQL; Django gets `clean()` mirrors only where the API needs to explain a
refusal in words (§7).

**04, the domain API and the changes feed.** DRF viewsets per aspect, and ONE endpoint the phone's
cache lives on: `GET /api/changes?since=<updated_at>&aspect=events` returning rows changed since the
watermark, tombstones included. This replaces the Realtime socket and every per-table pull with one
shape. Writes are `POST`/`PATCH` per resource; the response is the row as stored, so the phone never
guesses what landed.

**Slice for Phase 2: `events` (coursework, reminders) and `checklists`.** They are the aspects Kevin
touches hourly, the checklists have no server tables yet (so their Django models are the FIRST tables
Django owns end to end, no legacy to honour), and events already have a well-tested merge on the phone
to compare against.

## Phase 2 - the phone crosses one aspect at a time (ticket 09)

**Not Hilt yet.** Architecture map ticket 02 (Hilt plus the shim) lands in parallel and the HTTP
backends are written as injectable classes from day one, but the port does not wait for it.

For the slice aspect:

1. `backend/DjangoEventsBackend.kt` implementing the existing `EventsBackend` interface over HTTP
   (Ktor or OkHttp, whichever `supabase-kt` already pulls in - no new HTTP stack). Device token from
   `EncryptedSharedPreferences`, obtained by `POST /api/auth/login` from a new Setup row.
2. `EventsSync.pull` reads `/api/changes` instead of PostgREST; the merge rules are untouched (they
   were the hard part and they are tested). Realtime trigger is replaced by a 60 s poll while the app
   is foreground, `WorkManager`-free as the codebase already is.
3. Write-through and the outbox post to Django. **Same controller, same UI, same voice tools.** Only
   the backend object changes.
4. Checklists get `ChecklistsSync` for the first time, against Django tables Django owns.
5. A per-aspect switch in `SupabaseConfig`: `transport = supabase | django`. Default `supabase`. The
   slice aspects flip to `django` when Phase 3 passes.

## Phase 3 - end to end on the A25, before any cloud

Django runs on the laptop: `uv run manage.py runserver 0.0.0.0:8000`, phone on the same Wi-Fi,
`BASE_URL = http://<laptop-ip>:8000` in the debug Setup screen. No Docker, no Cloud Run, no tunnel.

| Check | Pass means |
|---|---|
| Sign in from the phone | a token row appears in `household_devicetoken`, hashed |
| Tick a Canvas task on the phone | Django wrote `done = true` in `public.events`; the pg MCP reads it back; the PWA at `/` shows it |
| Tick from the PWA on the laptop | the phone shows it after the next poll |
| Add "milk" by voice | `manage_checklist` -> `POST /api/checklists/...` -> row in Django's `checklist_items` |
| Kill the laptop server | the phone still renders from Room and says the server is unreachable in words; a queued write lands when it returns |
| Refuse a bad write | a measured tick with no number returns 400 with the controller's sentence, nothing stored |

Only when all six pass does the slice's transport flip to `django` in the committed default.

## Phase 4 - Cloud Run (tickets 07, 06)

`deploy/cloudrun/deploy.py` (dry-run proven tonight) with `gcloud` installed. Secrets in Secret
Manager. `BASE_URL` on the phone becomes the Cloud Run URL. Cloud Run Job + Scheduler run the
nightly `pg_dump` to R2 first (ticket 06), then the Canvas poll (ticket 03's rules, verbatim).
The PWA gets its manifest and service worker (ticket 08). Media to R2 (ticket 05).

## Phase 5 - widen, then cut over (tickets 09 again, 10)

One aspect per pass, same six checks each: ledger, pantry (the §4 gate moves into Django here,
ticket 03 of the map), body, memory, fleet, voice notes, places. Each pass deletes that aspect's
Supabase backend, its Realtime subscription and its PostgREST pull.

Cutover (ticket 10), after the last aspect: revoke the `anon` key, disable Realtime and Auth on the
project, remove `supabase-kt`, set the two users' Django ids to their former `auth.uid`. **No rows
move.** `pg_dump` before, as the last safety.

## What "end to end" means tonight

Phase 0 by Kevin, Phase 1 and the slice half of Phase 2 by agents, Phase 3 on the A25 with the
laptop as the server. Cloud Run follows when `gcloud` exists. That is a real end-to-end run of the
decided architecture with one aspect, not a demo.
