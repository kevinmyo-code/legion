---
map: proactive-mode
ticket: 11
title: "The concierge reframe missed the largest prompt surface"
type: bug
status: built
status-detail: "2026-08-21 - 236 literals renamed across 15 files, guard test added; owes a run on the phone"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The concierge reframe missed the largest prompt surface

## What is wrong

On 2026-08-20 Kevin said he was *still* hearing drive framing, and commit `557c436` renamed the
user's role from "the driver" to "the user" across the prompt layer - **45 string literals in
`ai/AriaBrain.kt`, `service/AriaForegroundService.kt` and `service/LiveSessionController.kt`** - and
added `ASSISTANT_FRAME` so the concierge frame is stated outright. CLAUDE.md §1 now records that as
done.

**`service/LiveToolbox.kt` was not in that commit, and it is bigger than all three files together.**

Verified by grep, 2026-08-21:

| Surface | Lines with "driver" inside a string literal |
|---|---|
| `service/LiveToolbox.kt` | **183**, of which only ~34 are car/OBD tools |
| everything the reframe actually touched | 45 literals total |

Tool descriptions are not incidental prose. They are sent to the model **in the system context on
every single turn**, alongside `ASSISTANT_FRAME`. So the frame says "concierge, and never assume
they are driving" while roughly **149 non-fleet tool descriptions** immediately restate that the
person is a driver - in the music tools, the reminder tools, the workout planner, the meal log, the
budget tools, the sleep target, the saved-places tools, the grocery-receipt tool. Examples, verbatim:

- `"Set a monthly spending budget for one category. Use when the driver ..."`
- `"Log a meal from the driver's spoken description ..."`
- `"Set the driver's nightly sleep target ..."`
- `"What to remind the driver about, in their own words ..."`

This is the exact mechanism CLAUDE.md §1 already names as the cause - *"the model was told over and
over who it was talking to and answered accordingly"* - left standing in the one place it is
repeated most often.

## Why it is on this map

Proactive speech is where a wrong frame is most expensive: an answer with a bad frame is a slightly
odd reply to a question Kevin asked, while a **greeting** with a bad frame is the app volunteering a
drive he is not on. The opener was de-carred twice already (2026-08-20, then 2026-08-21) and the
framing pressure never came from the opener.

## To decide, then do

1. **Rename or leave the ~34 car-tool literals?** The reframe's own commit changed car-adjacent
   strings too, on the grounds that the rule is about who the person is, not what the tool does.
   Following that precedent means renaming all 183; the alternative is that a car tool may say
   "driver" because it only ever fires with the dongle connected. **Recommendation: rename all of
   them** - consistency is the whole mechanism here, and `ObdBluetoothManager.isConnected` already
   supplies the car frame where it is true.
2. **Mechanical or reviewed?** 183 lines is sed-able, but "the driver's" / "the driver" / "a driver"
   each need a different substitution and some lines are comments that must NOT change (commit
   `557c436` deliberately left KDoc and identifiers alone, so history is not falsified). Needs a
   diff read, not a blind replace.
3. **Is there a check that stops the next miss?** `AriaBrainHonestyClauseTest` guards a clause's
   presence. The same trick works here: a test that fails on "driver" appearing in any string
   literal reachable by the model, with the fleet files allowlisted if (1) goes the other way.
   Without it, the next prompt surface added misses the reframe exactly as `LiveToolbox` did.

## Notes

- **Nothing was hidden.** `557c436`'s message is explicit about which three files it touched. The
  failure is that the count of 45 was reported as the size of the problem, and nobody grepped the
  toolbox.
- Three more leaks fixed in passing on 2026-08-21, all in unsolicited raise prompts:
  `AmbientListener` ("the driver/cabin"), `TelephonyController` ("the driver's phone"),
  `ReminderAlarmReceiver` ("remind the driver"). `ai/OnboardingFlow.kt:24` still says it and is
  untouched - it belongs to the onboarding rewrite, not here.

## Resolution - 2026-08-21

**All three points decided as recommended, and built.**

1. **Renamed all of them, car tools included.** Following `557c436`'s own precedent: the rule is
   about who the person is, not what the tool does, and `ObdBluetoothManager.isConnected` already
   supplies the car frame where it is actually true.
2. **Scripted, then read as a diff.** A scanner that only rewrites inside Kotlin string literals on
   non-comment lines, so KDoc, comments, identifiers and ticket references keep saying driver
   exactly as `557c436` intended. **236 replacements across 15 files:**

   | File | Renamed |
   |---|---|
   | `service/LiveToolbox.kt` | 183 |
   | `vehicle/DiagnosticAgent.kt`, `vehicle/MaintenanceAgent.kt` | 4 each |
   | `ai/MemoryConsolidator.kt` | 5, surgically |
   | `ai/ReflectionEngine.kt`, `ai/AriaBrain.kt`, `ai/DriverProfile.kt`, `vehicle/CarToolbelt.kt` | 2 each |
   | `service/LiveSessionController.kt`, `advisor/AdvisorBriefs.kt`, `advisor/Priming.kt`, `meals/MealAgent.kt`, `workouts/WorkoutPlanAgent.kt`, and four more `vehicle/` controllers | 1 each |

   **`ai/AriaBrain.kt` still had two of its own** - "surfaced when the driver arrives at a place" and
   "it's driver-initiated only" - inside the very file the reframe was written in.

   `service/LiveSessionController.kt:459` prefixed every live-context block with **"(Current
   car/driver context...)"**, so the car frame was restated at the head of the injected context on
   every session. Now "(Current context...)".

3. **`ai/PromptRoleNamingTest.kt`** walks the whole main source tree, scans string literals on
   non-comment lines, and fails on `drivers?`. **Verified by planting a leak in `MealAgent` and
   watching it fail**, then reverting - a guard nobody has seen fail is not a guard. It asserts it
   found the tree and found >100 files, so it cannot pass by scanning nothing (CLAUDE.md §4 rule 6).

### Deliberately NOT renamed - the allowlist, each with its reason

| Kept | Why |
|---|---|
| `vehicle/PidSpec.kt` (2) | OBD-II signal names from the standard - "Driver's demanded engine torque" is about whoever is physically driving |
| `ai/MemoryConsolidator.kt`, `ai/ReflectionEngine.kt`, `data/local/CompanionMemory.kt`, `data/local/EpisodicTurn.kt` | **`"driver"` is a STORED category value** (car_anchored/driver/relationship). Renaming it would orphan every row already written. The surrounding PROSE was renamed; the key was not |
| `vehicle/VehicleController.kt` (1) | A `Log.d` string |
| `ui/fleet/CarRows.kt` (1) | A `@Preview` name |
| `ai/OnboardingFlow.kt` (10) | **Deferred, not accepted.** Car-framed throughout - "the driver is at the wheel", "you are their car, not a friend riding along" - so a rename would leave the frame intact and hide it. Needs the onboarding rewrite |
| `ai/PersonaTraits.kt` (6) | Orphaned, CLAUDE.md §10 - `assemblePersona()` has no production caller |

### Verification

- `compileDebugKotlin -Pnokey` green; `testDebugUnitTest` green, **1765 tests**.
- Two `MaintenanceAgentDescribeItemTest` assertions asserted the old wording verbatim and were
  updated with it.
- **OWED: a run on the phone.** Nothing here proves the model actually stops framing answers around
  driving; that is the whole point of the change and only Kevin's ear settles it. Status stays
  `built` until then.
