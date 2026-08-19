---
map: notes-lists-calendar
ticket: 06
title: "What does the photo-to-draft flow look like?"
type: prototype
status: closed
status-detail: out of scope
blockers: ["01", "02"]
blocked-by: ["[[01-entity-model-and-cartask-migration]]", "[[02-can-it-read-the-real-handwriting]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What does the photo-to-draft flow look like?

Closed 2026-08-07 as OUT OF SCOPE, not resolved. Kevin cut photo ingestion for lists mid-effort.
Charting decision 3, the human-as-reconciliation-gate narrowing this ticket was to have worded, is
WITHDRAWN rather than settled. **Nothing on this map now narrows CLAUDE.md §4**, and no future
feature may cite this ticket as precedent for skipping a gate.

## Question

Charting decision 3 made the human the reconciliation gate: a photographed list lands as a draft and
nothing is written until Kevin confirms. Decide what that actually looks like.

### What must be decided

1. **The review screen.** What Kevin sees between taking the photo and the list existing. How lines
   are presented, how a wrong one is fixed, how a missed one is added, how an invented one is
   dropped. Ticket 02's findings on the real error *shape* drive this directly - a model that
   garbles words needs fast inline editing; one that misses whole lines needs an easy add.
2. **What happens to a heading.** A paper list usually has a title. Does it become the list name?
3. **Non-item marks.** Crossings-out, arrows, doodles, a phone number in the margin. What the
   extraction is told to do with them, and what the review screen does with what comes back.
4. **Already-ticked items.** A paper checklist often has some boxes already ticked. Does that carry
   through as `done`?
5. **Abandoning.** Kevin takes a bad photo. What happens to it. Pantry's precedent: a quarantined
   receipt keeps its photo so it can be retried without re-shooting. Decide whether that applies.
6. **Whether the source photo is kept.** Currently in the map's fog; if this ticket settles it,
   graduate it out of the fog rather than leaving it there.

### The wording that must not become a precedent

Charting decision 3 is a genuine narrowing of CLAUDE.md §4, and it needs writing carefully enough
that the next feature cannot cite it as "notes did not need a gate, so neither do we."

The distinction that does the work is **blast radius**: a misread checklist line is visible,
correctable, and poisons nothing downstream, whereas a misread receipt total silently becomes a fact
in a spend figure Kevin later trusts. It is NOT that notes are exempt from §4. Whatever wording this
ticket produces is what goes into `memory/library/decisions.md` and, if it changes a rule, into
CLAUDE.md in the same commit.

### Constraints

- CLAUDE.md §4 rule 6: a check satisfiable by an empty or partial extraction is not a gate. An empty
  draft must be visibly empty, not silently accepted.
- Everything here is REPORTED tier and must never read as PROVEN.
