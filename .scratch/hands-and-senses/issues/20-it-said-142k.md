---
map: hands-and-senses
ticket: "20"
title: "It said the Jeep was at 142k when the record says 227k"
type: task
status: open
status-detail: "Reported 2026-08-20, NOT explained. The deeper problem it named is fixed: memory_audit (Room v27) now records recalls and the lines the assistant speaks. The mileage claim itself remains unreproduced."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# It said the Jeep was at 142k when the record says 227k

## Question

Kevin, 2026-08-20: *"ai just said jeep was at 142k miles. its clearly not, the fleet tab says 227k"*
(227,621 mi).

### What was ruled out, by reading his real database off the phone

Pulled `legion_database` from the A25 and read it. **The data is correct and consistent everywhere:**

| Vehicle | baseline | trip | total |
|---|---|---|---|
| 1998 Jeep Cherokee | 227,494 | 117.5 | **227,612** |
| Ford F-150 | 161,470 | 220.9 | 161,691 |
| Mitsubishi Outlander | 73,577 | 12.6 | 73,590 |

- **No vehicle is near 142k**, so it did not report the wrong car.
- **142k appears nowhere in the database.** Every text column of every table was searched; the only
  matches were unix timestamps. The seven mileage-bearing `companion_memories` all say 227,4xx for
  the Cherokee, and both `service_records` say 227,3xx.
- **The tool chain returns the right number.** `list_vehicles` puts `odometer` on every vehicle from
  `VehicleController.mileageLabel`, which renders `"227,612 mi"` with its own estimate caveat
  attached as a string precisely so the model cannot restate an estimate as bare fact.
- **A km/mile mix-up does not explain it either.** 227,612 km would be 141,437 mi, close enough to
  "142k" to be worth checking, but nothing in the codebase converts distance - `setOdometer` takes
  miles, `tripMilesSinceBaseline` is miles, `mileageLabel` renders "mi".
- **The prompt already forbids this, explicitly and at file scope.** `AriaBrain`: *"NEVER state a
  fact about the driver's own record unless a tool call in THIS conversation returned it...
  figures, dates, car details."* That rule was added on 2026-08-18 after an invented dentist
  appointment - **this is a recurrence of an already-guarded failure class, not a missing rule.**

### The reproduction did not reproduce

Driving the same question through the debug hook
(`am broadcast -a com.kevin.legion.DEBUG_SAY --es text 'what is the mileage on the jeep'`) produced
a correct tool chain: `ask_fleet` dispatched, then the sub-agent's `investigate round 1: called
[list_vehicles]`. So on that run it asked, and it asked the right thing.

### The actual defect this exposes: nothing records what the assistant SAID

The reason this cannot be closed is that **there is no way to find out what happened.**

- `episodic_turns` holds **0 rows** - `MemoryConsolidator` distils and clears it, by design.
- `GeminiLiveSession` logs `Turn transcript: "..."` for **the DRIVER's** words only.
- `LiveEvent.Subtitle` carries the assistant's own words to the UI and **is never logged**.
- logcat had already rolled past the incident.

So a fabricated figure leaves **no trace anywhere**. The app can tell you what you said and what it
did, and not what it claimed. For a companion whose central risk is stating something plausible and
false, that is the wrong thing to be missing.

The work:

1. **Log the assistant's spoken text** (debug builds at minimum) where `LiveEvent.Subtitle` is
   handled. Cheap, and it makes this whole class diagnosable instead of anecdotal.
2. **Log tool results, not just `tool_dispatched`.** `MidnightEvents` records that `ask_fleet` was
   dispatched but not what came back, so "the tool returned the right number and the model ignored
   it" cannot be told apart from "the tool returned nothing".
3. Only once 1 and 2 exist can the real question be answered: did the model skip the tool, or get
   the right answer and say a different one?

**Do not add another prompt sentence as the fix.** The rule is already there, already file-scope,
already names its own prior incident. Adding a third phrasing of it without evidence about what
actually happened would be guessing at a fix for a bug nobody has observed the inside of.

## Update 2026-08-20 (late): the missing record now exists

The ticket's own conclusion - *"the actual defect this exposes: nothing records what the assistant
SAID"* - was acted on the same evening. `memory_audit` (Room v27) records memory writes, deletes,
recalls (the query AND every memory handed to the model) and the assistant's spoken lines.

**The mileage claim itself is still unexplained**, and this ticket stays open for it. What changed is
that a recurrence can now be read instead of guessed at: pull the database and look at the `spoken`
row next to the `recalled` rows around it.

**One honest limit.** The spoken text only exists when the API returns `outputTranscription`, and a
turn was observed that evening speaking audio with none - it missed the greeting Kevin had just
approved. So an absent `spoken` row does NOT prove the assistant said nothing, and treating it that
way would be the same "absence of evidence as evidence" mistake this map keeps running into.
