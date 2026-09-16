---
map: web-calendar-and-lists
title: "The web calendar, deleting a finished list, and a tick as a purchase record"
charted: 2026-09-16
tags: [map]
---

# The web calendar, deleting a finished list, and a tick as a purchase record

**Kevin, 2026-09-16:** *"django frontend > i want the calendar widget, much like my android
landing page. groceries and other lists, if i tick everything, i should be able to delete the
list. also let me delete list from the home screen. instead of pantry ingestion, when i tick off
an item from grocery, that will be tracked. etc i tick off toothpaste today after my trip. > i
ask the ai, hey when was the last time i bought toothpaste > it looks back at when it was
ticked."*

Three asks. The first two are ordinary build. The third looked like it needed a new table and a
migration, and it does not - which is the finding this map exists to record.

## The finding: the history already exists, and deleting the list does not destroy it

Two facts, both already true in the code, both load-bearing for ask 3:

1. **`ChecklistTick.tickedAt` is already stored, and is already a different fact from `day`.**
   `day` is the local epoch day the tick COUNTS FOR; `tickedAt` is the wall-clock instant of the
   actual tap. That distinction was built for retroactive ticks - and it is exactly what "when did
   I last buy toothpaste" needs. **No schema change, no migration, on either side.**
2. **Deleting a checklist is a soft delete and it deliberately does NOT cascade.**
   `ChecklistDetailView.delete` stamps `deleted_at` on the checklist row alone, and says why in a
   comment quoting `ChecklistController.deleteChecklist`: *a checklist's history is never rewritten
   by deleting the checklist any more than by deleting one of its items.*

So ask 2 and ask 3 do not collide. **Ticking out a grocery list and then deleting it keeps every
tick**, which is the one thing that would have made the feature a lie. The architecture already
decided this; nothing here needs to argue it.

## The fork that IS real: what makes two "toothpaste" lines the same thing

A tick points at an `itemId`. Delete the Groceries list in March, start a new one in September,
retype "toothpaste" - that is a different `ChecklistItem` row, new id, no relationship to the old
one. Answering "when did I last buy toothpaste" means matching ACROSS item rows, and the only
thing those rows share is their display text.

**Matching on display text is the hack one-home ticket 04 killed** (`ITEM_PREFIX = "Plan: "`,
which scanned `list_items` display text to find "its own" rows). That ruling is why this is a
ticket and not an implementation detail. Ticket 03.

## What a tick may and may not be allowed to say

Kevin said *"instead of pantry ingestion"*. **This map does not retire pantry ingestion, and the
distinction is not pedantry:**

| | A gated receipt | A tick |
|---|---|---|
| Says | what was bought, what it cost, verified against a printed total | that Kevin tapped a line |
| Provenance | `DETERMINISTIC` / `LLM_RECONCILED` (§4) | self-report, nothing to reconcile against |
| Can answer | "what did I spend on groceries" | "when did I last tick toothpaste" |

A tick is not evidence of a purchase; it is evidence of a tap. It answers WHEN and nothing else.
Every surface built on this map says so in words - **"you ticked toothpaste off Groceries on
Sep 16", never "you bought toothpaste on Sep 16"** (§7's outcome-verb rule: the app did not
observe a purchase). Ticket 04 owns that wording and its test.

This is not an ingestion path, so §4's reconciliation gate has no purchase here - there is no
document and no stated total. §4 rule 5 does bind: nothing the tick does not state may be
asserted, and a price is something it does not state.

## Tickets

| # | Type | What |
|---|---|---|
| 01 | build | The calendar widget on the web home, month grid plus day view |
| 02 | build | Delete a list, from Lists and from Home, and offer it when everything is ticked |
| 03 | decision | What makes two "toothpaste" lines the same thing |
| 04 | build | "When did I last buy X", from tick history, saying only what a tick can say |

## Not in scope

Retiring pantry receipt ingestion. Ticks cannot carry money and the ledger side of pantry is
gated; that would be a separate ruling with §4 consequences, and nothing in Kevin's message asks
for the spend half to go away.
