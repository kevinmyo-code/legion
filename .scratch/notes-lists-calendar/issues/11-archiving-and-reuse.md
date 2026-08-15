# How do lists get archived, and how is one reused next year?

Type: grilling
Status: resolved
Blocked by: 01

Graduated from the map's fog 2026-08-07 and resolved the same session. Absorbs two fog patches at
once - "archiving, deleting and finding" and "templates" - because Kevin's answer settled both in one
sentence: *"archived. eventually we want to have a master camping list that we can reproduce for
subsequent trips."*

## Question

What happens to a finished camping list, and how does next year's trip start from it?

## Answer

### Archived, not deleted

A finished list is **archived**: hidden from the list-of-lists, kept whole, reachable behind a
**SHOW ARCHIVED** toggle. This is not a new pattern - `ui/CarsScreen.kt` already does exactly this
for archived vehicles, and reusing it costs nothing and keeps the app consistent.

Archiving is distinct from the `deleted` tombstone, which stays what it is: a soft delete for sync.
An archived list is `archived = true` and very much alive.

### A "master list" is not a new concept

**It is just an archived list you copy.** Any list, archived or active, can be copied into a fresh
one. The master camping list is an archived list called "Camping" that you copy each year.

Rejected: a template flag (a third state per list, plus a rule for archiving a template) and a
separate template entity (a second table duplicating almost everything `ListItem` does - it would
have been the first real crack in charting decision 2's one model).

### What a copy carries

| Carried | Dropped |
|---|---|
| item text | `done` / `doneAt` — everything comes back unticked |
| item order (`sortOrder`) | `startsAt` / `endsAt` — last year's Friday is useless this year |
| | repeat rules — a recurring event inside a copied one-off list is almost never meant |
| | `triggerPlaceLabel` — same reasoning as times |
| | `syncId` — a copy is a NEW row and must get a new identity, never inherit one |

That last row is not cosmetic. Copying a `syncId` would make two genuinely different rows claim the
same cross-device identity, and the next sync would treat one as a stale edit of the other.

**Unticked is the load-bearing part.** A packing list that starts half-done is the one failure a
packing list must never have.

### The copy does not learn

**Copy and original are fully independent from the moment of copying.** Adding "midge spray" to this
year's list does not touch the master; if the lesson is worth keeping, you edit the master
deliberately.

Rejected: automatic write-back (editing one list silently changing another is surprising, and a
one-off addition would permanently pollute the master) and an offer-at-archive-time prompt (needs
provenance tracking, and arrives at the moment you least want a question).

**The accepted cost, stated plainly:** packing up a wet tent, you will not remember to write the
lesson back, and it will be lost. Kevin chose this knowing that.

### Consequences for other tickets

- **Ticket 01:** `ItemList` gains `archived: Boolean`. Every active-list read filters
  `archived = 0 AND deleted = 0`.
- **Ticket 05:** the most-recently-used default must **never** resolve to an archived list. Copying
  one should make the new copy the most recently used, not its source.
- **Ticket 07:** the list-of-lists screen carries the SHOW ARCHIVED toggle, matching `CarsScreen`.
- **Ticket 10's tool budget:** archive, unarchive and copy are three more verbs. They should be
  parameters or a small number of tools, not three new registrations - the map is still required to
  come out net-neutral or better on tool count.
