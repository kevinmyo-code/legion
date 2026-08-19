# What a 1998 Jeep Cherokee (XJ) 4.0L actually needs

Research output for `.scratch/fleet-maintenance/issues/02-what-a-1998-xj-actually-needs.md`.
Compiled 2026-08-15.

**Read this first.** Everything below is split into three tiers and they are never mixed:

| Tier | Meaning |
|---|---|
| **FACTORY** | Printed in a Chrysler/DaimlerChrysler XJ Factory Service Manual. Verbatim item wording, source URL on every row. |
| **COMMUNITY / INDUSTRY** | Owner practice, forum consensus, or a non-Jeep manufacturer's guidance. Not a Jeep figure. Confined to §6 and §7. |
| **REASONED** | My inference from the sources, not printed anywhere. Called out inline and re-tagged in the ledger at the end. |

---

## 0. Provenance, and the one honest caveat

A **1998-dated** XJ Factory Service Manual Group 0 (*Lubrication and Maintenance*) could not be
obtained. What was obtained, and read in full:

| Source | What it gave | URL |
|---|---|---|
| **1997 XJ FSM**, Group 0 pp. 0-1 to 0-8 | The complete gasoline-engine Schedule "A" and Schedule "B", the Schedule B trigger list, the fluid-capacity table, and all fluid specs | https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages |
| **2001 XJ FSM**, Group 0 | The same two schedules, transcribed independently | https://archive.org/details/jeepcherokeexjfactoryservicemanual2001ocr3663pages |
| **1999 XJ FSM**, Group 0 | Diesel-only schedule; explicitly says *"Refer to the 1997 XJ Service Manual for gasoline engine and non-engine related Maintenance Schedules"* | https://archive.org/details/jeepcherokeexjfactoryservicemanual1999ocr1948pages |
| **1998 XJ FSM (Spanish, partial)** | Genuinely 1998-dated. Confirms axle capacities, gear oil spec and the Trac-Lok additive verbatim (§6.2). Its battery chapter points at a "grupo 0 - Lubricación y mantenimiento" that is not among the published sections | https://manuals.opinautos.com/published/Jeep-Cherokee_1998_en__manual_de_taller_c43bf74487.pdf · https://manuals.opinautos.com/published/Jeep-Cherokee_1998_ES__manual_de_taller_diferencial_sistema_transmision_83e7cb8f32.pdf |
| **1998 XJ Parts Manual** | Genuinely 1998-dated Mopar publication. Confirms the 1998 spark plug, oil filter, air filter and AW4 transmission code (§6.2) | https://archive.org/details/1998jeepcherokeexjpartsmanualocr437pages |

**Why no 1998 Group 0.** The 1998 Cherokee service manual is Chrysler P/N **81-370-8146** and is
print-only; it is in no free archive. The Spanish 1998 FSM published online omits Group 0 entirely.

**The 1997 and 2001 schedules are item-for-item and mile-for-mile identical.** 1998 is bracketed on
both sides by two independent printings that agree, and the 1999 manual points *back* at the 1997
one as the authority for gasoline XJs. The Group 0 **fluid capacity tables** are likewise identical
line for line across 1997, 1999 and 2001. `reasoned`: the 1998 schedule is the same schedule. This
is a strong inference, not a 1998 document — and it is the single largest caveat in this file.

Everything in §1-§5 is quoted from those FSMs.

---

## 1. Which schedule, and what puts you on B

**FACTORY**, 1997 XJ FSM Group 0 p. 0-4 / 2001 XJ FSM Group 0 p. 0-4.

> "There are two maintenance schedules that show proper service for the Cherokee. First is
> Schedule "A". It lists all the scheduled maintenance to be performed under "normal" operating
> conditions. Second is Schedule "B". It is a schedule for vehicles that are operated under these
> conditions:"

The Schedule B trigger list, complete and verbatim (seven items):

| # | Trigger (verbatim) |
|---|---|
| 1 | Frequent short trips driving less than 5 miles (8 km) |
| 2 | Frequent driving in dusty conditions |
| 3 | Frequent trailer towing |
| 4 | Extensive idling |
| 5 | More than 50% of driving is at sustained high speeds during hot weather, above 90°F (32°C) |
| 6 | Off-road driving |
| 7 | Desert operation |

> "Use the schedule that best describes the driving conditions. Where time and mileage are listed,
> follow the interval that occurs first."

Source: https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages (Group 0,
"MAINTENANCE SCHEDULES", p. 0-4); identical text at
https://archive.org/details/jeepcherokeexjfactoryservicemanual2001ocr3663pages (p. 0-4).

**Three things worth flagging for ticket 06:**

1. **The trigger list is a list of *frequent* or *dominant* conditions**, not a list of things that
   have ever happened to the vehicle. "Frequent short trips", "more than 50% of driving". A daily
   driver doing normal mixed-use mileage is a **Schedule A** vehicle by the factory's own wording.
   `reasoned`.
2. **Cold weather is NOT a Schedule B trigger on the gasoline XJ.** The diesel XJ schedule does list
   "Day and night temperatures are below freezing" and "Stop and go driving"; the gasoline schedule
   does not. Anyone reciting the Chrysler severe list from memory is likely reciting the diesel one.
   Source: 1999 XJ FSM Group 0 p. 0-2 (diesel), same URL as above.
3. **A separate, narrower trigger exists inside Schedule B**, footnote `‡`, and it gates the axle
   service only. Verbatim: *"Off-highway operation, trailer towing, taxi, limousine, bus, snow
   plowing, or other types of commercial service or prolonged operation with heavy loading,
   especially in hot weather, require front and rear axle service indicated with a ‡ in Schedule
   "B". Perform these services if the vehicle is usually operated under these conditions."*

---

## 2. Not tied to either schedule: the inspection list

**FACTORY**, 1997/2001 XJ FSM Group 0 p. 0-4, headed "UNSCHEDULED INSPECTION" (2001) / untitled
(1997). These apply regardless of which schedule you follow.

| Trigger | Item (verbatim) | Source |
|---|---|---|
| At each stop for fuel | Check engine oil level, add as required | 1997 FSM p. 0-4 |
| At each stop for fuel | Check windshield washer solvent and add if required | 1997 FSM p. 0-4 |
| Once a month | Check tire pressure and look for unusual wear or damage | 1997 FSM p. 0-4 |
| Once a month | Inspect battery and clean and tighten terminals as required. Check electrolyte level and add water as needed | 1997 FSM p. 0-4 |
| Once a month | Check fluid levels of coolant reservoir, power steering, brake master cylinder, and transmission and add as needed | 1997 FSM p. 0-4 |
| Once a month | Check all lights and all other electrical items for correct operation | 1997 FSM p. 0-4 |
| At each oil change | Inspect exhaust system | 1997 FSM p. 0-4 |
| At each oil change | Inspect brake hoses | 1997 FSM p. 0-4 |
| At each oil change | Rotate the tires at each oil change interval shown on Schedule "A" (7,500 miles) or every other interval shown on Schedule "B" (6,000 miles) | 1997 FSM p. 0-4 |
| At each oil change | Check coolant level, hoses, and clamps | 1997 FSM p. 0-4 |
| After off-road operation | The underside of the vehicle should be thoroughly inspected. Examine threaded fasteners for looseness | 1997 FSM p. 0-4 |

URL for all rows: https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages

**Tire rotation is here, not in either schedule.** It rides on the oil change: 7,500 mi on A,
12,000 mi on B (every *other* 6,000-mile oil change). Note that this makes the app's canonical
"Tire Rotation" a Schedule-A-7,500 item, not a 5,000-mile item.

---

## 3. Schedule "A" — normal service. FACTORY.

Source for every row: 1997 XJ FSM Group 0 pp. 0-4 to 0-6,
https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages
— identical at 2001 XJ FSM Group 0 pp. 0-4 to 0-6,
https://archive.org/details/jeepcherokeexjfactoryservicemanual2001ocr3663pages

Schedule A is printed as a mileage ladder in 7,500-mile steps, each with a paired month figure.
Collapsed to intervals:

