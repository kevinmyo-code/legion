# Pick one temperature unit and mean it

Type: grilling
Status: open
Blocked by: -

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
