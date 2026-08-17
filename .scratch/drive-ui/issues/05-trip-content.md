# What makes a DRIVE legible, not just a moment

Type: grilling
Status: resolved (pending the mpg scale bug, ticket 09)
Blocked by: -

## Question

The screen shows three instantaneous readings and nothing that accumulates. Nothing on it tells you
anything about **the drive you are currently on**. Roughly a third of the screen is empty, which is
where this would go.

Decide what a drive-in-progress surfaces:

1. **The candidates.** Elapsed time, distance so far, average mpg this drive, instantaneous mpg,
   idle time, max speed, a fuel-trim watch, coolant trend. Which earn a place at a glance, and which
   are post-drive-only?
2. **What already exists.** `DailyDriveLogController` and `TelemetryRecorder` already aggregate
   `TRIP_MILES`/`MPG_TRIP` into `daily_drive_logs`, and the DRIVES pane already renders a summary.
   **Grep before proposing anything as new** - three tickets died on this map's sibling for exactly
   that reason. Does a live trip need new state, or is it a read of what is already accumulating?
3. **Where a "drive" begins and ends.** `DailyDriveLog` has no per-drive start/end timestamps (only
   day granularity), and `AriaForegroundService`'s drive monitor keeps `driveStartedAt` as a private
   local. If a live trip readout needs a drive boundary, something has to own it - and that is a
   data-model decision, not a UI one.
4. **Instantaneous vs average.** Instantaneous mpg is an estimate derived from MAF and speed and
   must be labelled one (CLAUDE.md section 4 rule 5). Average over a known distance is closer to a
   fact. Say which is shown and how each is worded.
5. **What happens with no link.** Every trip figure is unavailable or frozen. The current screen is
   scrupulous about worded staleness; trip content must be too.

## Answer (settled, pending the mpg scale bug)

**Stark's recommendations, put to Kevin 2026-08-16 in the 29-question blast, unopposed.**

- **Q13 - three trip figures: ELAPSED, DISTANCE, AVERAGE MPG.** Three, matching the glance ceiling.
- **Q14 - a drive needs a real boundary object.** Today there is only `driveStartedAt` as a private
  local in `AriaForegroundService` and day-granularity `daily_drive_logs`. Live trip content is
  impossible without one, and that is a data-model decision, not a UI one.
- **Q16 - exiting drive mode shows a post-drive summary.** That is where accumulated content
  belongs; it costs no glance budget.
- **Q17 - `finalizeDrive`'s gate must be SPLIT.** It currently writes neither `MPG_TRIP` **nor**
  `TRIP_MILES` unless `gallons > MIN_TRIP_GALLONS` (`TelemetryRecorder.kt:309`). Distance must not
  depend on fuel maths - a drive with a silent MAF currently logs no distance either.

### The mpg finding that changes Q15 - MEASURED, not reasoned

[Ticket 01](01-bus-reality-research.md) concluded the Jeep 4.0L is speed-density with no MAF sensor
and that `0110` would almost certainly never answer. **Kevin's own database falsifies that.** Query
run 2026-08-16 against a copy pulled off the A25:

- `12:34:56:11:22:33` **is** the 1998 Jeep Cherokee (confirmed against the `vehicles` table).
- It has **166 `0110` MAF samples** with plausible values (2.28, 11.24, 15.68 g/s).
- It has one finalised drive: **`TRIP_MILES` 20.7 mi, `MPG_TRIP` 29.4 mpg**.

So the PCM reports a calculated MAF even though the engine is speed-density mechanically, and
**instantaneous mpg is back on the table.**

**But 29.4 mpg is not a 4.0L XJ** - real-world is 15-18. LEGION derives gallons by integrating MAF
(`driveGallons += maf * dtSec / (AFR_GASOLINE * GRAMS_PER_GALLON)`, `TelemetryRecorder.kt:229`), so
**the mpg figure for this car is probably wrong by roughly 1.7x**, most likely because a calculated
MAF proxy needs a scale factor the integration does not apply.

**Q15 answered: do not show mpg live until that is fixed.** Shipping a figure known to be ~1.7x off
is the estimates rule violated outright. Filed as its own ticket -
[the mpg scale bug](09-mpg-scale-bug.md).
