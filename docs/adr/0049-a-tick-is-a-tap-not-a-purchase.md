---
status: accepted
decided: 2026-09-16
decided-by: Opus, on Kevin's standing delegated taste
supersedes: []
source: "[[decisions#2026-09-16 - A checklist tick records a tap, and answers WHEN and nothing else]]"
tags: [adr]
---

# 49. A tick is a tap, not a purchase

## Standing

**A `ChecklistTick` is evidence that someone tapped a line on a given day. It is not evidence that
anything was bought, done, consumed, or delivered.** Every surface built on tick history says
"ticked", never "bought" or any other outcome verb, and reports an absent tick as an absent
RECORD, never as an absent event.

This binds voice tool descriptions, tool result messages, UI labels, advisor lines, and any report
or figure computed from ticks.

## Context

Kevin, 2026-09-16: *"instead of pantry ingestion, when i tick off an item from grocery, that will
be tracked. etc i tick off toothpaste today after my trip. > i ask the ai, hey when was the last
time i bought toothpaste > it looks back at when it was ticked."*

The feature is genuinely useful and cost no migration: `ChecklistTick.tickedAt` already stored the
instant of the tap as a column distinct from the day the tick counts for, and a checklist delete is
soft and non-cascading, so history outlives the list. Verified against the live engine on the day
of the decision.

The risk is not in the storage. It is that the question a person asks ("when did I last BUY
toothpaste") is not the question the data can answer ("when did I last TICK a line reading
toothpaste"), and the gap between those two sentences is where a false assertion lives.

## Why this is not the reconciliation gate

CLAUDE.md §4's gate applies to INGESTION - a document with a stated total, reconciled exactly or
quarantined. A tick is not an ingestion path: there is no document, no printed total, and nothing
to reconcile against. The gate has no purchase here and inventing one would be theatre.

**§4 rule 5 does bind, and it is the whole of this ADR**: anything the source does not state cannot
be asserted. A tick states a tap and a time. It does not state a price, a quantity, a merchant, or
that a transaction occurred at all.

The distinction, stated once so it is not re-litigated per feature:

| | A gated receipt | A tick |
|---|---|---|
| States | what was bought, what it cost, verified against a printed total | that a line was tapped, and when |
| Provenance | `DETERMINISTIC` or `LLM_RECONCILED` | self-report, nothing to reconcile |
| May answer | "what did I spend on groceries" | "when did I last tick toothpaste" |

**This ADR does not retire pantry receipt ingestion**, and nothing in it licenses replacing a gated
money path with a tick. They answer different questions and both remain.

## The three sentences

1. A match: **"You ticked toothpaste off Groceries on Sep 16."** Never "you bought".
2. A near miss: the matcher is deliberately narrow (normalised text only), so `"Colgate
   toothpaste"` does not answer a `"toothpaste"` question. **A wrong match asserts a date that
   never happened; a miss merely says nothing is recorded.**
3. No match: **"I have no record of ticking toothpaste."** Never "you have never bought
   toothpaste". This is CLAUDE.md §1's empty-versus-unreadable distinction: plenty is bought that
   never touches a list, and an absent row is silence, not a negative fact.

## Consequences

- The tool description is the only real lever, because nothing inspects generated speech. It must
  state in words that these are ticks rather than purchases. A test asserts the description carries
  no purchase claim, which is the sole automated grip on a prompt-level rule.
- Tick history must be read THROUGH tombstones on both the item and the checklist. After a list is
  deleted that is the expected state, not an edge case, and filtering on `deleted_at` would return
  nothing for exactly the lists a user has cleared.
- A future surface that wants to assert a purchase needs a different source, not a louder tick.

## Known weakness, with a named trigger

A tick points at an item row whose text is mutable. Edit a grocery line and every past tick against
it retroactively changes meaning - "toothpaste" ticked in March becomes "shampoo" ticked in March.
That is §4 rule 8's failure shape: a record whose evidence lives in a mutable row elsewhere.

The fix is to store the item's text on the tick, and it is a migration on both sides. **Deliberately
not built**, because the tick rows being written today already carry `tickedAt` and a later
migration can back-fill text from the item rows that still exist, so nothing is lost by waiting -
and building it now would put a migration in front of a feature nobody has used once. **If an item's
text is edited and history goes wrong, that is the trigger.** This paragraph is the record that it
was foreseen rather than missed.

Ticket: `.scratch/web-calendar-and-lists/issues/03-what-makes-two-toothpaste-lines-the-same-thing.md`.
