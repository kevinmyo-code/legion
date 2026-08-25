---
map: backend-erp
ticket: "05"
title: "The migration path: 569 records to Postgres without a bad day"
type: grilling
status: open
status-detail: "Now owns three retirements handed down by ticket 01; still blocked on 02/03"
blockers: ["01", "02", "03"]
blocked-by: ["[[01-what-the-backend-owns]]", "[[02-auth-and-identity]]", "[[03-the-gate-server-side]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# The migration path: 569 records to Postgres without a bad day

## Question

The cutover-arc playbook, aimed at the network: schema up (SQL migrations in the repo, so a
stranger runs one script against their fresh project - clone-and-run); guid-keyed idempotent
upload of the engine records; per-aspect cutover of the WRITE path (Room keeps reading until
verified); the second phone and its divergent data (guid merge, updatedAt rule); the rollback
story; what the eval harness and screenshot tests owe this arc. Sequencing vs the soak follow-ups
already on the board: the legacy-table drops WAIT until after this arc - dropping local history
before the backend is proven would be the bad day.

## What ticket 01 handed down (2026-08-25) - this ticket now owns three retirements

Ticket 01 resolved with eleven rulings and deliberately deferred ALL sequencing here. Nothing is
deleted until this ticket says in what order. Three retirements land on this ticket, plus one
ordering constraint that is already binding:

1. **The generic engine retires; the phone goes typed** (ruling 7). Measured footprint in ticket
   01: 9,518 production lines + 6,367 test lines. **Read ticket 01's "the finding that makes
   ruling 7 far cheaper than it looks" before planning this** - the engine retired zero legacy
   tables, so most of this is repointing writes back to typed tables that still exist rather than
   building new ones. The legacy tables are stale by roughly a day for the aspects that cut over
   on 2026-08-24 (ledger, fleet, and the notes/places/pantry waves), so the shape is
   **reconcile-and-repoint, per aspect, engine-records-remain-truth-until-the-diff-is-clean** -
   never a blind switch back.
2. **The Notes `Item` type merges into the Dates `Event` type** (ruling 4). 21 fields into 7, with
   recurrence, `list_item_skips`, geofenced place triggers, a second alarm stack and the
   goal-checklist materializer attached; 64 Kotlin files. Ticket 01's cost inventory has the full
   list. Collapse the three disjoint agenda paths here rather than porting all three.
3. **Google Calendar is removed** (ruling 5), and **the order is already ruled** (ruling 11):
   widen the importer (description/location/allDay, parse the `LEGION::v1` block into real
   fields, unbounded window), run it, verify, and only THEN remove the Google path. Cutting first
   permanently deletes class metadata that exists nowhere else. `CalendarProvider.kt` also writes
   to Google and has zero test coverage - both facts matter to the removal order.

**Ordering constraint carried in from ruling 8:** writes go direct to Postgres with no offline
queue, so there is no local buffer to hide a bad cutover behind. Every per-aspect write cutover
needs its own rollback, and the ticket-06 keep-alive against the 7-day free-tier pause is
load-bearing before the first write cutover, not after.

The pre-existing rule stands and is now sharper: **legacy-table drops wait until the whole backend
arc is proven.** With ruling 7 in play they may not be drops at all - some of those tables are
where the phone is going back to.
