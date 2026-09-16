---
map: web-calendar-and-lists
ticket: "02"
title: "Delete a list, from Lists and from Home, and offer it when everything is ticked"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Delete a finished list

**Kevin, 2026-09-16:** *"groceries and other lists, if i tick everything, i should be able to
delete the list. also let me delete list from the home screen."*

## What already exists

- `DELETE /api/checklists/{checklist_id}` - **already built, already soft, already idempotent**,
  and deliberately non-cascading (see the map). Nothing is needed on the server.
- `_authed.lists.tsx` has delete for an ITEM (`Trash2` on `ItemRow`) and none for the list itself.
- Home has no lists at all.

## What to build

1. **A delete control on the list card**, in `_authed.lists.tsx`. Same `Trash2` vocabulary the
   item rows already use, so a reader does not have to learn a second one.
2. **When every live item on a list is ticked for today, the card offers the delete** - a visible
   "all done" state with the delete as its action.
3. **Lists on Home, with the same delete.** Home currently shows the day and the horizon; a
   ticked-out Groceries list is exactly the kind of thing Kevin wants to clear without navigating.

## Two rulings this ticket needs, both made here

**It OFFERS, never auto-deletes.** Ticking the last line must not make a list vanish. Auto-delete
on completion would mean the app destroys a surface as a side effect of ordinary use, with no
moment for "wait, I mistapped" - and the tick that triggered it is one tap from being undone. The
completed state is a visible affordance and the delete is a deliberate second act.

**A delete says what it keeps.** The confirmation says the history is kept - because it is, and
because ticket 04 is about to build a feature that depends on it. A user who believes deleting
Groceries erases six months of "when did I last buy X" will not delete it, and will be wrong about
what the app did. One line: *"The list goes. What you ticked off it is kept."*

## Failure path

The list is a real network call and it can fail. On failure the card stays, says it could not
delete and why. **A card that disappears optimistically and comes back on the next poll is worse
than one that never moved** - `_authed.lists.tsx`'s existing mutations already state their errors
in words rather than silently reverting; match that.

## Verification

- `npm run build`, `npx tsc --noEmit` clean.
- Vitest: a list whose live items are all ticked for today reports complete; one with an unticked
  item does not; a list with no items is NOT reported complete (nothing was done - "empty" and
  "finished" are different sentences).
- Against the real engine: create a list, tick it out, delete it, confirm the ticks survive by
  reading `/api/changes?aspects=checklists` and finding the tick rows still present with the
  checklist tombstoned. **That round trip is the whole point of the map and must not be claimed
  without running it.**
