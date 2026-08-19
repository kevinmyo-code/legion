---
map: notes-lists-calendar
ticket: 01
title: "What is the entity model, and what happens to `car_tasks`?"
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What is the entity model, and what happens to `car_tasks`?

## Question

Charting settled the shape in words (a list owns items; a note is a list whose items do not tick; a
calendar event is an item with optional times). This ticket turns that into an actual schema, and
decides the migration.

**Nearly every other ticket on this map is blocked on this one.** Resolve it first.

### What must be decided

1. **The two entities' fields.** A list (name, whether its items tick, ordering, provenance) and an
   item (text, done, order within the list, optional `startsAt`/`endsAt`, optional trigger, the
   recurrence hook ticket 04 will need).
2. **How `CarTask` becomes an item.** `CarTask` is already close: global (deliberately not
   vehicle-keyed), `text`, `category`, `done`, `createdAt`, `doneAt`, `updatedAt`, `syncId`,
   `deleted`. Read its doc comment before deciding - the "kept global" and soft-delete-tombstone
   choices both have stated reasons that must survive.
3. **What happens to `category`.** `CarTask.category` is a free string ("maintenance", "project",
   "wishlist"). With named lists, list membership and category overlap. Does category survive as a
   second axis, or do the three categories become three lists?
4. **How `PlaceReminder` becomes an item** carrying a place trigger, per charting decision 4.
5. **The migration itself.** Room v9 -> v10, additive, verbatim generated SQL, `exportSchema`,
   schema JSON committed, migration test. **Existing rows must keep their `syncId` and their
   `deleted` tombstones** - both are cross-device sync facts, and losing them would resurrect
   deleted rows on the next sync from a remote snapshot that never saw them disappear.
6. **The trust tier.** Everything here is REPORTED (`.scratch/legion-shape/issues/02-trust-tiers.md`).
   Confirm nothing in this domain can ever read as PROVEN, and that a figure mixing the two says so.

### Constraints

- Charting decisions 1, 2, 4 and 6 are binding. Do not reopen them.
- Ticket 04 (recurrence) is IN scope, so the item shape must leave room for it. Do not design a flat
  events table that recurrence has to be retrofitted onto - that is the exact trap recurrence was
  taken in-scope to avoid. Coordinate with ticket 04 rather than pre-empting its model.
- CLAUDE.md §5: additive migrations only, verbatim generated SQL, no destructive fallback.

## Answer

Two entities, Room **v9 -> v10**, and `category` is gone.

### The entities

**`ItemList`** — `id`, `name`, `tickable: Boolean` (false makes it a note), `sortOrder`,
`lastUsedAt` (drives ticket 05's most-recently-used default), `createdAt`, `updatedAt`, `syncId`,
`deleted`.

**`ListItem`** — `id`, `listId`, `text`, `done`, `doneAt`, `sortOrder`, `createdAt`, `updatedAt`,
`syncId`, `deleted`, plus:
- `startsAt: Long?`, `endsAt: Long?`, `allDay: Boolean` — an item with a `startsAt` is an event
  (charting decision 6). Index `startsAt`; ticket 08's calendar query must never scan the untimed
  camping gear sharing this table.
- `triggerPlaceLabel: String?` — the absorbed `PlaceReminder` behaviour.
- the repeat columns ticket 04 specifies.

**At most one trigger** (charting decision 4): `startsAt` and `triggerPlaceLabel` are mutually
exclusive, enforced in the controller, not by a CHECK constraint (the rest of this schema uses no
CHECK constraints and consistency is worth more here than belt-and-braces).

### `category` is dropped (Kevin, 2026-08-07)

All car tasks land in **one list named "Car"**. The maintenance/project/wishlist enum
(`LiveToolbox.kt:798`) does not survive in any form. Stated cost, accepted: "what do I need to buy
for the car" stops being separately answerable and becomes one pile.

### The migration

v9 -> v10, additive:
1. Create `item_lists` and `list_items`.
2. Insert one list, "Car".
3. Copy every `car_tasks` row into `list_items` under it, **preserving `syncId`, `deleted`,
   `updatedAt`, `createdAt`, `done` and `doneAt` verbatim**. Losing a tombstone would let the next
   sync resurrect a deleted row from a snapshot that never saw it disappear - `CarTask`'s own doc
   comment spells this out.
4. Copy `place_reminders` into a list called "Reminders", each carrying `triggerPlaceLabel`.

**Do NOT drop `car_tasks` or `place_reminders` in this migration.** Leave both in place, unread, for
one version. CLAUDE.md §5 forbids destructive migrations and this is the case it exists for: a copy
that silently mis-maps a column is recoverable while the source table is still there, and
unrecoverable the moment it is not. Drop them in a later, separate migration once the new tables have
run on both phones.

Verbatim generated SQL, `exportSchema`, `10.json` committed, migration test - and note that the
three existing migration tests **have still never been run** (ADB unpaired), so this one lands on a
stack of unexecuted ones.

### Trust tier

Everything here is REPORTED, permanently. Nothing in this domain can read as PROVEN, and no figure
mixes the two - there are no figures. Charting decision 3 having been withdrawn, **nothing here
narrows CLAUDE.md §4 at all**.
