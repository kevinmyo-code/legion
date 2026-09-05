---
map: two-clients
ticket: "04"
title: "The first rules move down: measured ticks and done-once"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# The first rules move down: measured ticks and done-once

ADR 0042 ruling 1: business rules live in Postgres, and existing Kotlin logic migrates down as it is
touched. This ticket names the first two, so "as touched" has a starting point and a pattern to copy.
Both are in `checklists/ChecklistController.kt` today and nowhere else.

## The two rules

### 1. A measured tick needs a number

Kotlin today, `ChecklistController.tick`: an item with `measureUnit` non-null and a tick with
`value == null` returns `TickOutcome.Refused` and writes nothing. Kevin's ruling, verbatim in the
doc comment: *"a number is the point."*

In Postgres this is a **trigger**, not a CHECK, because it spans two tables (the item's unit, the
tick's value): `before insert or update on checklist_ticks`, raise `check_violation` with the same
words the Kotlin message uses - *"is measured in %s - give a number to tick it, nothing was
recorded"* - so the phone can surface the database's own message and the two never say different
things.

### 2. A no-schedule checklist is done once

Kotlin today, `ChecklistDayViewLogic` via `Checklist.scheduleKind`: a checklist with
`scheduleKind = null` applies to a plain to-do list, and an item is done when a tick exists on ANY
day (one-today ticket 09 decision 4: one tick model, no `done` boolean). A scheduled checklist is
done per day.

In Postgres this is an **RPC** the clients read through - `checklist_item_state(item_id, day)` - and
a `unique (item_id, day)` index that makes a double tick one tick. The done-once semantics cannot be a
CHECK (it is a read rule), so both clients must call the function rather than reimplementing the
`scheduleKind is null` branch. The phone's `ChecklistDayViewLogic` becomes a caller of that function's
Room-replicated result, or is deleted in favour of it.

## Precondition, and it is the real work

**The checklist tables are not on the server yet.** `checklists`, `checklist_items` and
`checklist_ticks` exist in Room only; ticket 09 deferred sync to "a later slice". A rule cannot move
into a database that has no table for it. So this ticket carries:

1. One migration in `supabase/migrations/` creating the three tables on the standard four-part
   template (write-through, backfill, merge pull, Realtime), with the sync columns, the
   `(item_id, day)` unique index, `private.apply_household_rls` on each, and the two rules above in
   the same file. The table and its rules land together; a table that arrives ruleless and gets its
   trigger later is a window in which a second client can write what the phone would refuse.
2. The Kotlin side: `ChecklistController` writes through an RPC (`tick_checklist_item`) and maps a
   `check_violation` back to `TickOutcome.Refused` with the server's message. The local refusal may
   stay as a fast path; the server's is the one that counts.
3. The Kotlin controller takes its dependencies as parameters while it is open - it currently takes
   `Context` on every method. CLAUDE.md's Android convention: migrate as touched.

## What does NOT move here

- `events.kind`'s CHECK already lives in Postgres. Nothing to do.
- The §4 gate is already `commit_statement` / `commit_receipt`. Nothing to do.
- The discussion sub-deadline rule moves with [[03-canvas-poller]], because its RPC is the poller's
  write path and there is no reason to build it twice.
- Day-boundary logic (`ChecklistController.today`, local-day conversion). A day is a client fact
  about where the user is standing; the server stores the integer day the client sends and does not
  recompute it.

## Verification

- [ ] From the phone, tick a measured item with no number: refused, and the message shown is the
      server's, word for word.
- [ ] From `psql` as `legion_django`, `insert into checklist_ticks` with a null value on a measured
      item: refused with the same message. That is the whole point of the ticket and the one check
      that proves a rule has actually moved.
- [ ] Insert the same `(item_id, day)` twice: one row.
- [ ] `checklist_item_state` for a no-schedule item ticked last week, queried for today: done.
- [ ] Room migration for any column this adds: verbatim generated SQL, additive, JSON committed,
      `SCHEMA_VERSION` in lockstep, migration test. §5, unchanged. Do not quote the version number
      here; read it from `CarDatabase.kt`.
