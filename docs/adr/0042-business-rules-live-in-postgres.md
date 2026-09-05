---
status: amended
decided: 2026-09-05
decided-by: Kevin
amended: 2026-09-05
source: "[[decisions#2026-09-05 - Two clients, one Postgres: the rules live in the database]]"
tags: [adr]
---

# 42. Business rules live in Postgres, not in either client

## Standing

**AMENDED 2026-09-05 by [[0044-django-is-the-engine]]:** Django is the only writer and owns the
migrations. Integrity rules (immutable facts, touch triggers, unique `origin_guid`, the
`unaccounted_cents` check) stay in SQL, shipped by Django migrations. Business rules live in Django.
The text below is the 2026-09-05 morning ruling, kept for its reasoning.

**LEGION is a two-client system: one Supabase Postgres, the Android app, and a Django app. Any rule
both clients must agree on is enforced in the database - CHECK constraints, triggers, RPC functions -
and the clients call RPCs and render.** `supabase/migrations/` is the only migration owner. Django
reaches Postgres directly, with its own role.

Kevin, 2026-09-05, asked whether LEGION should adopt an Android framework: *"well, 1 live backend, a
django app that will consume that, plus our android app."* Then *"go with both"* to writing these
rulings and the Android convention alongside them.

## Context

With one client, a rule in a Kotlin controller was the rule. `ChecklistController.tick` refuses a
valueless tick on a measured item; `ChecklistDayViewLogic` decides that a checklist with no schedule
is done once a tick exists on any day; ticket 08 rules that a Canvas discussion's parent
`submitted_at` does not complete its sub-deadlines. Each of those lives in Kotlin today and nowhere
else. The moment a second client writes the same tables, every one of them has to be written again in
Python, and two implementations of one rule drift into disagreeing - the exact failure
[[0035-every-voice-capability-has-a-hands-path]] names for two paths to one capability, now across a
language boundary where no shared test can catch it.

The database already enforces its most important rules this way. `private.forbid_mutation_of_facts`
makes gated rows immutable by trigger; `public.commit_statement` and `public.commit_receipt` run the
§4 gate as RPCs; `events.kind` carries a CHECK. Those hold for every client because no client can
skip them. The rules still in Kotlin hold only for the phone.

## Decision

1. **Business rules live in Postgres.** Anything both clients must agree on is a CHECK, a trigger, or
   an RPC. The first four, named so nothing is abstract: a measured tick needs a number; a
   no-schedule checklist is done once; a discussion parent's `submitted_at` does not complete its
   sub-deadlines; the §4 gate's anchors. Clients call RPCs and render what comes back. A client may
   pre-validate for a faster error message, never as the only check. **Existing Kotlin controller
   logic migrates down as it is touched, not in a sweep** - the same posture as the Android
   convention in `CLAUDE.md` ("No framework. Dependencies are parameters.").
2. **One migration owner: `supabase/migrations/`.** Django models are `managed = False`, generated
   from `inspectdb` and hand-corrected. `manage.py migrate` never touches a LEGION table. Django's
   own `auth_*`, `django_*` and admin tables live in a separate schema named `django`, created and
   migrated by Django, and never referenced by the phone or by any LEGION migration.
3. **Django connects to Postgres directly**, with a dedicated role, not through PostgREST or
   supabase-py for data access. RLS is table-level with one household, so a service-level role is
   acceptable. **The role is never the phone's anon or authenticated key**, and never the Supabase
   `service_role` JWT: a leaked Django credential must be revocable without rotating what the phone
   holds.

## Consequences

- An RPC is now the unit of a write, not a table insert. A new write path is a `create function`
  in a migration first, and a Kotlin caller second. The `commit_*` RPCs are the shape.
- A rule that cannot be expressed as a CHECK or a trigger goes in an RPC that both clients call.
  A rule that lives in one client and not the database is a bug against this ADR, not a style
  choice, once the second client can reach that table.
- `inspectdb` output is a derived artefact. When `supabase/migrations/` changes a table Django
  reads, the Django model is regenerated; it is never edited to lead.
- The `private` schema stays unreachable from PostgREST and becomes reachable from the Django role,
  because the Django role is a database role, not an API consumer. That asymmetry is intended.
- The Room replica is unchanged by this ADR. The phone still writes through to the backend and
  pulls back; what changes is that the write is an RPC that carries the rule, so a phone with a
  stale controller cannot store a row the database would refuse.
- Tickets: `.scratch/two-clients/issues/01-schema-ownership-and-django-role.md` and
  `.scratch/two-clients/issues/04-first-rules-move-down.md`.
