---
map: two-clients
title: "Two clients, one Postgres"
charted: 2026-09-05
charted-by: Fable
effort: ""
tickets: 5
open: 5
status: open
tags: [map]
---

# Two clients, one Postgres

**Kevin, 2026-09-05**, asked whether LEGION should adopt an Android framework: *"well, 1 live
backend, a django app that will consume that, plus our android app."* Then *"go with both"* to
writing the rulings and the Android convention.

## The shape

| Piece | Owns | Does |
|---|---|---|
| Supabase Postgres | every table, every rule | CHECKs, triggers, RPCs. `supabase/migrations/` is the only migration owner |
| Android app | nothing | voice-first client. Calls RPCs, renders, replicates to Room |
| Django app | nothing | what must run while the phone is asleep, plus desk UI. Calls the same RPCs |

The rulings are ADRs, not tickets: `docs/adr/0042-business-rules-live-in-postgres.md` (rules in
Postgres, one migration owner, Django's own role) and `docs/adr/0043-django-is-the-second-client.md`
(Django's role, and the hosting rule narrowed to "nothing the PHONE depends on"). This map is the
work those two ADRs leave owed.

## The tickets

| # | Type | What |
|---|---|---|
| 01 | build | The Postgres role, the `django` schema, `inspectdb` models |
| 02 | decision | Where Django runs. Open: Fly.io, Railway, a home box |
| 03 | build | The Canvas poller. Absorbs one-today ticket 08's edge function; its discussion rules bind verbatim |
| 04 | build | The first Kotlin rules move down: the measured-tick refusal and checklist done-once |
| 05 | build | The WebAssign completion read. Dates from the syllabus; WebAssign read for completion only |

**Order.** 01 first: nothing Django does exists without a role and a schema. 02 is independent and
can be decided any time, but 03 cannot run on a schedule until it is. 04 needs no Django at all and
can go in parallel with everything; it is the phone's half of ADR 0042. 05 is blocked on 01 only.

## What this map does NOT cover

- The Android convention (no framework, dependencies as parameters, one `AppGraph`). That is a
  CLAUDE.md section, written by the orchestrator, and binds new Kotlin code regardless of Django.
- Migrating the 33 existing controllers. ADR 0042 says as-touched, never a sweep; there is no
  ticket for a sweep because there will be no sweep.
- Any Django UI beyond admin. The desk surface is ADR 0040's PC surface; which screens it gets is a
  later map.
