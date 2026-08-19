---
map: fleet-maintenance
ticket: 02
title: What a 1998 Jeep Cherokee actually needs
type: research
status: resolved
status-detail: 2026-08-15
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What a 1998 Jeep Cherokee actually needs

## Question

The schedule on Kevin's phone was written by an LLM answering a prompt that asks for the **severe /
heavy-duty** schedule (`VehicleController.kt:740-742`). That is very likely where the 3,000-mile oil
interval came from - severe-service schedules for late-90s Chrysler products do call for 3,000.

Kevin wants 7,500. Before ticket 06 decides whether the seed prompt should ask for severe at all,
somebody has to know what the factory actually published.

**Find the real numbers, from primary or near-primary sources.**

## What to find

1. **The factory maintenance schedule for a 1998 Jeep Cherokee (XJ), 4.0L I6.** Chrysler published
   two: "Schedule A" (normal) and "Schedule B" (severe). Get **both**, item by item, with mileage
   and time intervals for each.
2. **What actually lands in Schedule B**, and what triggers it. Short trips, dusty roads, trailer
   towing, extended idling, cold climates. A daily driver in normal use is usually Schedule A;
   knowing the trigger list is what lets Kevin choose.
3. **The oil interval specifically.** Factory figure under each schedule, and the relevant caveat:
   1998-era intervals assume conventional oil and pre-date modern oil life monitors. Note what the
   published number is, and note separately (clearly labelled as a modern practice, not a factory
   figure) what a synthetic-oil interval on a 4.0L typically runs.
4. **Items the LLM's 10-entry canonical list would have missed.** `SERVICE_KEYWORDS`
   (`VehicleController.kt:71-82`) has ten canonical names. The XJ schedule almost certainly names
   things outside it - transfer case fluid, front and rear differential fluid, the NP231/NP242
   transfer case specifically, cooling system service, PCV, serpentine belt, exhaust manifold
   inspection. **List everything the factory schedule names**, so ticket 05's hand-add affordance is
   designed against the real vocabulary rather than a ten-item guess.
5. **Age-driven items a mileage schedule will not catch.** A 28-year-old vehicle has rubber, fluid
   and fastener concerns that no 1998 schedule anticipated because it assumed a 10-year life. Brake
   fluid hygroscopy, coolant, fuel and brake hoses, the rear main, the notorious XJ rear axle seal.
   Time-only intervals, explicitly.
6. **Capacities and specs where a schedule item implies one** - oil capacity and grade, coolant
   type (this matters on a 1998; HOAT vs green is a real trap), ATF spec for the AW4, transfer case
   fluid, axle fluid. Useful directly, and it tells ticket 05 whether a maintenance item wants a
   notes field.

## Sources

Primary where possible: the factory service manual, the owner's manual maintenance schedule, Jeep /
FCA technical publications. Well-regarded XJ community references are acceptable for the age-driven
items in (5) provided they are named as such and not presented as factory figures.

**Distinguish factory-published figures from community practice in every single entry.** Ticket 06
is about labelling guesses honestly; this ticket cannot be the place a guess gets laundered into a
fact.

## Output

`.scratch/fleet-maintenance/research/1998-xj-schedule.md`, on a throwaway `research/` branch.
A table per schedule (A and B), a table of age-driven items, a capacities table, and a clearly
separated "community practice, not factory" section. Cite every row.

---

## Answer (2026-08-15)

Full findings: [`research/1998-xj-schedule.md`](../research/1998-xj-schedule.md), ~63KB, 9 sections,
44 `sourced` rows and 8 `reasoned`, plus an explicit "not determined" table where nothing was
substituted for a fact that could not be found.

### The headline: 7,500 is the factory number

| Schedule | Oil interval |
|---|---|
| **A (normal service)** | **7,500 miles or 6 months, whichever first** |
| **B (severe / heavy-duty)** | **3,000 miles - and NO time interval at all** |

