# Odometer: manual entry, drift, and what the estimate is worth

Type: grilling
Status: open
Blocked by: 01, 03

## Question

Every mileage-based maintenance interval on this map rests on one number:
`currentMileage = odometerBaseline + tripMilesSinceBaseline.roundToInt()`
(`VehicleController.kt:542-544`).

**Kevin has no way to set that baseline except by talking.** `setOdometer` has exactly one caller,
the voice tool `set_odometer` (`LiveToolbox.kt:1378`). `saveVehicleFacts` - the "AI Profile facts
form" its own doc describes (`VehicleController.kt:270-275`) - has **zero callers**; that form is
gone from `ui/`. The odometer appears on screen in exactly one place, read-only, in the CARS pane's
first row (`ui/FleetScreen.kt:271`, `:759`).

Kevin's ruling: **a manual reading always wins and resets the baseline.** Drift is discarded.

## How the estimate is produced

`TelemetryRecorder.run`, one loop, 30-second ticks (`TICK_MS = 30_000L`):

- Only accumulates when the adapter **is connected**, `rpm > 0`, and **no conversation is in
  progress** (`:110-128`). A long conversation while driving is dead reckoning nobody is doing.
- GPS distance first, accepted only inside `MIN_TICK_MILES..MAX_TICK_MILES` = 0.001 … 5.0 (`:203-210`)
- Otherwise **rectangular integration of instantaneous speed**:
  `tickMiles = speedKmh * dtSecDist / 3600.0 / KM_PER_MILE` (`:211-213`), with `dtSecDist` capped at
  `MAX_DT_SEC = 90.0`
- Its own comment admits this undercounts stop-and-go (`:196-198`)
- Persisted straight onto `Vehicle.tripMilesSinceBaseline` (`:214-222`)

`setOdometer` resets `tripMilesSinceBaseline` to `0.0` (`:123-130`), so Kevin's ruling is already
half-implemented - **the reset exists, the way to trigger it without speaking does not.**

Separately: daily miles in `DailyDriveLogController` aggregate `TRIP_MILES` samples, which are only
written when a drive exceeds `MIN_TRIP_MILES = 1.0` **and** `MIN_TRIP_GALLONS = 0.05`
(`TelemetryRecorder.kt:233`). **Two independent accumulations off the same per-tick figure**, with
different gates - so the odometer estimate and the fleet miles sparkline can legitimately disagree,
and nothing on screen explains why.

## What has to be decided

1. **Where manual entry lives.** A row on the rebuilt maintenance surface, the CARS pane, the
   specs screen, or its own sheet. It is the single highest-leverage number in the aspect and it is
   currently unreachable without voice.
2. **What the app does with the drift it is about to discard.** Kevin chose "manual wins, reset,
   discard" over "reset and show me the drift". **Take that as decided.** But the drift is free to
   compute at the moment of reset, and if the estimator turns out to be 15% low, that is worth
   knowing once. Decide whether it is logged (not shown), shown once at the moment of entry, or
   genuinely thrown away.
3. **How stale is too stale.** `odometerCheckInDue` (`:631-633`) and `lastOdometerPromptAt` already
   exist and drive a monthly spoken nag (`AriaForegroundService.kt:448-451`). Should staleness be
   **visible** rather than only spoken? A screen showing a 4-month-old baseline plus an estimate is
   saying something quite different from one showing a reading from Tuesday.
4. **What the number is labelled as.** It is an estimate between readings. §4 rule 5 says an
   estimate is labelled an estimate, in words. But the label cannot fire constantly or it becomes
   noise. **When does the estimate stop being trustworthy enough to render bare?** Ticket 03's
   accuracy finding (question 4 there) is the input.
5. **Whether a better source exists.** Ticket 03 asks whether a 1998 XJ exposes a real odometer or
   PID 0x31 distance-since-clear over an ELM327. If the answer is yes, this ticket changes shape
   substantially. If no, integration stands and this ticket is about disclosure.
6. **The two-accumulator divergence.** Do the odometer estimate and the daily-drive miles get
   reconciled, unified, or explicitly documented as different measurements? Doing nothing is
   acceptable; doing nothing **silently** is the thing this codebase keeps getting bitten by.
7. **Validation on entry.** An odometer only goes up. A typed reading below the current estimate
   is either a typo or evidence the estimator over-counted. A typed reading 40,000 miles above it is
   a typo. What does the form refuse, and what does it merely question?

## Known adjacent defect, do not fix here

`Elm327Io` polls `available()` and never blocks on `read()`, so a **quiet Bluetooth link reads as a
healthy car** (`.scratch/android-auto/issues/13`, `traced` not `tested`). That means the recorder
can believe it is connected while receiving nothing, and `rpm > 0` may be stale rather than live.
Note the interaction; that ticket owns the fix.

## Verification

On the device: set the odometer from the new affordance, force-stop, reopen, confirm it held. Pull
the DB and confirm `odometerBaseline`, `odometerBaselineAt` and a **zeroed** `tripMilesSinceBaseline`.
Then drive with the adapter connected and confirm the estimate moves at all - ticket 01 question 5
establishes whether it ever has.
