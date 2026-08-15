# What a 1998 Jeep Cherokee actually needs

Type: research
Status: claimed (research subagent fired at charting, 2026-08-15)

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
