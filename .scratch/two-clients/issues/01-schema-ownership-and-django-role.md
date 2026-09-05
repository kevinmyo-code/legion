---
map: two-clients
ticket: "01"
title: "Schema ownership, and a role of Django's own"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Schema ownership, and a role of Django's own

Builds ADR 0042 rulings 2 and 3. Before any Django code runs against LEGION data, three things exist
in the database and one thing exists in the Django project.

## Rulings this builds (decided, not re-openable here)

1. `supabase/migrations/` is the only migration owner for LEGION tables. `manage.py migrate` never
   touches one.
2. Django's own tables live in a schema named `django`, created by Django, never referenced by the
   phone or by any LEGION migration.
3. Django connects directly with a dedicated Postgres role. Never the anon or authenticated key,
   never the `service_role` JWT.

## Build

### In `supabase/migrations/`, one file

- `create role legion_django login password ...` - the password is NOT in the migration. The
  migration creates the role with `nologin` or a placeholder and the household sets the password
  once from the dashboard SQL editor, the same way the household sets its own Gemini key. A committed
  password is a committed credential in a public repo.
- `grant usage on schema public, private to legion_django;` and `grant select, insert, update,
  delete on all tables in schema public to legion_django;` plus `alter default privileges` so
  future tables inherit. The `private` schema is intentionally reachable: Django is a database
  role, not an API consumer, and ADR 0042 says so.
- `grant execute on all functions in schema public to legion_django;` - the RPCs are the write path.
- `create schema if not exists django authorization legion_django;` so Django owns its own schema
  outright and can `migrate` inside it without touching `public`.

### RLS, and how the role sees rows at all

Every LEGION table has RLS applied through `private.apply_household_rls`, whose policy calls
`private.is_household_member()`, which reads `auth.uid()`. A direct-connect role has no JWT, so
`auth.uid()` is null and the role sees NOTHING through those policies. One of these, and which one is
a verification step against the live project, not a choice made here:

| Option | Cost |
|---|---|
| `alter role legion_django bypassrls` | Needs a role that may grant BYPASSRLS. Supabase's `postgres` role is not superuser; whether it can grant this is `reasoned`, unverified. Try it first. |
| A second policy per table: `using (current_user = 'legion_django')` | Verified to work in plain Postgres. Touches `apply_household_rls` once and re-applies to every table. |
| Grant `legion_django` membership in the table owner role | Owners bypass RLS unless `force row level security`. Fragile: a future `force` breaks it silently. Rejected. |

Whichever lands, write down which in this ticket's status-detail. ADR 0042 accepts a service-level
role because there is one household; the day there are two, this is the line that changes.

### Connection

- Supabase's direct connection is IPv6-only on the free tier. Use the Supavisor **session** pooler
  (port 5432) for Django's persistent connections; **transaction** mode (6543) breaks named prepared
  statements, which Django's psycopg3 backend uses unless `server_side_binding` is off. `reasoned`
  from Supabase's own docs, verify on first connect.
- `DATABASES['default']['OPTIONS']['options'] = '-c search_path=django,public'` so Django's own
  tables resolve first and `inspectdb` models can name `public` tables unqualified via `db_table`.

### In the Django project

- `python manage.py inspectdb --database default > legion/models.py`, then hand-correct: every model
  `managed = False`, every `db_table` schema-qualified where `inspectdb` dropped it, `bigint` ids
  kept as `BigAutoField`, `timestamptz` as `DateTimeField`. Commit the raw `inspectdb` output beside
  the corrected file so a regeneration diffs cleanly.
- A management command `regen_models` that re-runs `inspectdb` and fails CI if the committed raw
  output differs. This is `docs_check.py`'s posture pointed at the schema: drift is a hard failure.
- `MIGRATION_MODULES = {'legion': None}` so Django never generates a migration for the LEGION app.

## Verification

- [ ] `select count(*) from public.events` as `legion_django` returns the household's rows, not zero.
- [ ] `manage.py migrate` creates `django.auth_user` and friends and leaves `public` byte-identical
      (diff `pg_dump --schema-only --schema=public` before and after).
- [ ] `manage.py makemigrations` reports no changes for the LEGION app.
- [ ] The phone, signed in as before, is unaffected. It never learns the role exists.
- [ ] `supabase db reset` on a fresh project applies the migration cleanly: clone-and-run for the
      database half.

## Out of scope

Where Django runs ([[02-where-django-runs]]). This ticket produces a role and a project that connect
from anywhere, including a laptop.
