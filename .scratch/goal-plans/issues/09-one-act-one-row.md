---
map: goal-plans
ticket: "09"
title: "A ticked workout is one act, not two rows"
type: bug
status: built
status-detail: "Fixed at v33: swept logs carry their source item, untick deletes the log, the sweep skips a day already logged. Kevin's data checked - no phantom rows existed."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# A ticked workout is one act, not two rows

Found by the bug hunt Kevin ordered after the oil-change defect, in code that shipped to his phone
hours earlier (ticket 08's auto-log). **Same disease as [[29-one-source-for-service-history]], in
the body domain: one real-world act living as two independent rows.**

The tick lives on `ListItem` (`done`/`doneAt`). The sweep writes a separate `WorkoutSetLog`. Nothing
connects them.

## Two defects, both traced, both reachable by ordinary use

### 1. Unticking leaves a phantom set behind, permanently

`sweepPastDayAutoLog` writes the log and stamps `ListItem.loggedAt`. `NotesController.untick` clears
`done`/`doneAt` and **never touches `loggedAt` or the log row**. Nothing anywhere deletes a
`WorkoutSetLog` in response to an untick.

Tick Monday's line by mistake, app opens Tuesday and logs it, notice and untick: the checklist shows
it undone while the training history and every voice answer still count it. **Forever.**

Worse, it is unreachable: the swept row's `loggedAt` is backdated to the item's own day (correct, by
ticket 08's design), so `undo_last_log` - which selects the most recent - can never reach it once
anything else has been logged since.

### 2. Logging by hand AND ticking double-counts

`WorkoutController.logSet` inserts unconditionally and never touches `ListItem`. The sweep only
checks `ListItem.loggedAt`, never whether a matching set already exists that day. Log 3 sets of
squats by voice in the moment, tick the visible line later that evening, and Tuesday's sweep writes
a second identical row. Volume inflated, two entries in the history, nothing warns.

## The fix: the log knows where it came from

1. **Link the row to its origin.** An additive nullable column on `WorkoutSetLog` naming the
   `ListItem` that produced it (v33). A hand or voice log leaves it null; a swept log carries the
   item id. That single link is what makes both defects fixable and is the smallest step toward the
   one-act-one-fact shape ticket 29 argues for.
2. **Untick undoes the log.** Unticking an item that has a linked log deletes that log and clears
   `loggedAt`, so a correction propagates to every surface. Do this in the write path both the voice
   tick and the checkbox already share, so neither can miss it.
3. **The sweep never double-writes.** Before writing, check for an existing log for that exercise on
   that day. If one exists, the tick is adherence ONLY - stamp `loggedAt` so it never retries, and do
   not write. **A user who logged it and also ticked it did one workout, not two.**
4. **Say nothing that is not true.** Neither path may report a log it did not write.

## Verification

- Suite green both ways, one run fresh. Migration test for v33 (verbatim generated SQL, additive,
  `exportSchema`, `SCHEMA_VERSION` bumped with `@Database`).
- Tests: untick after a sweep removes the log and clears the anchor; a manual log then a tick
  produces exactly ONE row; a tick alone still produces one; the linked column is null for hand and
  voice logs.
- **Kevin's phone already has phantom rows if he has ticked and unticked anything.** Say plainly in
  the report whether any cleanup of existing rows is needed, and do not silently delete history to
  make the numbers look right.
