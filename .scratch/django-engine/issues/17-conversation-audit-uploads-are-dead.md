---
map: django-engine
ticket: "17"
title: "Conversation-audit uploads have been failing since the tenancy migration"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Conversation-audit uploads are dead

Found on the A25 on 2026-09-10, in logcat, while running `one-home` ticket 08's ship pass. It was
not on that ticket's list; the device agent flagged it because it was there.

```
SupabaseConversationAuditBackend.uploadConversationAuditBatch
there is no unique or exclusion constraint matching the ON CONFLICT specification
```

**Every conversation-audit upload this session failed.** The rows are still on the phone; nothing has
reached the server since the tenancy migration was applied.

## The chain, and each link was checked rather than assumed

1. `backend/SupabaseConversationAuditBackend.kt:92` upserts the batch with `onConflict = "client_uuid"`.
   That target moved from `(device_id, local_id)` to `client_uuid` on 2026-09-06.
2. PostgREST sends it as a bare `ON CONFLICT (client_uuid)`, and Postgres must be able to **infer** a
   unique index from that clause alone.
3. `conversation_audit` is in `server/household/tenancy.py`'s `TENANT_TABLES` (line 71).
4. `server/household/migrations/0002_households_are_tenants.py` "reads each table's unique keys off
   the Postgres catalogs, drops them and rebuilds" them scoped by household - **so the unique index
   on `client_uuid` is now on `(household_id, client_uuid)`.**
5. A bare `ON CONFLICT (client_uuid)` cannot infer a two-column index. Hence the error.

**So this is a regression introduced by the 2026-09-08 tenancy work**, not a new bug in the audit
path. It went unnoticed because nothing surfaces an upload failure - the rows queue silently, and
`/health` still answers `{"db":"ok"}`.

## It is the second time this exact shape has bitten, which is why the fix matters more than the patch

`library/lessons.md` L-2026-09-07 - written the day BEFORE the tenancy migration - says: *"A
constraint is not only a rule; it is also an interface... Anything a client names in an `ON CONFLICT`
clause must be inferable from that clause alone."* That lesson was learned against a PARTIAL index.
The same rule was then broken from the other direction, by WIDENING the index, by a migration whose
whole job was to widen every unique key in the database.

**Nothing connected the two**, because the tenancy migration re-keyed 44 unique indexes generically
off the catalogs and had no way to know that one of them was a conflict target a client names by
hand.

## The fix: move it to Django, do not repair the Supabase call

Tempting patch: change `onConflict` to `"household_id,client_uuid"`. **Do not**, except as a stopgap
Kevin explicitly asks for.

`conversation_audit` is **the only write path still hardwired to Supabase.** All nine aspects in
`EngineTransport.KNOWN_ASPECTS` were flipped to Django on 2026-09-10; this one is not an aspect and
is not in the switch - `backend/ConversationAuditReconcile.kt:329` constructs
`SupabaseConversationAuditBackend(client)` directly, with no transport check. ADR 0044 makes Django
the engine and the only writer, and django-engine 10 has Supabase's PostgREST going dark at cutover.
Repairing the on-conflict string buys a path that is scheduled to stop existing, and hides the fact
that one writer was left behind.

So:

1. **A Django endpoint** for the audit batch, idempotent on `client_uuid` **within a household** -
   the uniqueness the tenancy migration actually created. Server-side, so the conflict target is a
   Django concern and not a string a client has to guess (ADR 0044: a write path is a Django endpoint
   first and a Kotlin caller second).
2. **A `DjangoConversationAuditBackend`** behind the existing `ConversationAuditBackend` interface -
   that seam already exists, which is why this is a new implementation and not a rewrite.
3. **Route it through `EngineTransport`** rather than constructing a backend directly, so it stops
   being the one path that cannot be switched. Decide whether it becomes a tenth `KNOWN_ASPECTS`
   entry or rides on an existing one; a tenth entry is the honest answer if it can be toggled alone.
4. **Backfill what queued up.** The rows are still on the phone. Confirm the reconcile path carries
   them once a working backend exists rather than assuming a cursor moved past them - L-2026-09-06's
   finding was exactly a cursor that advanced over rows that never uploaded.

## The check that would have caught it, and should exist

**An upload failure is invisible today.** `MidnightEvents` records it and nothing reads that back.
Whatever lands here, add the check that makes silence impossible: either a surface that says in words
that N audit rows are unsent, or a test that exercises a real upsert against a real schema. The
lesson's own closing line applies unchanged: *"if a change to an index is meant to unstick a write
path, exercise that exact write against it in a rolled-back transaction before shipping."*

## Wider question this raises, worth one look rather than a separate ticket

The tenancy migration re-keyed **44** unique indexes. This one was caught because a phone happened to
be attached while someone was looking at logcat. **Are any of the other 43 named as a conflict target
by a client?** Grep the Kotlin for `onConflict =` and the server for `on_conflict`, and check each
against what 0002 rebuilt. That is a bounded search and it is how you find out whether this is one
bug or the first of several.

## The bounded search, done 2026-09-10, and the answer is better than feared

Every string conflict target in the Kotlin tree, by file:

| File | Targets |
|---|---|
| `SupabaseConversationAuditBackend.kt` | `client_uuid` - **the live bug** |
| `SupabaseFleetBackend.kt`, `BodyBackfill.kt`, `SupabaseBodyBackend.kt`, `SupabaseEventsBackend.kt`, `SupabaseMemoryBackend.kt`, `SupabasePlacesBackend.kt`, `SupabaseLedgerConfigBackend.kt`, `SupabaseLastAspectsBackend.kt` | `origin_guid` (18), `sync_id` (6), `vehicle_id`, `vehicle_id,service_name`, `vehicle_id,pid,recorded_at`, `quirk_id`, `label`, `event_id,skip_date` |

(The 41 `OnConflictStrategy.*` hits are Room annotations and have nothing to do with this - different
`onConflict`, same word.)

**Only the first is live.** Every other file in that table is a `Supabase*Backend`, and all nine
aspects in `EngineTransport.KNOWN_ASPECTS` were flipped to Django on 2026-09-10, so none of those
upserts runs on the phone today. `conversation_audit` is broken precisely BECAUSE it is not an aspect
and was not flipped - `ConversationAuditReconcile.kt:329` builds its backend directly.

**But they are not safe, they are dormant**, and that distinction is the real finding here. The
per-aspect toggle still works in both directions - pinned by a unit test on 2026-09-10 (`an explicit
SUPABASE row is honoured even though Django is the default`). **That is `tested`, not `on-device`:
the A25 ship pass the same day never flipped an aspect back to Supabase**, so what a real
Supabase-bound write does on the phone today has not been seen by anyone. So
**flipping any aspect back to Supabase now lands on an upsert whose conflict target the tenancy
migration re-keyed underneath it.** The escape hatch that exists for when Django misbehaves is itself
broken, and it will look like whatever the user was doing at the time rather than like this.

Whoever takes this ticket decides which is true, and says so in words rather than leaving it:

- **The fallback is real** - then these need the same fix as `conversation_audit`, or the toggle needs
  to refuse Supabase for a re-keyed table rather than fail at write time.
- **The fallback is dead** - then django-engine 10's cutover should delete these backends rather than
  leave a path that compiles, is reachable from a settings toggle, and cannot work.

Leaving it undecided is the option that produces a confusing bug report in three months.