| Item (verbatim FSM wording) | Mileage interval | Time interval | First due | Notes |
|---|---|---|---|---|
| Change engine oil | 7,500 mi | 6 months | 7,500 mi / 6 mo | Every rung of the ladder |
| Replace engine oil filter | 7,500 mi | 6 months | 7,500 mi / 6 mo | Always paired with the oil |
| Lubricate steering linkage | 7,500 mi **(4x4 only)** at 7.5k/22.5k/37.5k/52.5k…; 15,000 mi (all) at 15k/30k/45k… | 6 mo (4x4) / 12 mo | 7,500 mi | 4x4 gets it every rung; 2WD every other rung |
| Lubricate steering and suspension ball joints | 15,000 mi | 12 months | 15,000 mi / 12 mo | |
| Inspect brake linings | 22,500 mi | 18 months | 22,500 mi / 18 mo | 22.5k, 45k, 67.5k, 90k, 112.5k |
| Replace air cleaner element | 30,000 mi | 24 months | 30,000 mi / 24 mo | Printed "Replace engine air cleaner element" from 60k on |
| Replace spark plugs | 30,000 mi | 24 months | 30,000 mi / 24 mo | |
| Inspect drive belt, adjust tension as necessary | 30,000 mi | 24 months | 30,000 mi / 24 mo | |
| Drain and refill automatic transmission fluid | 30,000 mi | 24 months | 30,000 mi / 24 mo | |
| Drain and refill transfer case fluid | 30,000 mi | 24 months | 30,000 mi / 24 mo | |
| Drain and refill manual transmission fluid | 37,500 mi | 30 months | 37,500 mi / 30 mo | 37.5k, 75k, 112.5k. N/A on an AW4 car |
| Flush and replace engine coolant | 30,000 mi thereafter | **36 months** first, then 24 months | **36 months regardless of mileage**, or 52,500 mi if not done at 36 mo | See §3.1 |
| Replace ignition cables | 60,000 mi | 48 months | 60,000 mi / 48 mo | 60k and 120k only |

**Fourteen distinct named items** across Schedule A (13 in the table, counting "Lubricate steering
linkage" once).

### 3.1 The coolant interval, stated two different ways in the same manual

**FACTORY**, but the FSM is not self-consistent, so both figures are given:

- **Group 0, Schedule A, 45,000 mi row:** *"Flush and replace engine coolant at 36 months,
  regardless of mileage."* Then 52,500 mi row: *"Flush and replace engine coolant if not done at 36
  months."* Then from 75,000 mi on: *"Flush and replace engine coolant if it has been 30,000 miles
  (48 000 km) or 24 months since last change."*
- **Group 7, Cooling System, service procedures:** *"It is recommended that the cooling system be
  drained and flushed at 84,000 kilometers (52,500 miles), or 3 years, whichever occurs first. Then
  every two years, or 48,000 kilometers (30,000 miles), whichever occurs first."*

Both resolve to the same practical rule: **first flush at 3 years or 52,500 miles, whichever comes
first; every 2 years or 30,000 miles after that.** Source:
https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages (Group 0 p. 0-5,
Group 7 "ADDING ADDITIONAL COOLANT—ROUTINE" preamble).

---

## 4. Schedule "B" — severe service. FACTORY.

Source for every row: 1997 XJ FSM Group 0 pp. 0-6 to 0-8,
https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages
— identical at 2001 XJ FSM Group 0 pp. 0-6 to 0-8,
https://archive.org/details/jeepcherokeexjfactoryservicemanual2001ocr3663pages

**Schedule B publishes NO time intervals.** It is a pure 3,000-mile ladder from 3,000 to 120,000.
Every month figure in Schedule A is absent from Schedule B. This is a real finding, not an omission
in my transcription — verified against both the 1997 and the 2001 printing.

| Item (verbatim FSM wording) | Mileage interval | Time interval | First due | Notes |
|---|---|---|---|---|
| Change engine oil | **3,000 mi** | **none published** | 3,000 mi | Every rung |
| Replace engine oil filter | 3,000 mi | none published | 3,000 mi | |
| Lubricate steering linkage | 3,000 mi | none published | 3,000 mi | Every rung, 4x4 and 2WD alike |
| Lubricate steering and suspension ball joints | 6,000 mi | none published | 6,000 mi | |
| Drain and refill automatic transmission fluid | 12,000 mi | none published | 12,000 mi | 12k, 24k, 36k, 48k, 60k, 72k, 84k, 96k, 108k |
| Inspect brake linings | 12,000 mi | none published | 12,000 mi | Same rungs as the ATF |
| **Drain and refill front and rear axles ‡** | 12,000 mi | none published | 12,000 mi | **Conditional** — only under the `‡` trigger list, see §1 note 3 |
| Inspect engine air cleaner element, replace as necessary | 30,000 mi, offset | none published | 15,000 mi | 15k, 45k, 75k, 105k — an *inspect*, distinct from the *replace* below |
| Replace engine air cleaner element | 30,000 mi | none published | 30,000 mi | 30k, 60k, 90k, 120k |
| Replace spark plugs | 30,000 mi | none published | 30,000 mi | |
| Inspect drive belt, adjust tension as necessary | 30,000 mi | none published | 30,000 mi | |
| Drain and refill transfer case fluid | 30,000 mi | none published | 30,000 mi | |
| Drain and refill manual transmission fluid | 18,000 mi | none published | 18,000 mi | 18k, 36k, 54k, 72k, 90k, 108k. N/A on an AW4 car |
| Flush and replace engine coolant | 30,000 mi thereafter | none published | **48,000 mi** | Then "if it has been 30,000 miles since last change" (81k, 111k) |
| Replace ignition cables | 60,000 mi | none published | 60,000 mi | 60k and 120k only |

**Fifteen distinct named items** across Schedule B — Schedule A's fourteen, plus "Drain and refill
front and rear axles", plus the extra "Inspect engine air cleaner element, replace as necessary"
wording, minus nothing.

**Where the LLM's 3,000-mile figure came from.** Schedule B, first rung. `VehicleController.kt:740-742`
asks for the severe/heavy-duty schedule, and the severe schedule for a 1998 XJ genuinely is 3,000
miles. The prompt got a correct answer to the wrong question.

---

## 5. Every item the factory schedule names — the full vocabulary

This is the load-bearing list for ticket 05. **Twenty-six** distinct factory strings: 15 scheduled
service items plus 11 inspection items. The app's `SERVICE_KEYWORDS` has ten canonical names.

### 5.1 Scheduled items (15)

| # | Factory string | Covered by an existing canonical name? |
|---|---|---|
| 1 | Change engine oil | Yes — `Oil Change` |
| 2 | Replace engine oil filter | Partly. Folded into `Oil Change` in practice; the FSM prints it as its own line |
| 3 | Lubricate steering linkage | **No** |
| 4 | Lubricate steering and suspension ball joints | **No** |
| 5 | Inspect brake linings | Partly — `Brake Pads`, but "linings" is an *inspect*, not a replace |
| 6 | Replace (engine) air cleaner element | Yes — `Air Filter` |
| 7 | Inspect engine air cleaner element, replace as necessary | Partly — same canonical name, different action |
| 8 | Replace spark plugs | Yes — `Spark Plugs` |
| 9 | Inspect drive belt, adjust tension as necessary | **No** (serpentine belt) |
| 10 | Drain and refill automatic transmission fluid | Yes — `Transmission Fluid` |
| 11 | Drain and refill manual transmission fluid | Collides with #10 under one canonical name; different fluid, different interval |
| 12 | Drain and refill transfer case fluid | **No** (NP231 / NP242) |
| 13 | Drain and refill front and rear axles | **No** (Dana 30 front; Dana 35 or Chrysler 8.25 rear) |
| 14 | Flush and replace engine coolant | Yes — `Coolant Flush` |
| 15 | Replace ignition cables | **No** (plug wires) |

### 5.2 Inspection items (11)

Check engine oil level · Check windshield washer solvent · Check tire pressure / inspect tires ·
Inspect battery, clean and tighten terminals, check electrolyte · Check fluid levels (coolant
reservoir, power steering, brake master cylinder, transmission) · Check all lights and electrical
items · Inspect exhaust system · Inspect brake hoses · Rotate the tires · Check coolant level, hoses
and clamps · Inspect underside and threaded fasteners after off-road operation.

### 5.3 What the ten canonical names get WRONG on this vehicle

| Canonical name | Problem |
|---|---|
| `Cabin Air Filter` | **The XJ does not have one.** No cabin/passenger-compartment filter appears anywhere in the 1997 or 2001 FSM. A seeded row for this is a phantom item. `sourced` — absence verified by full-text search of both FSMs. |
| `Brake Fluid` | **The factory schedule never names it.** Neither Schedule A nor B contains a brake fluid change or flush at any mileage. It appears only as a monthly *level check* at the master cylinder. Any brake-fluid interval on this vehicle is community practice (§6), not a factory figure. |
| `Battery` | The schedule has no battery *replacement* item at all — only a monthly inspect/clean/top-up. |
| `Transmission Fluid` | Ambiguous across three separate factory items: automatic (30k A / 12k B), manual (37.5k A / 18k B), and transfer case (30k A and B). |
| `Tire Rotation` | Not a schedule item; it hangs off the oil change (7,500 mi on A, 12,000 mi on B). |
| `Oil Change` | Correct name, but the interval the app holds is Schedule B's. |

### 5.4 Items the ticket asked about that the factory schedule does NOT name

All four verified absent by full-text search of the 1997 and 2001 FSMs:

- **PCV valve.** The 4.0L has a **CCV (Crankcase Ventilation) system**, not a serviceable PCV
  valve, and no PCV item exists in either schedule. Source: 1997 XJ FSM Group 9, "CRANKCASE
  VENTILATION SYSTEM", https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages
- **Fuel filter.** Verbatim, 1997 XJ FSM Group 14: *"Both fuel filters (at bottom of fuel pump
  module and within fuel pressure regulator) are designed for extended service. They do not require
  normal scheduled maintenance. Filters should only be replaced if a diagnostic procedure indicates
  to do so."* In-tank, part of the fuel pump module. Same URL.
