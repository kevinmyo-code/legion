---
map: legion-shape
ticket: 01
title: "What is LEGION?"
type: grilling
status: resolved
status-detail: "2026-08-06, Kevin"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What is LEGION?

## Question

`CLAUDE.md` §1 calls LEGION "one voice assistant orchestrating aspects of life". The repo does not
look like that. Is the voice the product, or is the data?

## Facts, read not assumed (2026-08-06)

| Area | Source files | Test files |
|---|---|---|
| ledger | 22 | **17** |
| vehicle | 31 | 2 |
| pantry | 3 | 2 |
| service (voice) | 22 | **1** |

22 of the last 30 commits are ledger, statement parsers, or Drive sync serving the ledger.
`AriaBrain.assembleBase()` named no car at all until 2026-08-06. `memory/MEMORY.md`: **"Nobody has
asked the assistant a QUESTION. No voice tool call has ever run."**

## Resolution

**LEGION is a personal record of your life that you talk to.**

Kevin, verbatim: *"voice logging things like workouts and meals and expenses (photo logging) and
also bank statements and also obd data from cars. basically a personal directory that i feed to ai
agents with a voice orchestrator with a persona that i talk to."*

- The **record is the product**. Not the screens, not the voice.
- **Voice is how you write to it**, not only how you query it. "Log a workout" is the main event.
  This is a real change: voice write-tools exist today only for cars (`set_odometer`, `log_service`,
  `add_car_task`, `remember`).
- **Photos are the second way in** - receipts, meals.
- The **persona is the front door**; agents work behind it.

**Five domains: workouts, meals, expenses, bank statements, car data.** Two of them do not exist at
all. `pantry/` reads grocery receipts, which is shopping, not eating.

**Why the ledger swallowed everything, stated so it is not repeated:** of the five domains, bank
statements are the only one that arrives with its own built-in truth-check. A statement prints its
own total, so correctness is *provable*. That made it both the hardest and the only one where "done"
felt measurable. The other four have no such anchor - which is what ticket 02 exists to handle.

**Neither of the two options offered was right.** Voice is not decoration, and the screens are not
the product. The data is the product; voice is the pen and the reading lamp.
