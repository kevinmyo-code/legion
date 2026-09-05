---
status: accepted
decided: 2026-09-05
decided-by: Kevin
supersedes: [0038-byo-supabase-is-the-system-of-record, 0043-django-is-the-second-client]
source: "[[decisions#2026-09-05 - Django is the engine: one server, a native Android limb, a web limb]]"
tags: [adr]
---

# 44. Django is the engine

## Standing

**LEGION is one Django server over one Postgres, and every other piece is a limb that talks to it
over HTTPS JSON.** Django owns the schema and its migrations, runs the section 4 gate, holds auth,
stores media, and runs the scheduled worker. It is the only writer to Postgres. The Android app is a
device limb: voice, OBD, wake word, and a Room replica as a read cache. A server-rendered web app is
the second adult's limb, installable on an iPhone. A future device (a home robot) is another limb
with another device token. Supabase retires.

Kevin, 2026-09-05: *"im thinking we copy instagram right, django with 2 native apps reading it"*,
and to the shape above, *"yeah i think thats the way to go."*

**This reopens CLAUDE.md section 2's clone-and-run row and the hosting rule in section 7, on
Kevin's own words**, and the matching edits land in the same commit as this file.

## Context

Two clients writing one Postgres through PostgREST needed every rule in SQL so neither client could
skip it ([[0042-business-rules-live-in-postgres]]), a service role for the second client that
bypassed row security, and a second identity system beside Supabase Auth for the web app. The
2026-09-05 architecture review found the contract thinner than the ADRs claimed (three RPCs, no
`created_by` anywhere) and the assistant's 112 tools welded to Kotlin. The conventional shape,
Instagram's, resolves all three: one API server owns the data, phones and browsers are clients of
it, and rules live once, in the server.

## Decision

1. **Django owns the schema.** `server/*/migrations/` is the only migration owner. Integrity rules
   that must hold even when Django has a bug stay in SQL and ship as `RunSQL` migrations:
   `forbid_mutation_of_facts`, `updated_at` touch triggers, the `origin_guid` unique indexes, the
   `unaccounted_cents` check. Business rules live in Django, once.
2. **The gate moves language, not posture.** Section 4 rules 1 to 8 bind the Python
   implementation exactly as they bound the plpgsql. Same request, same response, same corpus.
3. **Limbs authenticate with a device token**, one per phone, browser or robot, revocable alone.
   Users are made in the admin. Two adults, no roles, no tenancy, one household per server.
4. **The phone depends on the server to write, and never to read.** Room is a full replica; writes
   wait in the outbox; the app says in words when the server is unreachable. This replaces
   [[0043-django-is-the-second-client]]'s "if Django is down the phone loses freshness, never
   function". Function now means reads.
5. **Clone-and-run is restated, not relaxed** ([[0003-clone-and-run]]): clone, `docker compose up`,
   make two users, point the app at the URL. Nothing Kevin-hosted is needed; the household hosts its
   own, on a home box or a rented one (`.scratch/django-engine/issues/07-where-it-runs.md`).
6. **No Firestore, no broker, no hosted key**: unchanged, and now also no Supabase.

## Consequences

- [[0038-byo-supabase-is-the-system-of-record]] and [[0043-django-is-the-second-client]] are
  superseded. [[0042-business-rules-live-in-postgres]] is amended: one migration owner is now
  Django; the "must hold for every writer" rules stay in SQL because that is where they are safest,
  not because there is a second writer.
- supabase-kt leaves the Android build; Ktor and kotlinx-serialization stay. Realtime becomes a
  poll: foreground, after every write, every 60 s.
- The twelve `*Backend` Kotlin interfaces are unchanged; their implementations change. That seam is
  why this is a swap and not a rewrite.
- Backups exist for the first time since the xlsx mirror retired: a nightly dump with a drilled
  restore, before any other worker job.
- The map: `.scratch/django-engine/map.md`, eleven tickets.