- **Timing belt.** The 4.0L uses a **timing chain** with a tensioner (1997 XJ FSM Group 9, "TIMING
  CHAIN AND SPROCKETS"). There is no timing service item in either schedule. Same URL.
- **Exhaust manifold inspection.** Not in the gasoline schedule. The *diesel* schedule has "Check
  correct torque, exhaust manifold mounting nuts" at 500 km; the gasoline schedule has only the
  general "Inspect exhaust system" at each oil change. Source: 1999 XJ FSM Group 0 p. 0-2,
  https://archive.org/details/jeepcherokeexjfactoryservicemanual1999ocr1948pages

---

## 6. Capacities and specifications. FACTORY.

Source for the capacity table: 1997 XJ FSM Group 0 p. 0-3, "FLUID CAPACITIES";
byte-identical table in 2001 XJ FSM Group 0 p. 0-3.
https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages
https://archive.org/details/jeepcherokeexjfactoryservicemanual2001ocr3663pages

| System | Capacity (FSM) | Specification (FSM) | Source group |
|---|---|---|---|
| **Engine oil, 4.0L** | 5.7 L (**6.0 qts**) | API Service Grade Certified, or conforming to **API SH** or SH/CD. Energy Conserving type recommended | Group 0 p. 0-3; Group 9 p. 9-5 |
| **— does the 6.0 qts include the filter?** | **Yes.** The 1997 table heads the row bare "ENGINE OIL"; the **2001 table heads the identical row "ENGINE OIL W/FILTER CHANGE"** with the same 5.7 L / 6.0 qts figure. The diesel table in the same manuals says "(includes filter)" explicitly where the gasoline one does not | — | 2001 FSM Group 0 p. 0-3 |
| **Engine oil viscosity, 4.0L** | — | **10W-30** across the general range; **5W-30** where anticipated temperatures before the next oil change run low (FSM temperature chart, Group 9, spans -20°F to 100°F). The **1999** FSM labels the 10W-30 band **"(Preferred)"**; the 1997 FSM does not. Which side 1998 falls on is **not determined** | Group 9 p. 9-5 (1997); Group 9 (1999) |
| **Engine oil filter** | — | SAE **3/4-16** thread. *"Do not use oil filter with metric threads."* Mopar **P/N 5281 090** | Group 9 (1999); 1998 Parts Manual |
| **Air filter element** | — | Mopar **P/N 5300 4383** | 1998 Parts Manual |
| **Cooling system, 4.0L** | 11.4 L (**12 qts**), of which 1.0 qt is the coolant recovery reservoir | **50/50 ethylene-glycol antifreeze containing ALUGARD 340-2® and low mineral content water.** Minimum 44% antifreeze year-round, all climates. Propylene glycol and propylene/ethylene mixtures **prohibited**. 100% ethylene glycol prohibited | Group 0 p. 0-3; Group 7 |
| **Radiator pressure cap** | — | Relieves at 83–110 kPa (12–16 psi); rating engraved on the cap | Group 7 |
| **Automatic transmission, Aisin AW4** | Dry fill **7.8 L (16.5 pts)** per Group 0; Group 21 states approximate refill **8.0 L (16.9 pts)** | **Mopar Dexron IIE / Mercon.** *"Mopar Dexron II can be used but only in emergency situations where Mercon fluid is not available."* Verbatim identical in the 1997, 1999 and 2001 manuals | Group 0 p. 0-3; Group 21 "AW-4" |
| **— AW4 pan drop / screen service** | **Add 4 pints (2 quarts) initially**, run to temperature, cycle the ranges, then top to Full on the dipstick. Full overhaul with the converter drained: 10 pints (5 quarts) initially | — | Group 21, "REFILLING AFTER OVERHAUL OR FLUID/FILTER CHANGE" |
| **— AW4 filter** | **There is no throwaway filter.** A **cleanable metal oil screen** bolts to the valve body (10 N·m); the gasket is replaced, the screen is solvent-cleaned and blown dry. The pan uses **Threebond Liquid Gasket TB1281, P/N 83504038**, not a gasket. Pan bolts 7 N·m, drain plug 20 N·m | — | Group 21 |
| **— what the AW4 does NOT take** | **ATF+3 (7176) and ATF+4 (9602) are never specified for the AW4 in any year.** ATF+3 appears in the XJ FSM only for the Chrysler 30RH/32RH and the transfer cases; ATF+4 only in the 2001 book, and not for the AW4. Community "Dexron III" advice is a forward-substitution, not the printed spec | — | Full-text search, 1997/1999/2001 |
| **Automatic transmission, 30RH** (2.5L cars) | 4.67 L (9.86 pts) dry | Mopar ATF Plus 3, Type 7176 | Group 0 p. 0-3; Group 21 |
| **Manual transmission, AX15 (4x4)** | Group 0: 3.15 L (3.3 qts). Group 21, more precise: **3.10 L (3.27 qts) 4WD**, 3.15 L (3.32 qts) 2WD, dry fill | **Mopar 75W-90, API Grade GL-3** gear lubricant. Fill to the bottom edge of the fill plug hole, no more than 6 mm below it | Group 0 p. 0-3; Group 21 |
| **Manual transmission, AX5 (4x4)** | 3.3 L (3.5 qts) | as AX15 | Group 0 p. 0-3 |
| **Transfer case, NP/NV231 Command-Trac** | Group 0: 1.0 L (**2.2 pts**); Group 21: 1.2 L (**2.5 pts**) — the FSM disagrees with itself | **Mopar Dexron II, or ATF Plus 3, Type 7176** | Group 0 p. 0-3; Group 21 "NV231" |
| **Transfer case, NP/NV242 Selec-Trac** | Group 0: 1.3 L (**2.85 pts**); Group 21: 1.35 L (2.85 pts) | **Mopar Dexron II, or ATF Plus, Type 7176** | Group 0 p. 0-3; Group 21 "NV242" |
| **Front axle, 181-FBI (Dana 30)** | 1.48 L (**3.13 pts**) | **API GL-5 / MIL-L-2105C, thermally stable SAE 80W-90** hypoid gear lubricant. Heavy-duty or trailer tow: **SAE 75W-140 synthetic** | Group 0 p. 0-3; Group 3 |
| **Rear axle, 194-RBI (Dana 35)** | 1.66 L (**3.5 pts**) | as above. **+3.5 oz friction modifier if Trac-Lok** | Group 0 p. 0-3; Group 3 |
| **Rear axle, Chrysler 8-1/4"** | 2.08 L (**4.4 pts**) | as above. **+4 oz friction modifier if Trac-Lok** | Group 0 p. 0-3; Group 3 |
| **Fuel tank** | 76.4 L (**20.2 gal**) | — | Group 0 p. 0-3 |
| **Brake fluid** | not stated | **DOT 3**, meeting SAE J1703 | Group 5 |
| **Power steering** | *"dependent on engine/chassis options … these capacities may vary"* — the FSM **explicitly refuses** to publish a number | *"Use MOPAR Power Steering Fluid or equivalent. **Do not use automatic transmission fluid** and do not overfill."* | Group 0 p. 0-3; Group 19 |
| **Thermostat** | — | Closed below **195 °F (90 °C)** | Group 7 |
| **Spark plugs, 4.0L** | 6 off | The plug number moves across the years: **1997 FSM = RC12LYC**; **1998 Parts Manual = RC-12-LYC5, Mopar P/N 5602 7275** (qty 6 on engine code ER0 = 4.0L); 1999 and 2001 FSM = **RC12ECC**. Gap **0.89 mm (0.035 in.)** in every year. **"RC12LC4" is not the factory plug in any year checked** | Group 8D (1997/1999/2001); 1998 Parts Manual |
| **Firing order, 4.0L** | — | 1-5-3-6-2-4, clockwise | Group 8D |
| **Spark plug cable resistance** | — | 250–1000 ohms per inch (3,000–12,000 ohms/ft) | Group 8D |

### 6.1 HOAT vs green — settled, and the answer is green

The ticket flagged this as a trap. It is, and the trap runs the *other* way from what people expect.

**FACTORY: the XJ never used HOAT.** Full-text search of the 1997 FSM *and* the 2001 FSM — the last
model year of the XJ — returns **zero** hits for HOAT, MS-9769, G-05, or "5 year/100,000 mile". Both
manuals specify the same thing: ethylene glycol containing **Alugard 340-2®**, which is conventional
green Mopar Antifreeze/Coolant. Sources:
https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages (Group 7, "COOLANT
SELECTION—ADDITIVES") and
https://archive.org/details/jeepcherokeexjfactoryservicemanual2001ocr3663pages (same section).

`reasoned`: the HOAT confusion on 1998 Jeeps comes from the **Grand** Cherokee (WJ, 1999+) and
later Chrysler products, not the XJ. A 1998 XJ takes conventional green. Anyone who put HOAT in it
did not follow the factory manual.

### 6.2 The rows that ARE confirmed by a 1998-dated document

The Spanish-language **1998** XJ FSM, "Diferencial, Sistema de transmisión" section, is genuinely
1998-dated and confirms the axle specs verbatim. This is the only part of §6 that does not depend
on the 1997/2001 bracketing argument.

| Row | 1998 FSM text | 
|---|---|
| Front axle 181 FBI capacity | "Capacidad de lubricante . . . 1,48 l (3,13 pintas)" |
| Rear axle 194 RBI capacity | "Capacidad de lubricante . . . 1,66 L (3,5 pintas)" |
| Rear axle 8-1/4 capacity | "Capacidad de lubricante . . . 2,08 l (4,4 pintas)"; "Lubricante . . . SAE 80W-90" |
| Gear oil spec | "de calidad MIL-L-2105C y API GL 5" |
| Heavy duty / trailer tow | "El lubricante para ejes sometidos a servicio pesado o arrastre de remolque es el lubricante para engranajes SAE 75W-140 SINTETICO." |
| Trac-Lok additive | "Los diferenciales Trac-lok requieren el añadido de **105 ml (3,5 onzas líquidas) de modificador de fricción** al lubricante del eje. La capacidad total de lubricante del eje RBI 194 es de 1,66 litros (3,5 pintas), incluyendo el modificador de fricción, si fuese necesario." |
| Water-crossing caution — **not in any mileage schedule** | "PRECAUCION: Si el eje se sumerge en agua, el lubricante debe reemplazarse de inmediato para evitar el riesgo de fallos prematuros del eje." (If the axle is submerged in water, the lubricant must be replaced immediately.) |

Source: https://manuals.opinautos.com/published/Jeep-Cherokee_1998_ES__manual_de_taller_diferencial_sistema_transmision_83e7cb8f32.pdf

The **1998 Jeep Cherokee (XJ) Parts Manual** is also a genuine 1998-dated Mopar publication and
confirms, for model year 1998 specifically:

| Row | 1998 Parts Manual |
|---|---|
| Spark plug | **RC-12-LYC5**, Mopar P/N 5602 7275, **qty 6** on engine code **ER0 (4.0L)** |
| Engine oil filter | Mopar P/N **5281 090** |
| Air filter | Mopar P/N **5300 4383** |
| Transmission | Code **DGS = "4 Spd. Auto. AISIN"** — the AW4 — paired with ER0. Assembly 52104 180AB (2WD) / 52104 210AB (4WD) |

Source: https://archive.org/details/1998jeepcherokeexjpartsmanualocr437pages

### 6.3 Two places the FSM contradicts itself — read before trusting a partial scan

1. **A second, conflicting capacity page exists.** The export/diesel supplement bound into the same
   manuals carries its own Group 0 p. 0-2 fluid table giving NP231 = 1.3 L, front axle = 1.2 L,
   Dana 35 = 1.6 L, 8-1/4 = 2.3 L, with **4 oz / 5 oz** friction modifier. Those are not the US
   gasoline figures. The table in §6 above, from the main XJ Group 0 p. 0-3, is the one that
   governs a US 1998. A partial scan can very easily land you on the wrong page.
   Source: https://archive.org/details/jeepcherokeexjfactoryservicemanual1999ocr1948pages (diesel
   supplement Group 0 p. 0-1/0-2)
2. **The AW-4 chapter contradicts its own fluid heading.** The "OIL PUMP VOLUME CHECK" subsection
   says *"fill to the proper level with Mopar® ATF PLUS 3 (Type 7176)"* — boilerplate that leaked in
   from the Chrysler-transmission chapters. It contradicts the AW-4's own "RECOMMENDED FLUID"
   heading two pages earlier. **The heading governs: Dexron IIE/Mercon.**
   Source: https://archive.org/details/jeepcherokeexjfactoryservicemanual1999ocr1948pages (Group 21)

Also worth stating plainly: the FSM specifies coolant by **additive package** (Alugard 340-2®), not
by a Chrysler MS material-standard number. **Neither MS-7170 nor MS-9769 appears anywhere in the
1997, 1999 or 2001 XJ FSM.** Equating Alugard 340-2 to MS-7170 is correct but is an inference from
outside the manual, not FSM text. `reasoned`.

---

## 7. Age-driven items — **NOT FACTORY**

> **Nothing in this section is a Jeep figure.** The 1998 schedule assumed roughly a ten-year
> vehicle life and is a mileage ladder; none of it addresses a 28-year-old truck. Every row below
> is tagged **IND** (industry / trade standard practice), **MFR** (a non-Jeep manufacturer's
> published guidance), **XJC** (XJ owner-community practice), or **INF** (inferred, not stated in
> any source). Do not let any of these leak into a table labelled "factory".

### 7.1 Time-only fluid intervals the factory schedule does not provide

| Item | Time interval | Tag | Source |
|---|---|---|---|
| **Brake fluid** — the largest single gap | The factory schedule has **no** brake fluid item at all (verified in §5.3). Trade practice: **2 years / ~30,000 mi**. ATE TYP 200 (Continental): "optimal performance for **up to three years** under normal highway driving". Bosch ESI6: 3-year interval, standard DOT4 2 years | MFR / IND | https://www.continental-aftermarket.com/us-en/press/press-releases/2022/2022-06-21-ate-brake-fluids-engineered-for-quick-response-and-reliability-in-all-types-of-brake-systems · https://www.cars.com/articles/how-often-do-i-need-to-change-my-brake-fluid-1420680336417/ |
| Brake fluid hygroscopy — the mechanism | Absorbs roughly **2% water per year** through hoses, reservoir cap and caliper seals. DOT4 dry boiling point 265 °C falls to 160 °C wet (3.7% water) | IND | https://balancemotorworks.co.uk/2021/02/12/brake-fluid-moisture-content/ |
| Brake fluid — condition test rather than clock | Flush at 2.5–3% water, or **copper > 200 ppm** (Motorist Assurance Program guideline); test strips are the trade method | IND | https://shoppress.dormanproducts.com/brake-fluid-testing/ |
| Brake fluid — XJ community | 3 years or 30,000 mi, whichever first | XJC | https://naxja.org/threads/regular-and-preventative-maintenance-schedule.1043605/ |
| **Coolant** (conventional green / IAT) | 2 years, some formulations 3. The glycol does not break down; the **silicate/phosphate inhibitors deplete**. Factory figure is 3 years / 52,500 mi then 2 years / 30,000 mi (§3.1) — the age argument is that a depleted-inhibitor system in a 28-year-old cooling stack is a corrosion problem, not a freeze-point problem | IND | https://www.hagerty.com/media/opinion/the-hack-mechanic/just-chill-making-sense-of-the-coolant-conundrum/ |
| Coolant — XJ community | Every 2 years, some annually | XJC | https://naxja.org/threads/regular-maintenance-schedule-for-a-daily-driver.1168203/ |
| **Mixing coolant types** | HOAT + green is compatible but the long-life property **collapses to green's ~3-year life**. HOAT/green + **OAT** is the real hazard: organic acids react with silicates, inhibitors drop out as gelatinous sludge that blocks radiator tubes and the heater core and shreds the water pump seal | IND | https://cartipsdaily.com/ms-12106-coolant-equivalent · https://wranglertjforum.com/threads/is-generic-green-coolant-okay-or-will-it-cause-damage-to-my-tj.35230/page-2 |
| **Oil, low-mileage case** | If the truck does not accumulate miles, the interval is time: conventional ≈6 months, synthetic ≈12. Condensation and acids accumulate regardless of use. This *matches* the factory Schedule A 6-month figure | IND | https://engineerfix.com/how-often-should-you-change-your-oil-if-you-dont-drive-much/ |
| **Tyres** | Michelin: professional inspection annually after **5 years**; replace **10 years** after date of manufacture regardless of tread. Continental and Bridgestone publish the same 10-year removal. **The commonly-quoted "6 years" came from vehicle manufacturers (Ford, DaimlerChrysler, 2005), not tyre makers.** NHTSA publishes no age guideline of its own | MFR / IND | https://www.michelinman.com/auto/auto-tips-and-advice/tire-buying-guide/when-do-i-need-new-tires · https://www.continental-tires.com/tire-knowledge/replacing-tires/ · https://safetyresearch.net/two-tire-makers-add-tire-aging-replacement-guidelines-for-u-s-market/ |

### 7.2 Rubber and hoses — time, not mileage

| Item | Time interval | Tag | Source |
|---|---|---|---|
| Coolant hoses (upper/lower radiator, bypass, heater) | **4 years.** "Incidence of hose failure increases sharply after four years for most vehicles." Gates' own published position is narrower: electrochemical degradation is the leading failure mode, **inspect around 60,000 mi**; the flat 4-year number is trade-press attribution | IND / MFR | https://www.brakeandfrontend.com/cooling-system-hose-inspection-and-replacement/ · https://www.gates.com/us/en/fluid-power/engine-hose/coolant-hose.p.4175-000000-000001.html |
| Coolant hose condition test | Squeeze cold. Soft and pliable = fine; stiff, crackly, brittle or bulging = replace | IND | https://www.consumerreports.org/cars/car-repair-maintenance/how-to-inspect-car-belts-and-hoses-a3986860709/ |
| Rubber fuel hose (SAE 30R7) | **2–5 years.** Ethanol extracts the plasticiser oils and the tube goes brittle. On an E10-fed 28-year-old vehicle, replace any 30R6/30R7 with **SAE 30R9** (fluoroelastomer liner) — 30R7 permeates 550 g/m²/day vs 30R9's 15 | IND | https://vtauto.org/rubber-fuel-lines-ethanol/ · https://www.brakeandfrontend.com/tech-tip-avoid-comebacks-with-permeation-resistant-fuel-line-hose/ |
| Vacuum lines | Condition-based, replace as a set on an old engine. The CCV lines are worst — crankcase oil vapour swells and cracks them. XJ-specific silicone kits exist for 1991–2001 4.0L | IND / MFR | https://hpsimotorsports.com/products/hpsi-silicone-vacuum-hose-kit-jeep-cherokee-4-0l-1991-2001 |
| Brake flex hoses | **~6 years / 60,000 mi** preventative; 5–7 years is where the liner delaminates, swells, or collapses into a one-way restriction. **No OEM publishes a hard interval** | IND | https://www.jegs.com/tech-articles/how-long-do-brake-hoses-last-symptoms-of-failure/ |
| Steel brake lines | Rot from the outside on a 28-year-old chassis. Community verdict: if they are rusted, replace all of them. NHTSA links corroded brake pipes to extended pedal travel and reduced braking | XJC / IND | https://static.nhtsa.gov/odi/rcl/2009/RCDNN-09V144-5896.pdf |
| Serpentine belt | **5–6 years or 60,000–100,000 mi.** Modern EPDM belts resist cracking, so cracks are the wrong test — **material loss** is the indicator. Gates: inspect at 60k, replace worn parts by 90k. Replace the tensioner/idler pulley with the belt | MFR / IND | https://www.gates.com/content/dam/documents-library/tech-tips-bulletins/tt002-15.pdf |

### 7.3 XJ-specific failure points a mileage schedule will never name

All **XJC** unless tagged otherwise.

| Item | What actually fails | Source |
|---|---|---|
| **Cracked exhaust manifold** — the canonical 4.0L failure | A 1998 has the one-piece casting (Dorman 674-196 covers 91–99). Cracks at the #6 runner or the collector. Community framing: "not a question of if it will crack, but rather when." Symptom is a cold-start tick that quiets when warm. **Replace the motor mounts at the same time — worn mounts crack the new manifold.** Welded repair ~3 yr, new OEM ~5 yr, cheap aftermarket headers fail fast | https://naxja.org/threads/cracked-exhaust-manifold.65158/ · https://naxja.org/threads/0630-exhaust-manifold-constantly-cracking.1148812/ |
| **0331 cylinder head — a 1998 is NOT affected** | Castings by year: 7120 = 91–95, **0630 = ~96–99**, 0331 = 2000–01 XJ. The 0630 is one of the heads people swap *to* when a 0331 cracks. Verify the number stamped on the head rather than trusting the year | https://www.cherokeeforum.com/f2/00-01-xj-cracked-cylinder-head-overview-118922/ |
| Rear main seal | Two-piece seal hardens; leaks typically appear past ~100,000 mi. **Dye-trace before pulling the transmission** — valve cover, oil pan, and the oil filter adapter gasket all drain to the bellhousing and read as RMS | https://www.jeepin.com/features/rearmain · https://www.cherokeeforum.com/f2/rear-main-seal-still-leaking-45338/ |
| **Rear axle seals (C-clip design)** | Gear oil runs down the shaft into the drum. Oil-soaked shoes must be replaced, not cleaned. Replace the bearing with the seal. Repeat failures come from a groove worn in the shaft's seal land or undersize aftermarket bearings | https://naxja.org/threads/98-xj-8-25-rear-axle-seal.1073748/ |
| **Rear axle vent — the cheap fix, and the actual root cause** | A clogged vent lets the diff pressurise when hot and **push oil past the axle and pinion seals**; cooling in a water crossing then draws water in. **Check the vent before and after every seal job.** Stock vent sits only ~6" above the axle at the frame rail; ~$10 mod reroutes 5/16" tube into the taillight housing | https://www.cherokeeforum.com/f2/new-rear-axle-seals-leaking-115516/ · https://www.cherokeeforum.com/f2/how-extending-rear-diff-vent-67922/ |
| Which rear axle a 1998 has | 1997–2001 XJ = **Chrysler 8.25, 29-spline** (8.25" ring, 3" tube). Dana 35 = 7.58" ring, 2.62" tube. Forum lore, **unverified against a build sheet**, says factory-ABS trucks got the D35 — identify it physically (8.25 has a flat-bottomed cover and a cast pentastar) | https://naxja.org/threads/how-to-identify-your-xj-axle-and-the-original-gear-ratio.1114223/ |
| Valve cover gasket | A 1998 has a **stamped-steel** cover (steel 96–06, cast aluminium 87–95). It warps at the bolt bosses — check it flat on glass before refitting. Torque figures quoted on forums vary wildly; use the FSM number | https://naxja.org/threads/did-the-valve-cover-change-in-93.940601/ |
| Oil pan gasket | Weeps worst at the rear corners over the main cap. **Not necessarily an engine-lift job on an XJ** — pull the starter, jack the body, droop the axle | https://naxja.org/threads/identifying-oil-pan-gasket-or-rms-leak.1154754/ |
| Harmonic balancer | Elastomer dries out, the outer ring migrates or separates, and it can crack the crank. Inspect for rubber squeezing out; replace on any odd vibration | https://naxja.org/threads/harmonic-balancer-bad.970045/ |
| Motor mounts | Driver's side fails first. Bad mounts crack exhaust manifolds. Check for sheared mount-bracket bolts on the passenger side | https://www.naxja.org/forum/showthread.php?t=930703 |
| **Crankshaft position sensor** | **Thermal failure — dies hot, restarts cold, then bench-tests good.** Community rule for a 20+ year XJ: carry a Mopar spare in the glovebox. Cheap aftermarket CPSs are widely reported DOA or short-lived | https://naxja.org/threads/whats-the-signs-of-a-bad-cps.14576/ · https://naxja.org/threads/seriously-buy-the-mopar-crank-shaft-position-sensor.1161798/ |
| Camshaft position sensor | On a 1998 it lives **inside the distributor**. Heat/vibration failure → P0340, erratic stall, crank-no-start. Distinct failure: the **oil pump drive shaft seizes** and throws cam/crank out of sync — surging and bucking around 2000 rpm under light load | https://www.fixjeeps.com/camshaft-position-sensor.html |
| Radiator (plastic tank / aluminium core) | Tank-to-core crimp seam separates under thermal cycling; the plastic embrittles. Community treats it as a throwaway item, roughly 150–180k mi. All-metal copper/brass OE-style replacements exist that delete the plastic-to-metal seam | https://naxja.org/threads/replacing-radiator.1079998/ |
| Water pump / thermostat / fan clutch | On old pumps the **impeller fins corrode away entirely** while the pump looks fine externally. Do pump + thermostat + fan clutch as one job. Community: stay with the **195 °F** thermostat; running cold masks cooling faults | https://www.wayalife.com/threads/jeep-xj-cherokee-4-0l-water-pump-thermostat-fan-clutch-replacement-write-up.30583/ · https://www.cherokeeforum.com/f2/180-vs-195-a-55688/ |
| **Cooling system is open, not closed, on a 1998** | The closed pressurised-bottle system is **1987 – mid-1991 Renix only**. 1991–2001 HO XJs use an open system with a radiator filler neck, unpressurised overflow, 16 psi cap. Closed-system threads and conversion kits do not apply | http://www.olypen.com/craigh/cooling.htm |
| **Liftgate wiring harness** | Insulation dries and conductors break inside the rubber boot at the ~90° bend. One documented 1997: **13 wires, 9 fully broken, 5 partly.** Kills rear wiper, defroster, third brake light, reverse lights. Splicing is temporary; rebuild with fine-stranded flexible wire and reroute with slack. The four **door jamb boots** fail the same way and are called the #1 source of XJ electrical faults | https://www.cherokeeforum.com/f2/liftgate-wire-harness-261492/ · https://naxja.org/threads/rear-hatch-wiring.1128051/ |
| Blower motor resistor | Fan works **only on High**. Under-dash cover, passenger side, two screws, sub-1-hour DIY | https://www.cherokeeforum.com/f2/replace-blower-resistor-29932/ |
| Heater core | Dash-out, AllData quotes 8 hours. Not preemptive at that labour — but if the dash comes out for anything, do the core **and** the evaporator then | https://naxja.org/threads/heater-core-replacement.1150370/ |
| NSS / TRS on the AW4 | No crank in Park, dead reverse lights, no overdrive, P0700/P0705. **Clean and adjust it first** — that is usually the fix. Check fuses 10 and 18 before condemning it. 97–01 part differs from pre-97 | https://naxja.org/threads/transmission-range-sensor.1110453/ |
| AW4 heat | Heat is the AW4's real enemy — a healthy cooling system protects the transmission. **Do not power-flush an unknown-history AW4**; do repeated drain-and-fills to dilute | https://naxja.org/threads/aw4-fluid-change.1164302/ |
| Fuel pump / sending unit | Sending-unit failures are called out as common **particularly on 1997+**. Aftermarket senders are not calibrated to the XJ tank; members swap the old sender onto the new module. Pump failure signs: long crank, key-ON whine, sputter at speed, hard hot start. Keep the tank above ¼ — the pump is fuel-cooled | https://www.cherokeeforum.com/f2/used-xj-buyers-guide-checklist-stuff-look-161961/ · https://naxja.org/threads/fuel-level-sending-unit.1128861/ |
| Unit bearings (front hubs) | Growl that changes with steering load; play at 12/6. Stock-tyre life is reported as very long ("OEM last just about forever"); 33s with 4" backspacing kill them in months. Generic sealed-hub figure ~85–100k mi. Torque the axle nut with a torque wrench — it sets preload. **1999 changed composite to cast rotors, so hubs do not interchange across that line — order by year** | https://naxja.org/threads/unit-hub-bearing-and-axle-nut-torque.1110199/ |
| **Track bar / death wobble** | Front track bar is the #1 cause: bushings, axle-end joint, under-torqued bolt, and the axle-side bracket hole **wallowing oval**. Diagnose in order — wheels and balance, steering linkage play, ball joints and control arm bushings, track bar geometry and caster, steering box frame looseness, then **toe**. One documented case survived all-new parts and was cured by alignment. Do not parts-cannon | https://www.jeepforum.com/threads/xj-death-wobble-diagnosis.1287913/ · https://naxja.org/threads/wallowed-out-holes-axle-side-track-bar.1140211/ |
| Ball joints | ~100k typical, far less with salt or off-road. Lift the wheel and pry; any movement = replace | https://www.cherokeeforum.com/how-tos/a/jeep-cherokee-1984-2001-how-to-replace-ball-joints-398000 |
| Control arm bushings / leaf springs | Perished at 28 years. At this age community advice is **complete new arms** rather than re-bushing rusted originals. Rear: measure ride height and do the whole kit — springs, U-bolts, shackles, pins, bushings | https://naxja.org/threads/best-replacement-control-arms.1137301/ |
| Steering box | Leaks at the sector shaft / pitman seal; both preloads are adjustable but should be done on the bench | https://naxja.org/threads/steering-box-blowing-fluid-past-seal-at-steering-lock-really-struggling-after-months-of-trying.1170694/ |
| Headlights | The XJ has **no factory headlight relay** — all current runs through the switch. Measured 9 V at the lamps against 14 V at the battery; one genuine Mopar switch caught fire at 19 years old. Relay harness upgrade is a standard XJ job | https://naxja.org/threads/headlights-harness-upgrade-drl.1136487/ |
| **Body rot map** | Rockers (rot inside-out, **structural on a unibody**); rear quarters above/behind the rear wheel, cause named as the **factory insulation bag inside the quarter holding water against the metal**, with 1997–99 called out specifically; rear wheel-well pinch seam; floor pans (worse on ≤1996, **1997+ is the better side**); front footwells from the cowl seal and the heater-box top gasket; liftgate bottom seams; A-pillars and rain gutters. **The unibody rails and body mounts are the important structural check — there is no frame to fall back on** | https://www.cherokeeforum.com/f59/common-rust-prone-areas-jeeps-65882/ · https://naxja.org/threads/rear-quarter-panel-rot-what-to-do.1130153/ |
| Not applicable to a 1998 | The **C101 bulkhead connector** refresh is a 1987–88 Renix item; the factory deleted it for 1989. Ignore those threads | https://cruiser54.com/?p=24 |

### 7.4 Where community claims contradict the FSM

Flagged rather than silently reconciled.

| Claim | Source of claim | What the FSM says |
|---|---|---|
| "The 4.0L timing chain has no tensioner, only a guide" | XJC, https://wranglertjforum.com/threads/timing-chain-tension.29952/ | **Contradicted.** 1997 XJ FSM Group 9: *"The timing chain tensioner reduces noise and prolongs timing chain life. In addition, it compensates for slack in a worn or stretched chain and maintains the correct valve timing"*, plus a removal step *"To replace the timing chain tensioner, the oil pan must be removed."* A 1998 4.0L **has** a tensioner. https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages |
| "Timing chain stretch — check around 100,000 mi" | XJC | Not contradicted, just absent. There is no timing item in either schedule at any mileage. |

---

## 8. Community practice — **NOT FACTORY**

### 8.1 The oil interval, honestly

This is the question ticket 06 turns on, so it gets its own table.

| Position | Figure | Tier | Source |
|---|---|---|---|
| Factory, normal use | **7,500 mi or 6 months**, whichever first | **FACTORY** | 1997/2001 XJ FSM Group 0 Schedule "A", https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages |
| Factory, severe use | **3,000 mi**, no time interval published | **FACTORY** | Same, Schedule "B" |
| The caveat the factory figure carries | The 7,500 figure was set against **1998-era conventional oil at API SH**, with no oil life monitor anywhere on the vehicle. The FSM's only concession to condition is the binary A/B choice | REASONED | — |
| XJ community, full synthetic, ordinary use | **5,000–10,000 mi.** "I do 5000 between changes and use a larger oil filter"; "Normally I do 10,000 mile changes" | XJC | https://naxja.org/threads/full-synthetic-oil-in-the-4-0l.1047920/ |
| XJ community, full synthetic, short-trip use | **8,000 mi or 1 year**, whichever first. The thread's point is that short trips are severe service regardless of what oil is in the sump — incomplete warm-up, moisture in the oil | XJC | https://naxja.org/threads/oil-change-frequency-for-fully-synthetic-and-light-use.1161574/ |
| XJ community, outlier | 12,000–15,000 mi on Amsoil, one owner past 350,000 mi | XJC | Same thread |
| XJ community, high-mileage caution | Synthetic can expose an existing leak on a worn 4.0L; a synthetic blend is suggested as an intermediate step. One mechanic argues it is not worth the money past ~200k | XJC | https://naxja.org/threads/full-synthetic-oil-in-the-4-0l.1047920/ |
| Time floor when the truck sits | Conventional ≈6 months, synthetic ≈12, regardless of miles | IND | https://engineerfix.com/how-often-should-you-change-your-oil-if-you-dont-drive-much/ |

**What this means for ticket 06.** Kevin's 7,500 is not a stretch of the factory number — **it is
the factory number**, exactly, under Schedule A. The 3,000 currently on his phone is the factory
severe number, correctly retrieved for a question nobody meant to ask. Changing the seed prompt to
ask for the **normal** schedule produces 7,500 without any further intervention, and a synthetic
interval of 5,000–10,000 straddles it, so the factory figure is defensible on modern oil too. The
one honest addition the factory schedule lacks is the **6-month floor**, which Schedule A already
publishes, and which matters more than the mileage for a truck that does not accumulate miles.

### 8.2 The community "just bought an XJ" list

All **XJC**. Sources: https://www.cherokeeforum.com/f2/used-xj-buyers-guide-checklist-stuff-look-161961/ ·
https://naxja.org/threads/preventative-maintenance-high-mileage.1109281/ ·
https://naxja.org/threads/200-000-mile-maintenance.1032089/

1. **Change every fluid** — engine oil, AW4 plus filter, transfer case, both diffs, brake fluid,
   power steering, coolant. The most-repeated first instruction across every thread.
2. Full tune-up: plugs, wires, cap, rotor, air filter.
3. **CPS** — replace, and keep a Mopar spare in the glovebox.
4. Cooling system top to bottom: radiator, debris-clogged condenser, hoses, thermostat, water pump,
   belt, fan clutch, electric fan. Protects the AW4 as much as the engine.
5. Steering and suspension: inspect then replace — tie rod ends, track bar, ball joints, control arm
   bushings, sway bar bushings and links, shocks, U-joints, wheel bearings.
6. Motor mounts and transmission mount.
7. Headlight relay harness upgrade.
8. Grease the rear driveshaft slip yoke (also cures the clunk from a stop).
9. Electrical sweep: all four door jamb boots, the liftgate boot, all windows, all door speakers.
10. Function test: four blower speeds, heat, A/C, cruise, rear wiper/washer, defroster, fuel gauge.
11. Diff fluid: check for shavings, mud, water.
12. Brakes: flush and bleed; rear shoes and the chronically seized self-adjuster hardware.
13. Fuel injector cleaning has **no** published time interval from anyone; Chevron markets Techron
    at every 1,000 mi, which is a marketing interval, not an engineering one
    (https://www.chevronlubricants.com/en_us/home/learning/about-our-brands/techron/faqs.html, MFR).

Governing philosophy quoted repeatedly across those threads: *"Don't replace anything else unless
it is actually bad. Fix what's broken. Upgrade as you go."*

### 8.3 Where the widely-copied community fluid table is simply WRONG

There is one XJ fluids table that has been copied across forums and parts-retailer blogs for years.
It disagrees with the FSM in seven places. Listed here so nobody reconciles the app against it by
accident. Community source: https://naxja.org/threads/fluids.1107467/ and its ExtremeTerrain /
Morris 4x4 mirrors.

| Item | The community table says | The FSM says |
|---|---|---|
| Cooling system, 4.0L | 10.5 qts (9.9 L) | **12 qts (11.4 L)**, including 1 qt of reservoir |
| AW4 fluid | Dexron III / Mercon | **Dexron IIE / Mercon** |
| AW4 drain and refill | 4 quarts | **4 pints (2 quarts)** initially, then top to Full |
| Transfer case fluid | Dexron III / Mercon | **Dexron II or ATF+3 (type 7176)** |
| Dana 30 front capacity | 2.5 pts (1.2 L) | **3.13 pts (1.48 L)** |
| Axle gear oil viscosity | 75W-90 | **80W-90**; 75W-140 synthetic only for heavy duty / trailer tow |
| Dana 35 vs 8.25 capacities | frequently swapped | **D35 = 3.5 pts, 8.25 = 4.4 pts** |

`reasoned`: the community Dana 30 figure and the 75W-90 viscosity look lifted from a **TJ Wrangler**
table — that same table lists 30RH/32RH transmissions, which no XJ ever had.

---

## 9. Assumptions ledger

Every non-trivial claim in this document, tagged `sourced` (with the URL it came from) or
`reasoned` (my inference, not printed anywhere).

### Factory claims — `sourced`

| # | Claim | Tag | Source |
|---|---|---|---|
| 1 | Chrysler published exactly two XJ schedules, "A" (normal) and "B" (severe) | `sourced` | https://archive.org/details/jeepcherokeexjfactoryservicemanual1997ocr1724pages (Group 0 p. 0-4) |
| 2 | Schedule A oil + filter = **7,500 mi or 6 months** | `sourced` | Same, p. 0-4 |
| 3 | Schedule B oil + filter = **3,000 mi**, no time interval published | `sourced` | Same, p. 0-6 |
| 4 | The seven-item Schedule B trigger list, verbatim | `sourced` | Same, p. 0-4 |
| 5 | Schedule B publishes **no** time intervals at all | `sourced` | Verified against both the 1997 and 2001 printings, full text read |
| 6 | Cold weather is a *diesel* Schedule B trigger, not a gasoline one | `sourced` | https://archive.org/details/jeepcherokeexjfactoryservicemanual1999ocr1948pages (Group 0 p. 0-2) |
| 7 | Front/rear axle service in Schedule B is gated by its own `‡` footnote trigger list | `sourced` | 1997 FSM p. 0-8 |
| 8 | Tire rotation is not a schedule item; it rides on the oil change (7,500 A / 12,000 B) | `sourced` | 1997 FSM p. 0-4 |
| 9 | Coolant: first flush 3 years or 52,500 mi, then every 2 years or 30,000 mi | `sourced` | 1997 FSM Group 0 p. 0-5 and Group 7 |
| 10 | The FSM states the coolant interval two slightly different ways in Group 0 and Group 7 | `sourced` | Both passages quoted in §3.1 |
| 11 | Every capacity figure in §6 | `sourced` | 1997 FSM Group 0 p. 0-3, identical in 2001 FSM |
| 12 | Coolant spec is ethylene glycol containing **ALUGARD 340-2®**, 50/50, min 44% year-round; propylene glycol prohibited | `sourced` | 1997 FSM Group 7, "COOLANT SELECTION—ADDITIVES" and "ETHYLENE-GLYCOL MIXTURES" |
| 13 | The XJ **never** specified HOAT — zero hits for HOAT / MS-9769 / G-05 in either the 1997 or the 2001 (final model year) FSM | `sourced` | Full-text search of both djvu.txt transcriptions |
| 14 | AW4 fluid = Mopar **Dexron IIE / Mercon**; Dexron II emergency only | `sourced` | 1997 FSM Group 21, "RECOMMENDED FLUID CAPACITY" (AW-4) |
| 15 | The 2001 FSM updates the same AW4 paragraph to Dexron III / Mercon | `sourced` | 2001 FSM Group 21 |
| 16 | NV231 fluid = Mopar Dexron II or ATF Plus 3 type 7176; NV242 the same | `sourced` | 1997 FSM Group 21, NV231 and NV242 "RECOMMENDED LUBRICANT AND FILL LEVEL" |
| 17 | The FSM disagrees with itself on NV231 capacity (2.2 pts in Group 0, 2.5 pts in Group 21) | `sourced` | Both passages read |
| 18 | Axle lube = API GL-5 / MIL-L-2105C thermally stable SAE 80W-90; 75W-140 synthetic for heavy duty/tow; 3.5 oz (194 RBI) / 4 oz (8-1/4) friction modifier with Trac-Lok | `sourced` | 1997 FSM Group 3 and Group 0; **also confirmed in the 1998-dated Spanish FSM**, https://manuals.opinautos.com/published/Jeep-Cherokee_1998_ES__manual_de_taller_diferencial_sistema_transmision_83e7cb8f32.pdf |
| 19 | Engine oil = API SH or SH/CD, 10W-30 or 5W-30 per the temperature chart, 6.0 qts with filter on the 4.0L | `sourced` | 1997 FSM Group 9 p. 9-5 and Group 0 p. 0-3 |
| 20 | Spark plugs = Champion RC12LYC, 0.035 in gap | `sourced` | 1997 FSM Group 8D specification table |
| 21 | Brake fluid = DOT 3 meeting SAE J1703 | `sourced` | 1997 FSM Group 5 |
| 22 | The factory schedule contains **no brake fluid change** at any mileage, on either schedule | `sourced` | Full-text search of Group 0 pp. 0-4 to 0-8 in both the 1997 and 2001 FSM |
| 23 | The XJ has **no cabin air filter** | `sourced` | Zero hits for "cabin air filter" / "passenger compartment filter" across the full 1997 and 2001 FSM text |
| 24 | Fuel filters are in-tank, "designed for extended service", not a scheduled item | `sourced` | 1997 FSM Group 14, quoted verbatim in §5.4 |
| 25 | The 4.0L uses a **CCV** system, not a serviceable PCV valve; no PCV item in either schedule | `sourced` | 1997 FSM Group 9, "CRANKCASE VENTILATION SYSTEM" |
| 26 | The 4.0L uses a timing **chain**, **and it has a tensioner** | `sourced` | 1997 FSM Group 9, "TIMING CHAIN AND SPROCKETS" |
| 27 | No exhaust manifold inspection item exists in the gasoline schedule (only in the diesel one) | `sourced` | 1997 and 2001 gasoline Group 0; 1999 diesel Group 0 |
| 28 | Radiator pressure cap relieves at 12–16 psi | `sourced` | 1997 FSM Group 7 |
| 29 | The FSM publishes no power steering capacity, by its own statement | `sourced` | 1997 FSM Group 0 p. 0-3, quoted |
| 30 | The 1999 XJ FSM Group 0 covers diesel only and redirects to the 1997 manual for gasoline schedules | `sourced` | https://archive.org/details/jeepcherokeexjfactoryservicemanual1999ocr1948pages |
| 31 | The 1997 FSM covers 1997–1999 Cherokees per an XJ reference site | `sourced` (secondary) | http://www.xjjeeps.com/ — index page text; the direct manuals page 404s |
| 32 | Item counts: 15 distinct scheduled service items, 11 inspection items, 26 total factory strings | `sourced` | Enumerated by hand from the full Group 0 text; see §5 |
| 33 | The 6.0 qts oil figure **includes the filter** — the 2001 FSM heads the identical row "ENGINE OIL W/FILTER CHANGE" | `sourced` | 2001 FSM Group 0 p. 0-3 |
| 34 | AW4 pan-drop refill is **4 pints (2 qts) initially**, then top to Full; overhaul with drained converter is 10 pints | `sourced` | 1999 FSM Group 21, "REFILLING AFTER OVERHAUL OR FLUID/FILTER CHANGE" |
| 35 | The AW4 has a **cleanable metal screen**, not a throwaway filter; the pan is sealed with Threebond TB1281 P/N 83504038, not a gasket | `sourced` | 1999 FSM Group 21 |
| 36 | **ATF+3 and ATF+4 are never specified for the AW4** in any XJ model year | `sourced` | Full-text search of the 1997, 1999 and 2001 FSMs |
| 37 | The AW-4 chapter's "OIL PUMP VOLUME CHECK" contradicts its own fluid heading by naming ATF Plus 3; the heading governs | `sourced` | 1999 FSM Group 21, both passages read |
| 38 | A **second, conflicting capacity table** exists in the bound export/diesel supplement (231 = 1.3 L, front = 1.2 L, D35 = 1.6 L, 8-1/4 = 2.3 L, 4/5 oz modifier) and is not the US gasoline figure set | `sourced` | 1999 FSM diesel supplement Group 0 pp. 0-1/0-2 |
| 39 | 1998 factory part numbers: oil filter 5281 090, air filter 5300 4383, spark plug RC-12-LYC5 / 5602 7275 qty 6 on ER0, AW4 trans code DGS | `sourced` | https://archive.org/details/1998jeepcherokeexjpartsmanualocr437pages — a genuinely **1998-dated** Mopar publication |
| 40 | The plug part number moves by year: RC12LYC (1997 FSM), RC-12-LYC5 (1998 parts book), RC12ECC (1999 and 2001 FSM). **"RC12LC4" is not the factory plug in any year checked** | `sourced` | Those four documents |
| 41 | Power steering: "Do not use automatic transmission fluid and do not overfill"; the FSM explicitly declines to publish a capacity | `sourced` | 1999 FSM Group 19; Group 0 p. 0-3 |
| 42 | Thermostat closed below 195 °F (90 °C) | `sourced` | 1999 FSM Group 7 |
| 43 | AX15 Group 21 dry fill is 3.10 L (3.27 qts) 4WD / 3.15 L (3.32 qts) 2WD, slightly finer than Group 0's single 3.15 L figure | `sourced` | 1999 FSM Group 21 |
| 44 | The widely-copied community fluids table disagrees with the FSM in seven specific places | `sourced` | §8.3; https://naxja.org/threads/fluids.1107467/ vs the FSM rows cited in §6 |

### Explicitly NOT determined — no factory source found, and nothing substituted

| Open question | Why it is open |
|---|---|
| Whether the **1998** viscosity chart labelled 10W-30 "(Preferred)" | The 1997 chart does not carry the label; the 1999 chart does. 1998 falls between and was not read. |
| The **1998** API service grade letter | 1997 names SH / SH-CD; 1999 and 2001 name no letter at all, only "API Service Grade Certified". API SJ appears in none of them. |
| Power steering fluid capacity | The FSM refuses to state one, by design. Not a gap in the research. |
| MS-7170 / MS-9769 designations | **Neither appears anywhere** in the 1997, 1999 or 2001 XJ FSM. Chrysler specified coolant by additive package in this era. |
| Whether a genuine 1998 Group 0 exists in any free archive | It does not. The 1998 Cherokee service manual is Chrysler P/N 81-370-8146, print-only. The Spanish 1998 FSM published online omits Group 0. |

No community figure was substituted for any of the five above.

### Inferences — `reasoned`

| # | Claim | Tag | Basis |
|---|---|---|---|
| 45 | **The 1998 schedule is identical to the 1997 and 2001 schedules** | `reasoned` | No 1998-dated Group 0 was obtainable. The 1997 and 2001 printings are item-for-item and mile-for-mile identical, 1998 sits between them, and the 1999 FSM names the 1997 manual as the authority. Strong, but it is an inference, not a 1998 document. **This is the single largest caveat in this file.** |
| 46 | A daily driver in ordinary mixed use is a Schedule **A** vehicle | `reasoned` | The trigger list is worded in terms of *frequent* or *dominant* conditions ("frequent short trips", "more than 50% of driving"), not conditions that have ever occurred |
| 47 | The app's 3,000-mile figure came from `VehicleController.kt:740-742` asking for the severe schedule | `reasoned` | The severe schedule genuinely is 3,000 miles; the prompt asks for severe by its own text. Consistent, not proven |
| 48 | 1998 engine oil grade is API SH as in 1997 | `reasoned` | 1997 FSM says SH/SH-CD; the 2001 FSM drops the letter grade entirely and says only "API Service Grade Certified". 1998 falls between and was not read |
| 49 | The HOAT-on-a-1998-Jeep belief comes from the WJ Grand Cherokee and later Chrysler products | `reasoned` | The XJ manuals never mention HOAT through the final model year; the confusion has to originate somewhere else |
| 50 | The 7,500 figure assumed conventional oil with no oil life monitor | `reasoned` | The FSM specifies API SH conventional and offers only the binary A/B choice; no monitor exists on the vehicle. Not stated as a caveat by Chrysler |
| 51 | A synthetic interval of 5,000–10,000 mi straddles the factory 7,500, so the factory figure is defensible on modern oil | `reasoned` | From the community range in §8.1 |
| 52 | The 6-month floor matters more than the mileage for a low-mileage truck | `reasoned` | Combines the factory 6-month figure with the industry time-based argument in §7.1 |

### Community and industry claims — `sourced`, but NOT factory

Everything in §7 and §8 carries its own inline tag (**IND** / **MFR** / **XJC** / **INF**) and its
own URL. Those tiers are as reported by the sources cited; I did not independently verify a
manufacturer's service-life figure against the manufacturer's own engineering data, and several of
the trade-press numbers (notably the "4 years" for coolant hose) are attributions to Gates rather
than something on a Gates page. Three specific weaknesses to carry forward rather than launder:

- The Bosch ESI6 3-year figure and the Dayco pulley guidance came from a **search index of a PDF
  and a 403-blocked page**, not from reading the source document.
- The Dana 35 / factory-ABS pairing is **forum lore, unverified against a build sheet**. Identify
  the axle physically.
- The claim that ABS-light-as-hub-symptom does not transfer to an XJ is tagged **INF** by the
  contributing research: it follows from the knuckle-mounted wheel speed sensor, but no source
  states it.

### Deliverables checklist against the ticket

| Ticket question | Answered in |
|---|---|
| 1. Both schedules, item by item, mileage and time | §3 and §4. **Schedule B has no published time intervals** — that is the answer, not a gap. |
| 2. What lands in Schedule B and what triggers it | §1, seven verbatim triggers, plus the separate `‡` axle trigger |
| 3. The oil interval under each, plus the modern-synthetic caveat clearly separated | §3, §4, and §8.1 |
| 4. Every item the factory schedule names | §5 — 15 scheduled + 11 inspection = 26 strings, plus §5.3 on where the ten canonical names go wrong and §5.4 on four things that are *not* in the schedule |
| 5. Age-driven items, time-only, clearly labelled non-factory | §7 |
| 6. Capacities and specs | §6, with §6.1 settling HOAT vs green and §6.2 flagging the rows a 1998-dated document confirms |
