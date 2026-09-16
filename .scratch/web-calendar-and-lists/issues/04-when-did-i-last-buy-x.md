---
map: web-calendar-and-lists
ticket: "04"
title: "When did I last buy X, from tick history, saying only what a tick can say"
type: build
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-what-makes-two-toothpaste-lines-the-same-thing]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# "When did I last buy toothpaste"

**Kevin, 2026-09-16:** *"i tick off toothpaste today after my trip. > i ask the ai, hey when was
the last time i bought toothpaste > it looks back at when it was ticked."*

Ticket 03 resolved the matching rule. This builds the read and the voice tool.

## The data, and what it costs

Nothing new. `ChecklistTick` already carries `tickedAt` (the real instant of the tap, distinct from
`day`, which is the day it counts for). Ticks are never hard-deleted and a checklist delete does
not cascade. **No migration on either side.**

`tickedAt` is the right column to report, not `day`: Kevin asked when he BOUGHT it, and the tap
after the trip is closer to that than the day the tick was filed against.

## What to build

1. **A pure matcher** - normalised text in, ranked tick history out. Pure and unit-tested, in the
   shape `outstanding/Outstanding.kt` already uses on this codebase: the fetching is one file, the
   judgement is another, and the judgement is what gets tested.
2. **A controller read** that walks ticks joined to items, **through tombstones on both the item
   and the checklist** (ticket 03), and returns matches newest first with the checklist name.
3. **A voice tool.** Name it for what it does - it reads a tick history, and its description must
   not promise a purchase record.
4. **A hands path.** ADR 0035: a capability reachable only by voice is not finished. The natural
   home is the list surface - tapping an item shows when it was last ticked.

## The wording, and this is the part with teeth

A tick is evidence that Kevin tapped a line. **It is not evidence that he bought anything**, it
carries no price, and nothing reconciled it against anything. §4 rule 5 binds: what the source does
not state may not be asserted.

| Allowed | Forbidden |
|---|---|
| "You ticked toothpaste off Groceries on Sep 16" | "You bought toothpaste on Sep 16" |
| "Last ticked 12 days ago" | "You spend about $4 a month on toothpaste" |
| "I have no record of ticking toothpaste" | "You have never bought toothpaste" |

The third row matters most. **An absent tick is an absent RECORD, not an absent purchase** - Kevin
buys plenty of things that never touch a list, and the §1 empty-versus-unreadable distinction is
exactly this shape. The tool's failure result says what it did not find, never what did not happen.

`ai/AriaBrainHonestyClauseTest` guards the clause's presence, not its obedience, so the tool
DESCRIPTION is the lever: it must state in words that these are ticks, not purchases, and that the
app cannot see a purchase it was not told about.

## Verification

- `compileDebugKotlin`, full suite, totals from the JUnit XML under `app/build/test-results/`.
- Unit tests on the matcher: case and whitespace variants match; `"Colgate toothpaste"` does NOT
  match `"toothpaste"` (03's deliberate narrowness, pinned so a later "improvement" has to argue
  with a red test); a tick whose checklist is tombstoned IS returned; newest first.
- `python tools/voice_guide.py` exits zero - a new voice tool with no copy is a hard failure by
  design, so this step is not optional.
- A test that the tool description does not contain a purchase claim. Cheap, and it is the only
  automated grip on the §4 rule 5 exposure above.
- **On the phone**: tick a grocery line, ask the assistant when it was last ticked, and report what
  it actually said. Not claimable from a unit test (L11) - the model choosing to call the tool is
  the half no test covers, and the 2026-09-11 `read_calendar` bug was exactly a tool that worked
  and was never reached.
