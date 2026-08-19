---
map: notes-lists-calendar
ticket: 09
title: "How does this sync across two phones?"
type: grilling
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-entity-model-and-cartask-migration]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# How does this sync across two phones?

## Question

Kevin and his wife both run LEGION. `CarTask` already carries the full sync apparatus - `syncId`,
`updatedAt` for last-write-wins, and `deleted` tombstones - and charting decision 1 says that
apparatus must survive absorption. Decide what syncing a shared list actually means.

### What must be decided

1. **Is a list personal or shared?** Currently in the map's fog. If this ticket can settle it,
   graduate it out of the fog. A camping list is obviously shared; a private note obviously is not.
   Decide whether that is a per-list flag or a blanket rule.
2. **What last-write-wins does to a list.** LWW on a whole list is wrong if two people add different
   items at once - one set of additions vanishes. LWW per *item* is much better behaved. Decide the
   granularity, and be explicit about what is lost.
3. **The known Drive problem.** CLAUDE.md §2 records it as still open and still unresolved: **Drive
   has no compare-and-swap**, so today's shared-file last-write-wins sync will silently lose rows,
   and sync must become append-only. This domain is the first one where two people would routinely
   write at the same time, so it hits that hole harder than anything already built.
4. **Whether this effort must solve it.** It may be legitimate to decide that notes sync is deferred
   until the append-only rework happens, and ship a local-only domain first. That is a real option
   and probably the honest one - say so if it is.
5. **Ticked state, specifically.** Two people packing from the same list, both ticking things, is
   the exact concurrent-write case. If anything is going to lose data, it is this.

### Hard constraint

**`sync/` has never executed once.** It could not, until 2026-08-04, and it is still blocked on an
unregistered OAuth client. Any decision here rests on machinery that has never run. Do not treat
existing sync behaviour as verified; it is not. Reason from the code and say so.

### Watch for

Do not let this ticket quietly grow into fixing Drive sync. That is a separate, larger effort with
an external blocker. This ticket decides what notes *require* of sync, and whether they can ship
without it.

## Answer

**Notes and lists do not sync. Local-only on each phone** (Kevin, 2026-08-07).

### Why, on facts rather than caution

- **`sync/` has never executed once.** Until 2026-08-04 it structurally could not, and it is still
  blocked on an OAuth client that has never authorized.
- **Drive has no compare-and-swap.** CLAUDE.md §2 records this as open and unresolved: today's
  shared-file last-write-wins will silently lose rows, and sync must become append-only first.
- **Two people ticking the same packing list is the worst possible first test** of machinery that
  has never run, and its failure mode is silent loss rather than an error.

### The accepted cost, stated plainly

**A shared camping list is not actually shared.** Kevin and his wife each keep their own. This is the
single most visible capability cut on the map, and it should be said out loud in the UI rather than
left to be discovered - a list that looks shareable and silently is not is worse than one that never
pretended.

### What is still carried

**Every sync column stays**: `syncId`, `updatedAt`, `deleted` tombstones, on both entities and on
`list_item_skips`. They come free from `CarTask`'s existing shape, stripping them would be a
deliberate act, and carrying them means enabling sync later needs no migration. Local-only means no
transfer is wired, not that the rows forget who they are.

**When sync does arrive it must be per-ITEM last-write-wins, never per-list.** LWW on a whole list
loses one person's additions entirely when both add at once. Recording it here so the later effort
does not have to rediscover it.

### Graduated out of the fog

The map's "whose list is it - personal or shared" question is **answered by this**: every list is
personal, because nothing syncs. It is removed from Not yet specified. It returns as a real question
only when the append-only rework does.

### Scope held

This ticket deliberately did not grow into fixing Drive sync. That remains a separate, larger effort
with an external blocker.
