# A recall button

Type: task
Status: open
Blocked by: 04, 13

## Input from ticket 01 (2026-08-15) - this ticket got worse

The `confirmed` gate in question 4 below **does not protect anything on Kevin's actual car**.
`confirmed = 1` on the Jeep row, but `make`, `model` and `year` are **empty**. So the gate passes
and `fetchRecalls` queries NHTSA with an empty make, an empty model and model year 0.

**A button that reports "no open recalls" after asking about no car is worse than no button.**
This ticket must not ship until the identity is restored (ticket 13) - and the guard it needs is
not `confirmed`, it is **year/make/model all actually present**.

## Question

Kevin: the specs screen shows the VIN but there is no way to check recalls; Midnight AI had a button.

**The capability already exists in LEGION.** Nothing needs building except reachability.

- `VinDecoder.fetchRecalls(year, make, model)` (`vehicle/VinDecoder.kt:98-103`) against
  `https://api.nhtsa.gov/recalls/recallsByVehicle` (`:28-29`), parsed into
  `VinDecoder.Recall(campaign, component, summary, remedy)` (`:62-68`)
- Wrapped by `VehicleSpecController.recalls(context, vehicleId)` (`:98-105`)
- Recalls are **never stored** - `data/local/VehicleSpec.kt:15` says so deliberately

It is reachable two ways, **neither of them a button**:

1. Voice tool `check_recalls` (`LiveToolbox.kt:873-882`, dispatch `:1386`, impl `:1883-1905`)
2. A startup proactive, `AriaForegroundService.checkRecallsOnce()` (`:462-475`), gated on
   `DebugSettings.recallAlertsEnabled` - which **defaults false**, and whose setter
   `DebugSettings.setRecallAlerts` (`:24`) has **zero callers**. There is no way to turn it on.

`ui/fleet/VehicleSpecsScreen.kt` renders a VIN pane with COPY / RE-READ / READ VIN (`:157-222`) and
a Factory pane (`:224-254`). It never mentions recalls and never imports `VinDecoder`.

## What to do

1. **Put the button on `VehicleSpecsScreen`**, beside the existing VIN actions. Mission-control's
   control vocabulary governs: deck-native in look, M3 in machinery, `Modifier.toggleable`/`clickable`
   with a real `stateDescription` and a 48dp target.
2. **Render the result.** A recall has a campaign number, a component, a summary and a remedy - that
   is a paragraph each, not a row. Decide list-then-detail vs. expandable rows. Zero recalls is the
   expected answer on a 28-year-old vehicle and **"no open recalls" must read as a completed check,
   never as an empty state that looks like a failure to load.**
3. **Handle the three outcomes distinctly**: recalls found, none found, and lookup failed
   (offline, NHTSA down, malformed response). CLAUDE.md §7: network calls degrade gracefully
   offline. All three said in words.
4. **The `confirmed` gate.** `check_recalls` refuses on an unconfirmed car
   (`LiveToolbox.kt:1884-1893`), because the placeholder seed is itself a 1998 Jeep Cherokee and
   reporting recalls on a mascot car would be a lie. **The button must honour the same gate**, and
   ticket 04 / ticket 01 establish whether Kevin's real row is `confirmed = true`. If it is not,
   **this button will refuse to work and the reason will be invisible** - so the refusal needs
   words and a route to fixing it.
5. **Decide what happens to `recallAlertsEnabled`.** Either give it a Settings toggle or delete it.
   A preference with no setter is dead code that reads like a feature - the same category as
   `refreshServiceIntervals` in ticket 05.
6. **Recalls are keyed by year/make/model, not by VIN** (`VinDecoder.kt:94-96`), despite sitting
   next to the VIN on screen. Do not let the button's placement imply a VIN-specific check. If the
   NHTSA VIN-based endpoint exists and is better, note it - **but do not silently claim precision
   the query does not have.**

## Why it is a task, not a decision

Nothing here is contested. It is blocked only by ticket 04, because the `confirmed` flag and the
car-identity question decide whether the button works at all on Kevin's actual car.

## Verification

On the device: tap it on the real Jeep and get a real NHTSA answer. Then tap it with the phone in
airplane mode and confirm the failure is worded, not blank. Screenshot both.
