# What makes a DRIVE legible, not just a moment

Type: grilling
Status: open
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
