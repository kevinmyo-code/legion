# Odometer: manual entry, drift, and what the estimate is worth

Type: grilling
Status: resolved (2026-08-15)
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
- GPS distance first, accepted only inside `MIN_TICK_MILES..MAX_TICK_MILES` = 0.001 ... 5.0 (`:203-210`)
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

---

## Answer (2026-08-15)

### The estimate is always labelled between readings

**Decision (Kevin).** Any mileage not taken from his own last typed reading says so, every time:

> `about 227,900 mi - estimated, last confirmed 3 days ago`

No threshold to tune, no window where a drifting number renders bare. §4 rule 5 applied without a
loophole, and ticket 03 supplies the reason it needs one: **the estimate runs ~5-15% low, always in
the same direction**, from five one-directional losses (VSS deadband at or below 3 km/h covering
35-45% of urban samples, the first tick of every drive, the lost trailing tick, `MAX_DT_SEC = 90`
truncating conversation gaps, integer km/h). A 10% one-directional error is not a figure that can be
shown bare.

The confirmed reading renders bare, because it is a fact Kevin stated. Only the estimate carries the
caveat, and it carries it **spoken as well as rendered**.

### The estimator now prefers OBD speed over GPS

**Decision (Kevin), and it reverses shipped behaviour** (`TelemetryRecorder.kt:203-213` takes GPS
first). Ticket 03's finding is the reason: CCD message `0x84`, "PCM TO BCM | INCREMENT MILEAGE" -
**the dash odometer is itself a PCM speed integration off the same VSS that feeds PID `010D`.**

So LEGION is not approximating a better measurement; it is computing the same one, more sparsely.
Preferring OBD speed puts the estimate and the manual reading that resets it **in the same
reference frame**, which GPS never shares. GPS becomes the fallback when `010D` is unavailable.

### Two live defects fixed here, both from ticket 03

1. **The acceptance floor admits phantom miles.** `MIN_TICK_MILES = 0.001` is **1.61 m**, below
   typical 2-5 m GPS static jitter - so idling with the engine running accrues distance, and the GPS
   branch took precedence exactly when `010D` correctly read 0. Preferring OBD speed removes most of
   it; raise the floor as well so the GPS fallback cannot do it either.
2. **`wanted("010D")` latches off permanently** after 3 consecutive failures in one `run()` -
   `failCounts` only resets inside `add()`, which is only called when the PID is requested. On a car
   with no GPS fix, distance then stops accruing **with no signal at all.** That is a silent zero,
   the defect class this map exists to close, and it is in the odometer's own supply line.

### Manual entry: one control, reused

**One place, not several.** The odometer is already displayed read-only on FLEET's CARS pane
(`FleetScreen.kt:271`, `:759`); the entry control lives there, beside it. Ticket 09's triage screen
and ticket 14's registration form **reuse that control** rather than re-implementing it - three
copies would be three places to get the estimate label wrong.

Today there is **no non-voice path at all**: `setOdometer` has one caller, the voice tool
(`LiveToolbox.kt:1378`), and `saveVehicleFacts` - the form its doc describes - has zero callers.

Validation, per the ticket's question 7: an odometer only goes up. A reading **below** the current
estimate is questioned, not refused, and the reply says why - the estimator running low is a real
possibility and Kevin's dash wins. `setOdometer`'s existing 100..999,999 bounds stay.

### Drift is discarded, but computed once

Kevin ruled at charting that a manual reading wins and the drift is discarded. Standing. But the
drift is free to compute at the moment of reset, and ticket 03 makes it the only evidence anyone
will ever have about whether the estimator works on this car: **log it, do not show it.** The one
place it may surface is a diagnostic, never a figure competing with the reading.

### The two accumulators stay separate, and that is now documented

`Vehicle.tripMilesSinceBaseline` and the `TRIP_MILES` samples feeding daily miles derive from the
same per-tick figure but persist through different gates (`MIN_TRIP_MILES = 1.0` **and**
`MIN_TRIP_GALLONS = 0.05`, `TelemetryRecorder.kt:233`). On Kevin's Jeep that produced **one
`TRIP_MILES` row against 938 speed samples**. They may legitimately disagree; **doing nothing is
acceptable, doing nothing silently is not.** Say so where both appear.

### Adjacent, not fixed here

`Elm327Io` polls `available()` and never blocks on `read()`, so a **quiet Bluetooth link reads as a
healthy car** (`.scratch/android-auto/issues/13`). The recorder can believe it is connected while
receiving nothing, and `rpm > 0` may be stale. That ticket owns it; note the interaction.

### Verification

1. Set the odometer from the new control; force-stop, reopen, **pull the database** - baseline set,
   `odometerBaselineAt` stamped, `tripMilesSinceBaseline` zeroed.
2. Confirm the estimate renders labelled and **speaks** the caveat.
3. Drive with the adapter connected and confirm `tripMilesSinceBaseline` moves at all - **it has
   never moved on this car** (ticket 01: 938 speed samples, accumulator at 0.0).
4. Confirm an idling engine accrues no distance.
5. Enter a reading below the estimate and confirm it is questioned, not refused.

### Assumptions ledger

- `traced`: `TelemetryRecorder`'s GPS-first ordering, the 0.001-5.0 window, `MIN_TRIP_MILES`/
  `MIN_TRIP_GALLONS`, `setOdometer`'s single caller, `saveVehicleFacts`' zero callers.
- `on-device`: 6,957 samples / 938 speed / one `TRIP_MILES` row / accumulator 0.0 on the Jeep.
- `sourced` (via ticket 03's research): the CCD `0x84` mechanism, the ~5-15% figure, the VSS
  deadband share, the `wanted()` latch.
- `reasoned`: that raising the acceptance floor removes the phantom-mile path. Not measured.
- **Not built.**

