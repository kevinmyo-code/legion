---
map: web-calendar-and-lists
ticket: "03"
title: "What makes two toothpaste lines the same thing"
type: decision
status: resolved
status-detail: >
  Resolved 2026-09-16 by Opus, carrying Kevin's standing "run everything with
  your taste" delegation into this map. Option A: normalised text match, read
  through tombstones, and the question is ASKED not asserted. Ticket 04 builds
  it. Overturnable cheaply - nothing is written, so a later product identity
  is additive.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# What makes two "toothpaste" lines the same thing

A `ChecklistTick` points at an `itemId`. That is enough to answer "did I do my bio plan today". It
is NOT enough to answer "when did I last buy toothpaste", because the toothpaste line Kevin ticks
in September may be a different `ChecklistItem` row than the one he ticked in March - a new list,
a retyped line, an edited word.

So the question has to match across item rows, and the only thing those rows share is the text a
human typed.

## Why this is a ticket and not a detail

**one-home ticket 04 killed exactly this mechanism.** `advisor/GoalChecklistSync.kt` found "its
own" rows by scanning `list_items` display text for the prefix `"Plan: "`. The resolution, which
is quoted in `Checklist.sourceKey`'s own field doc, says why it went: it *"could not survive a
user typing a line that happened to start the same way, could not record a tick history, and had
no identity a foreign key could point at."*

Re-introducing text matching without naming that ruling would be the same mistake with a nicer
face. This ticket names it, and argues the case is genuinely different.

## The options

**A. Normalised text match.** Lower-case, trim, collapse whitespace. `"Toothpaste"`,
`"toothpaste "`, `"TOOTHPASTE"` are one thing. `"Colgate toothpaste"` is not.

**B. A `pantry_products` master-data row.** A real identity every grocery line points at, with
autocomplete when adding a line. Correct, foreign-keyable, survives any rewording.

**C. Store the text ON the tick.** A tick records the item's text at tap time, so history survives
the item row being edited or removed.

## Resolution, 2026-09-16: A, and C is the ticket to watch

### A, and the reason it is not the "Plan: " hack again

The 04 ruling's objection was to a machine writer identifying **its own rows** by display text -
ownership inferred from a search term, where a user typing the same prefix silently joined the
machine's set and the machine then wrote to it. Three things are different here:

1. **Nothing writes.** This is a read-only question. The worst failure is an answer that misses a
   purchase or names the wrong one - not a row silently claimed and mutated.
2. **The text IS the thing being asked about.** Kevin's question is literally *"when did I last buy
   toothpaste"* - he is matching by name because the name is what he knows. An identity he never
   sees is not more truthful here, it is just indirect.
3. **It is falsifiable at a glance.** The answer names what it matched, so a wrong match is visible
   in the answer itself rather than buried in a sync decision.

Against B: a products table is a master-data layer, an autocomplete, and a migration, built before
there is any evidence the text match is insufficient. It also makes adding a grocery line heavier,
and a grocery list nobody uses records no purchases at all. **B is what A upgrades into if and
when a real miss is observed**, and because A writes nothing, that upgrade costs no data.

### What the match must do, precisely

- Lower-case, trim, collapse internal whitespace. Nothing cleverer - no stemming, no fuzzy
  distance, no synonyms. **A near-match that is wrong is worse than a miss**, because a miss says
  "I have no record" and a wrong match asserts a date that never happened.
- **Read THROUGH tombstones.** The tick's item and its checklist may both be soft-deleted - that is
  the expected state after ticket 02, not an edge case. A history read that filters
  `deleted_at IS NULL` on the parent returns nothing for exactly the lists Kevin cleared.
- Match against `ChecklistItem.text` across every checklist, not just ones named "Groceries". Kevin
  did not say the list has to be called Groceries and nothing should require it to be.

### C is real and is NOT built here

If an item's text is edited, every past tick against it retroactively changes meaning - "toothpaste"
ticked in March becomes "shampoo" ticked in March. That is **§4 rule 8's failure shape exactly**: a
record whose evidence lives in a mutable row somewhere else. Storing the text on the tick is the
fix, and it is a Room migration plus a server migration on both sides.

**Not built now because nothing is lost by waiting** - the tick rows being written today already
carry `tickedAt`, and a later migration can back-fill text from the item rows that still exist.
Building it now would put a migration in front of a feature that has never been used once. **If
Kevin edits a grocery line and the history goes wrong, that is the trigger**, and this paragraph is
the record that it was foreseen rather than missed.

## What this does not decide

The wording of the answer, and what a tick is allowed to claim. That is ticket 04, and it is the
half with the §7 exposure.
