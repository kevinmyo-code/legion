---
map: drive-ui
ticket: 07
title: Pick one temperature unit and mean it
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Pick one temperature unit and mean it

## Question

The same coolant reading renders three ways in three places, verified on-device 2026-08-16:

| Surface | Shows |
|---|---|
| FLEET, UPLINK live gauge | `81 C` |
| FLEET, FAULTS freeze frame | `45 C` |
| **DRIVE MODE pod** | **`177 F`** |

The voice surface converts too - `CarToolbelt.freezeHighlights` speaks Fahrenheit.

**This is partly self-inflicted and should be recorded as such.** On 2026-08-16 the FAULTS drilldown
was built converting to Fahrenheit, following the voice tool's convention; that was changed to
Celsius on the argument that "screens match screens" - without checking the driving screen, which is
the third screen and disagrees with both. The fix was right locally and asserted a consistency that
did not exist.

Decide:

1. **One unit, app-wide, or per-surface by intent?** Spoken output and rendered output could
   legitimately differ (a spoken "forty-five Celsius" versus a glanceable `45 C`), but three screens
   disagreeing is not a decision, it is drift.
2. **Which unit.** Kevin is in the US and every distance figure in the app is already imperial
   (odometer, DRIVES, recaps). Coolant in Celsius alongside miles is defensible but wants stating.
3. **Where the conversion lives.** Today each surface converts inline, which is exactly how three
   answers happened. One formatter, or a typed value that knows its own unit.
4. **Everything else with units.** Speed (mph on both screens today), pressure, MAF (g/s), voltage.
   Sweep for other silent disagreements while this is open rather than finding them one screenshot
   at a time.

**This ticket carries its own build spec** - it is small enough not to graduate a build effort.

## Answer

**Resolved 2026-08-16.** Stark's recommendations, put to Kevin in the 29-question blast and
unopposed. Status: resolved.

1. **One unit, app-wide, for rendered text.** Three screens disagreeing is drift, not a decision.
2. **Temperature is CELSIUS.** It already matches two of the three screens (UPLINK's live gauge and
   the FAULTS freeze frame) and the raw PID value, so it is the smallest correct change. **DRIVE
   MODE's `177 F` pod is the outlier and moves.**
3. **Distance and speed stay IMPERIAL** - mph and miles - matching the odometer, DRIVES, the recaps,
   and a US driver. A mixed system is deliberate here, not an oversight: Celsius temperature beside
   imperial distance is what the underlying PIDs and the user's own frame respectively call for.
4. **Spoken output must NOT differ from rendered output.** `CarToolbelt.freezeHighlights` currently
   speaks Fahrenheit and moves to Celsius with everything else, otherwise the assistant contradicts
   the screen out loud.
5. **The conversion moves out of the call sites.** Each surface converting inline is exactly how
   three answers happened; one formatter owns it.
6. **Sweep for other silent disagreements while this is open** - pressure, MAF (g/s), voltage,
   speed - rather than finding them one screenshot at a time.

**Recorded as Stark's own error, and the reason this ticket exists:** on 2026-08-16 the FAULTS
freeze frame was changed from Fahrenheit to Celsius on a "screens match screens" argument, without
checking DRIVE MODE - the third screen, which renders Fahrenheit. The fix was right locally and
asserted an app-wide consistency that did not exist.
