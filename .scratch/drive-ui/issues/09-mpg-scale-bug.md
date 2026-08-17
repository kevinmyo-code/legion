# Every mpg figure for the Jeep is probably wrong by ~1.7x

Type: grilling
Status: open
Blocked by: -

## Question

Found 2026-08-16 by querying a copy of Kevin's real database, not by any test. Same shape as the
four ledger bugs of 2026-08-13 and the two found in the faults drilldown today: **each component
individually correct, wrong in aggregate.**

### What was measured

Against a copy pulled off the A25 (`vehicles` joined to `obd_samples`):

- `12:34:56:11:22:33` **is** the 1998 Jeep Cherokee.
- It carries **166 `0110` (MAF) samples** with plausible values - 2.28, 11.24, 15.68 g/s.
- It has exactly one finalised drive: **`TRIP_MILES` = 20.7 mi, `MPG_TRIP` = 29.4 mpg.**

**29.4 mpg is not a 4.0L straight-six XJ.** Real-world for that engine is roughly 15-18 mpg. The
figure is high by something close to 1.7x.

### Where it comes from

`TelemetryRecorder.kt:229`:

```kotlin
driveGallons += maf * dtSec / (AFR_GASOLINE * GRAMS_PER_GALLON)
```

with `AFR_GASOLINE = 14.7` and `GRAMS_PER_GALLON = 2801.0` (`:60-61`). That is the standard
MAF-integration formula and it is correct **for a real MAF sensor reading true mass airflow**.

The 4.0L is **speed-density** - it has no MAF sensor. The PCM is evidently synthesising a `0110`
value (which is why 166 samples exist at all, falsifying
[ticket 01](01-bus-reality-research.md)'s conclusion that `0110` would never answer). A synthesised
value need not be scaled the way a real MAF sensor's output is.

Decide:

1. **Is the MAF proxy the culprit, or the formula?** 1.7x is suspiciously close to several plausible
   unit or scaling errors. Establish which before touching a constant - guessing a fudge factor that
   happens to make one drive look right is exactly the wrong fix.
2. **Can it be calibrated honestly at all?** A tank-to-tank fill-up measurement is the only ground
   truth available, and Kevin would have to supply it. If it cannot be calibrated, mpg on this car
   is an **estimate of an estimate** and CLAUDE.md section 4 rule 5 governs how it may be spoken and
   rendered - or whether it should be shown at all.
3. **What about the other vehicles?** `imported-mitsubishi-outlander-2020` carries `MPG_TRIP` values
   of 35.4 and 22.6, which are plausible for that car - but those rows are IMPORTED, not measured by
   LEGION, so they say nothing about whether this code path is correct.
4. **What is already showing this number?** `MonthlyRecapController`, `DailyDriveLogController`,
   `YearlyWrapped`, the DRIVES pane's mpg sparkline, and the `get_vehicle_data` "mpg" voice tool all
   read `MPG_TRIP`. **Every one of them is currently reporting a figure that is probably wrong**, and
   none of them says so.
5. **Interim honesty.** Until it is fixed, does mpg get labelled unverified everywhere it appears,
   or suppressed? [Trip content](05-trip-content.md) already ruled it off the live driving screen.

### Related, and probably the same fix

`finalizeDrive` (`TelemetryRecorder.kt:309`) gates **both** `MPG_TRIP` **and** `TRIP_MILES` behind
`gallons > MIN_TRIP_GALLONS`. So on any drive where MAF is silent, LEGION records **no distance
either** - distance is held hostage to fuel maths it does not depend on.
[Trip content](05-trip-content.md) Q17 already ruled these must be split; this ticket owns the change.

**One finalised drive in the entire history** (11 `MPG_TRIP` rows across all vehicles, only one of
them the Jeep's) is itself worth explaining. A car driven regularly should have produced more.
