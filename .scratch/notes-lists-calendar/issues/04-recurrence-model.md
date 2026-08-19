---
map: notes-lists-calendar
ticket: 04
title: "How does recurrence work?"
type: grilling
status: resolved
status-detail: ""
blockers: ["01", "03"]
blocked-by: ["[[01-entity-model-and-cartask-migration]]", "[[03-android-alarm-mechanism]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# How does recurrence work?

## Question

Kevin took recurrence IN scope during charting, against the recommendation to defer it, on the
argument that retrofitting it onto a flat events table is worse than designing for it. That argument
is correct and it makes this the most expensive decision on the map. Decide the model.

### What must be decided

1. **How a repeat is expressed.** A full RFC 5545 RRULE subset, or a small hand-rolled set (daily,
   weekly on chosen days, monthly on a date, yearly)? Name what is NOT supported.
2. **Stored series vs materialised occurrences.** Does the database hold one row with a rule, and
   occurrences are computed on read? Or are occurrences written out ahead of time? This is the
   central fork and it decides everything below it.
3. **Exceptions.** Skipping one week, or moving one occurrence, without disturbing the series.
4. **"Edit this one or all of them."** Every calendar app asks this. Decide whether LEGION asks,
   or picks one behaviour and states it plainly.
5. **Ticking a recurring item.** Charting decision 6 makes an event and a checklist item the same
   entity, so a recurring item can be ticked. Does ticking one occurrence tick the series? This is
   the place where the one-model decision is under the most strain - if the answer is ugly, say so
   rather than forcing it.
6. **Alarms for a series.** Fed by ticket 03. Probably "schedule the next one, re-arm on fire", but
   confirm against what that ticket establishes about doze and reboot.
7. **The end of a series.** Forever, until a date, or after N occurrences.

### Constraints

- Charting decision 6 (one entity, optional times) is binding.
- Ticket 01's item shape must accommodate whatever is decided here. If this ticket's answer forces a
  change to 01's schema, that is expected and fine - reopen 01's schema, do not bolt a second table
  on beside it.
- Simple first (standing preference). A small hand-rolled rule set that covers bin day and the gym
  is a legitimate answer; "we implemented RRULE" is not automatically the better one.

### Watch for

This is where the map is most likely to discover it is bigger than it looked. If resolving this
reveals that recurrence genuinely warrants its own effort, say so - deferring it after an informed
look is a different act from deferring it blind, which is what Kevin declined at charting.

## Answer

Far cheaper than charted, because of one answer: **a recurring item cannot be ticked** (Kevin,
2026-08-07). That removes per-occurrence completion state entirely, and with it the "edit this one or
all of them" prompt that makes recurrence expensive. A repeat is an event you attend, not a chore you
complete.

### The rule

A small hand-rolled set. Explicitly NOT RFC 5545.

```
Daily(every: Int)
Weekly(every: Int, days: Set<DayOfWeek>)
MonthlyOnDate(every: Int, day: Int)
Yearly(month: Int, day: Int)

end: Never | OnDate(date) | AfterCount(n)
```

**Not supported, deliberately, and the UI must not pretend otherwise:** "last Friday of the month",
"every 3rd Tuesday", "every 6 weeks". Stored as discrete Room columns rather than an encoded blob,
so a repeat is inspectable in the schema and in a query.

Month-end is the one edge case that must be decided rather than discovered: **`MonthlyOnDate(31)` in
a 30-day month fires on the last day of that month, not the 1st of the next.** Same for
`Yearly(2, 29)` in a common year.

### Storage

**A stored rule; occurrences computed on read. Never materialised.** With no per-occurrence
completion state to keep, there is nothing an occurrence row would hold that the rule does not
already say. Computing a bounded window is cheap and cannot drift.

### Exceptions: skip only

A separate `list_item_skips` table (`itemId`, `skippedDate`, plus the usual sync columns). Moving a
single occurrence does not exist - skip it and add a one-off. This is the only per-occurrence state
in the design, and it is one row per skip, not one per occurrence.

### Alarms

**Re-arm on fire, never `setRepeating`** - forced by ticket 03's finding that `setRepeating` has been
inexact since API 19, `setInexactRepeating` cannot express weekdays, and no exact repeating API
exists.

The sharp edge ticket 03 handed over: **if the phone is off when an occurrence is due, the chain
stops rather than skips.** So boot recovery must not resume from the last fired occurrence - it must
**recompute the next occurrence forward from now** for every series. That single rule is what keeps a
powered-off night from silently ending a repeat forever.

### What does NOT arise

"Edit this one or all of them" never has to be asked. Editing a series edits the series; the only
per-occurrence action is skip. That is the whole payoff of the not-tickable answer.
