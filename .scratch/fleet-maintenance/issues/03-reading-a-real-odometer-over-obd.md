# Can the adapter read a real odometer, instead of integrating speed?

Type: research
Status: claimed (research subagent fired at charting, 2026-08-15)

## Question

`TelemetryRecorder` estimates distance by **rectangular integration of instantaneous speed at
30-second ticks** (`TelemetryRecorder.kt:199-222`), falling back to that only when GPS produced
nothing usable. Its own comment admits it undercounts stop-and-go (`:196-198`). It only accumulates
while the adapter is connected, the engine is running (`rpm > 0`), and no conversation is in
progress (`:110-128`).

Kevin's ruling is that a manual reading always wins and resets the baseline. That makes the
estimator a between-readings interpolator, not a source of truth - but a better source may exist on
the wire, and if it does, ticket 10 should know before it designs around drift.

**Find out what a 1998 Jeep Cherokee with an ELM327 adapter can actually be asked for.**

## What to find

1. **Mode 01 PID 0x31, "Distance traveled since codes cleared".** Standard OBD-II, 2 bytes, km.
   - Is it required or optional on a 1998 vehicle? Was PID 0x31 in the OBD-II spec that far back?
   - It resets when DTCs are cleared. How does a consumer of it detect the reset rather than
     reading a large negative delta?
   - Same question for PID 0x21, "Distance traveled with MIL on".
2. **Whether the true dashboard odometer is readable at all.** On most vehicles the odometer lives
   in the instrument cluster, not the ECU, and is not exposed on the standard OBD-II PID set.
   - Is there a Mode 22 (or Chrysler-specific Mode 01 extension) request that returns the cluster
     odometer on a 1998 XJ?
   - 1998 Chrysler products used **SCI / J1850-adjacent** signalling before the CAN era. Can a
     generic ELM327 even address the cluster module, or does it only talk to the PCM?
   - If the answer is "no", say so plainly. That is a useful answer and it closes the question.
3. **What the ELM327 command set supports for any of the above.** `AT` command sequence, header
   manipulation (`AT SH`), whether a non-PCM module can be addressed, and the practical failure
   mode if it cannot (timeout? `NO DATA`? garbage?). LEGION's transport is `Elm327Io` /
   `ObdBluetoothManager`, and **there is a known open defect there**: `Elm327Io` polls `available()`
   and never blocks on `read()`, so a quiet link reads as a healthy car
   (`.scratch/android-auto/issues/13`). Any new PID request inherits that bug - note it, do not
   fix it here.
4. **Accuracy of speed integration as practised.** How far off does 30-second-tick integration
   typically run against a real odometer over a month of mixed driving? Order of magnitude is
   enough: is this a 1% problem or a 15% problem? Ticket 10's disclosure wording depends on which.
5. **Whether GPS is the better fallback anyway.** The recorder already prefers GPS distance when
   the fix is good (`:203-210`). Under what conditions does GPS distance beat speed integration,
   and does the current 0.001-5.0 mile per-tick acceptance window (`MIN_TICK_MILES`/
   `MAX_TICK_MILES`) throw away good data or admit bad data?

## Sources

SAE J1979 / ISO 15031-5 for the standard PID set, ELM327 datasheet for the command surface,
Chrysler/FCA technical documentation for anything vehicle-specific. Community sources acceptable for
(4) and for practical ELM327 behaviour, named as such.

## Output

`.scratch/fleet-maintenance/research/obd-odometer.md`, on a throwaway `research/` branch. Lead with
a one-line verdict on question 2 - **can the real odometer be read, yes or no** - because that is
the finding that changes ticket 10.
