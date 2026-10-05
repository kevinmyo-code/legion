---
status: accepted
decided: 2026-10-04
decided-by: Kevin
amended: 2026-10-05
supersedes: []
source: "[[decisions#2026-10-04 - The household bought log, and the assistant on the web]]"
tags: [adr]
---

# 55. A tick on the Groceries list is a purchase

## Standing

**A tick on the household's Groceries list records a purchase, by whoever ticked it, on the day it
was ticked.** It creates an entry in the bought log, and every surface may say "bought" about that
entry. **Every other list is unchanged: a tick there is still a tap (ADR 0049)**, and its surfaces
still say "ticked".

This narrows ADR 0049 for one list. It does not replace it.

## Context

Kevin, 2026-10-04: *"wife wants to be able to keep track when we bought certain household items.
like shampoo etc. ... either from groceries checklist, or by her manually logging"*. Asked whether a
Groceries tick should log a purchase by itself, ask each time, or never, he picked by itself.

ADR 0049's rule exists because the question people ask ("when did I last BUY toothpaste") is not
the question a tick answers ("when did I last TICK it"). On the Groceries list the household has now
said those are the same act: the list is the shopping list, and a line comes off it when the item is
in the cart. That is a ruling about what this list means, made by the people who use it. It is not
an inference the software makes.

## Consequences

- The purchase claim lives in the bought log entry the tick creates (`.scratch/purchase-log/`),
  never in the tick itself. Tick-history surfaces (`get_last_ticked`, "last ticked") keep ADR 0049's
  wording on every list, Groceries included; "bought" comes from the log.
- The hook runs on the engine, once, so the phone and the web cannot disagree (ADR 0044).
- An untick on the same day removes the entry it created. A later untick does not: clearing an old
  tick is not evidence the purchase did not happen.
- "Who" is the member whose request created the tick. Ticks themselves still record no user.
- The household's Groceries list is its built-in Groceries list (`system_key`), which cannot be
  deleted, renamed, archived or made private. (AMENDED 2026-10-05: ticket 01 had chosen the oldest
  shared, non-deleted list NAMED Groceries; on the live engine no such list was live, so the hook fired
  on nothing. A list a person names Groceries is now just a list.)
