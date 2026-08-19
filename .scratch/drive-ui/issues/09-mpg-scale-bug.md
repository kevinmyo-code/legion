---
map: drive-ui
ticket: 09
title: Every mpg figure for the Jeep is wrong by roughly 1.9x
type: grilling
status: resolved
status-detail: "2026-08-16, Kevin - suppress until calibrated"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Every mpg figure for the Jeep is wrong by roughly 1.9x

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

## Answer

**Resolved 2026-08-16. Kevin's call: SUPPRESS mpg entirely until a fill-up calibrates it.**

### What the data actually showed - including where my first hypothesis was wrong

The opening suspicion was that sparse MAF sampling was starving the fuel integral: across all
history the Jeep has **166 `0110` samples against 945 `010D` samples**, and `MAX_CONSECUTIVE_FAILS
= 3` latches a failing PID off while speed is **permanently exempt** from that latch. Distance
counting while fuel stops would inflate mpg without bound.

**That is a real defect, but it is NOT what happened on this drive.** Reconstructed from a copy of
the A25's database:

| Check | Result |
|---|---|
| MAF coverage inside the drive window | **100%** - 130 speed, 130 rpm, 130 MAF |
| Hand re-integration of the raw samples | **29.0 mpg** against the 29.36 LEGION recorded |
| So the formula is | **faithful** - the code does exactly what it was told |
| MAF magnitude across the drive | median **4.15 g/s**, max 27.88 |
| Reference for a 4.0L I6 cruising | **15-25 g/s** (`reasoned`, engine theory, not measured) |

Netting out a 9-hour parked gap leaves **~73 minutes of driving for 20.95 miles - 17 mph average**,
which is city driving, where this engine does 14-15 mpg. That implies ~1.4 gallons against the
**0.723** LEGION computed: **a factor of ~1.9x.**

**Q1 answered: the input is the suspect, not the formula.** The 4.0L is speed-density, so the PCM
**synthesises** PID `0110` rather than measuring it, and a synthesised proxy need not carry a true
sensor's scaling. `tested` for the arithmetic, `reasoned` for the cause.

### The bigger finding, which was not what this ticket was opened for

**There is no drive boundary, and this record proves it.** The window spans **610 minutes** and
contains a single **9-hour gap** - two separate sessions that `MAX_DT_SEC = 90` stitched into one
"drive". Every recap, sparkline and `YearlyWrapped` figure reading `TRIP_MILES`/`MPG_TRIP` is
aggregating whatever accumulated between resets, not a drive.
[Trip content](05-trip-content.md) asserted a boundary object was needed; this is that claim with
evidence under it.

### Decisions

1. **Q5 - mpg is SUPPRESSED everywhere, not labelled.** Kevin chose suppression over the §4 rule 7
   "unverified" treatment that provisional ledger rows get. Six surfaces: `get_vehicle_data`'s
   `mpg` metric and its handler, the recaps `AVG MPG` chart, `YearlyWrapped`'s `Avg Mpg` row, the
   drive-history rows, the last-drive caption, and the DRIVES sparkline.
2. **`MPG_TRIP` keeps being computed and stored.** Suppression is display-only, behind one flag, so
   a correction factor can be applied retroactively and re-enabling is a one-line change.
3. **The voice tool must REFUSE with a reason**, never answer with a number.
4. **`finalizeDrive`'s gate is split** - `TRIP_MILES` is written on miles alone. Distance was being
   held hostage to fuel maths it does not depend on, so any drive with a silent MAF recorded no
   distance at all. This is [trip content](05-trip-content.md) Q17, owned here.
5. **Q2 - the only honest calibration is a tank-to-tank fill-up**, and it needs Kevin. Until then no
   correction factor may be invented; picking one that makes a single drive look right is exactly
   the wrong fix.

### Still open, deliberately not fixed here

- **The MAF fail-latch asymmetry.** Speed is exempt from `MAX_CONSECUTIVE_FAILS`, MAF is not, so
  fuel can stop accumulating while distance continues. Splitting the gate limits the damage; it does
  not fix the asymmetry.
- **The drive-boundary object**, owned by [trip content](05-trip-content.md).
- **Whether a MAP-based estimate off `010B` would be better** than a synthesised MAF. `010B` has
  **zero samples** in the database, so it is unknown whether this car even answers it.
