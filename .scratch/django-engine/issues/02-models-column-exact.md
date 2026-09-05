---
map: django-engine
ticket: "02"
title: "The 41 tables as Django models, column-exact, with the integrity SQL shipped by migration"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-server-skeleton]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The 41 tables as Django models, column-exact

The cutover trick that makes ticket 10 cheap: **table and column names, types and nullability must
match the live Supabase schema exactly**, so a `pg_dump --data-only` of `public` loads into the
Django-created tables with no transform. This ticket's acceptance test is that load.

## Method

1. `pg_dump --schema-only --schema=public` from the live project into `server/legacy/supabase_schema.sql`.
   Committed. It is the oracle for this ticket and the archive of what Supabase held.
2. `manage.py inspectdb --database supabase` against the live project. Output committed raw as
   `server/legacy/inspectdb_raw.py`, then split by hand into apps. Every model `managed = True`,
   `db_table` set to the existing name, every `db_column` set explicitly where Django would rename.
3. Apps and their tables:

| App | Tables |
|---|---|
| `household` | `household_members` (adds FK to `household.User`) |
| `fleet` | vehicles, vehicle_specs, drives, drive_reassignments, code_events, code_clear_events, obd_samples, oil_analyses, service_history, maintenance_schedules, chassis_quirks, build_entries |
| `ledger` | statements, ledger_transactions, categories, category_rules, budget_targets |
| `pantry` | receipts, receipt_line_items, grocery_staples, meal_logs, meal_targets |
| `ingest` | ingested_files (shared by ledger and pantry; the gate lives here in ticket 03) |
| `dates` | events, event_skips |
| `notes` | voice_notes, item_lists, list_items |
| `places` | places |
| `body` | bodyweight_logs, sleep_logs, sleep_targets, workout_plans, workout_plan_items, workout_set_logs, goals |
| `memory` | memories, memory_audit, companion_memories, conversation_audit |

4. Postgres enums (`public.provenance`, `public.ingest_state`) and CHECKs (`events.kind` and the
   rest) become `TextChoices` on the model PLUS the same `CheckConstraint` in `Meta.constraints`,
   so the DB still refuses what Kotlin would refuse. Where the live schema uses a real enum type,
   the initial migration creates the type with `RunSQL` and the field uses `db_type` matching it,
   so the data load's `::provenance` casts still work.
5. **Integrity SQL travels as `migrations.RunSQL`, verbatim from `supabase/migrations/`:**
   `private.forbid_mutation_of_facts` and its per-table triggers, the `updated_at` touch triggers,
   the unique index on every `origin_guid`, the `receipts.unaccounted_cents` check. The `private`
   schema is created by the same migration. ADR 0042's "a rule both writers must agree on lives in
   the database" now reads "a rule that must hold even if Django has a bug lives in the database".
   Business rules (a measured tick needs a number, a no-schedule checklist is done once) go in
   Django, ticket 04, because Django is the only writer.
6. What does NOT come over: RLS policies, `private.is_household_member`, `apply_household_rls`,
   the `keepalive` function, the `supabase_realtime` publication, `storage.*` rows, every grant.
   Django connects as the one role.
7. `origin_guid`: every table a limb can create rows in has one, `uuid`, unique, non-null on new
   rows. Ticket 04's idempotent create keys on it. Tables that lack it today get it in this ticket's
   initial migration, nullable, and the load leaves legacy rows null.

## Verification

- [ ] `manage.py migrate` on an empty Postgres, then `pg_dump --schema-only` of the result diffed
      against `server/legacy/supabase_schema.sql` with grants, policies, publication and `storage`
      filtered out: **no differences in tables, columns, types, constraints, indexes, triggers.**
- [ ] `pg_dump --data-only --schema=public` from the live project loads with zero errors, and
      `select count(*)` per table matches the source, all 41.
- [ ] `update ledger_transactions set amount_cents = 0 where provenance = 'DETERMINISTIC'` is
      refused by the trigger, from Django's connection.
- [ ] `manage.py makemigrations --check` clean.