**Kevin's 7,500 is not a preference. It is exactly what Chrysler published for normal service.**
The 3,000 on his phone is the factory *severe* figure, correctly retrieved for a question nobody
meant to ask: `lookupServiceIntervals` (`VehicleController.kt:740-742`) hardcodes the word SEVERE
into its prompt. **The LLM was not wrong. The prompt was.**

Fixing the prompt to ask for normal service yields 7,500 with no further intervention. Carry over
one thing from Schedule A that severe does not have: **the 6-month floor.**

### Five findings that change other tickets

1. **Schedule B publishes no time intervals whatsoever** - a pure 3,000-mile ladder from 3k to
   120k, verified against two independent printings. **Ticket 07's hand-add form must tolerate a
   mileage-only item**, and ticket 06's due rule cannot assume both axes are ever present.
2. **Brake fluid is not in the factory schedule at all.** No flush, no change, at any mileage, on
   either schedule - it appears only as a monthly master-cylinder *level check*. So the app's
   `Brake Fluid` canonical name has **no factory figure behind it on this vehicle**, and the
   seeded `Brake Fluid Flush` item (24 months, no mileage) was invented by the LLM rather than
   retrieved. Direct input to ticket 06.
3. **The XJ has no cabin air filter.** Zero hits across the full 1997 and 2001 FSM text. LEGION
   has seeded a `Cabin Air Filter` item onto other cars in Kevin's roster; if one ever lands on the
   Jeep it is a fabrication, not a lookup miss.
4. **The XJ never used HOAT.** The 2001 FSM - final model year - still specifies conventional green
   ethylene glycol with Alugard 340-2. Zero hits for HOAT / MS-9769 / G-05 in any year. **The trap
   runs the opposite way from the folklore**, and my own charting note on ticket 02 repeated the
   folklore. Corrected here.
5. **Cold weather is not a gasoline Schedule B trigger** - it is on the *diesel* schedule. Anyone
   reciting the Chrysler severe-service list from memory is likely reciting the wrong one.

### Vocabulary: 26 distinct factory strings

15 scheduled service items (A has 14; B adds a front/rear axle drain-and-refill and a separately
worded air cleaner inspection) plus 11 unscheduled inspection items. **`SERVICE_KEYWORDS` has
ten entries** (`VehicleController.kt:71-82`). The factory schedule for one 28-year-old vehicle
already names more than twice that, so ticket 07's hand-add flow cannot be built against the
canonical ten - which was the test this ticket existed to run, and the ten fails it.

### Caveats not papered over

- **No 1998-dated Group 0 exists in any free archive** (Chrysler P/N 81-370-8146, print-only). The
  schedule is transcribed from the 1997 and 2001 FSMs, which are identical, with the 1999 FSM
  explicitly naming the 1997 manual as the gasoline authority. Tagged `reasoned` in the research
  file and called out there as its largest caveat. Axle specs and 1998 part numbers **are**
  confirmed by genuinely 1998-dated documents.
- Two FSM self-contradictions are **documented rather than silently reconciled**: a conflicting
  capacity table in the bound diesel supplement, and the AW-4 chapter naming ATF+3 in a subsection
  that contradicts its own fluid heading.
- One community claim is **actively contradicted by the FSM** (forums say the 4.0L timing chain has
  no tensioner; the FSM has a replacement procedure for one), flagged in the research file's section 7.4.

### Assumptions ledger

- `sourced`: the A/B schedules item by item, the trigger list, capacities, axle specs, the absence
  of brake fluid and of a cabin air filter, the coolant specification. 44 rows, each with a URL.
- `reasoned`: that the 1997/2001 transcription is valid for 1998. Load-bearing for every interval
  above and named as such.
- Not verified by me directly: I am relaying a research subagent's report. The findings are
  internally consistent and the caveats are the kind an agent papering over gaps does not write,
  but **I have not personally read the source FSMs.**
