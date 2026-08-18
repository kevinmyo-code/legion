---
map: fleet-maintenance
ticket: 03
title: "Can the adapter read a real odometer, instead of integrating speed?"
type: research
status: resolved
status-detail: 2026-08-15
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Can the adapter read a real odometer, instead of integrating speed?

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

---

## Answer (2026-08-15)

**VERDICT: No.** The dashboard odometer on a 1998 XJ cannot be read by a generic ELM327. No PID, no
mode, no header trick. Full findings: [`research/obd-odometer.md`](../research/obd-odometer.md).

### Why it is a hard no - two independent kills

1. **The odometer lives on a bus the ELM327 has no transceiver for.** The XJ's cluster odometer is
   on Chrysler's **CCD bus, DLC pins 3 and 11**. The ELM327 IC's OBD pins are J1850 bus+/-, ISO
   K/L, and CAN Tx/Rx only. There is no CCD (J1567) transceiver in the part, and both
   user-definable protocol slots are CAN.
2. **The Cherokee is the unlucky model.** It kept CCD to the end of production while the rest of
   Chrysler moved to PCI (J1850 VPW) in 1998 - which the ELM327 *does* speak.

Also: **PID `$A6` (Odometer) did not exist in 1998.** CARB first mandated it for MY2019.

### The finding that actually changes ticket 10

**The dash odometer is itself a speed integration.** CCD message `0x84`, "PCM TO BCM | INCREMENT
MILEAGE": the PCM computes distance from the same VSS that feeds PID `010D`, and the cluster
accumulates it. LEGION is **not approximating a better measurement - it is computing the same one,
far more sparsely.**

Corollary, and it inverts a live code decision: **prefer OBD speed over GPS.** The estimator's
baseline is reset by a manual dash reading, so the estimator and the dash should share a reference
frame - and GPS does not share it. `TelemetryRecorder` currently prefers GPS (`:203-210`).
**Flagged as a decision to take, not as a bug.**

### The other PIDs are both disqualified

| PID | Verdict |
|---|---|
| `$21` distance with MIL on | **Disqualified by definition, not availability.** J1979-DA Table B27: accumulates *only while the MIL is ON*, and must "not change value while MIL is not activated". On a healthy car it is permanently 0 |
| `$31` distance since codes cleared | **Optional in 1998** - CARB 1968.1 (l)(1.0)'s required-signal list contains no distance parameter at all. Resets on a **battery disconnect**, and **saturates at 65535 km without wrapping** (~40,700 mi), which a monotonicity check cannot detect. Reset detection: monotonic decrease, corroborated by `$30`/`$4E` dropping together |

### Accuracy: a ~10% problem, not a 1% problem

Torque users report **5-15% under, up to 20%**, worsening as poll rate drops - and **LEGION samples
at 0.03 Hz against their ~8 Hz.** Short trips lose 10-20%.

Sampling alone is unbiased. The undercount comes from **five one-directional losses**, all of which
only ever lose miles:

1. VSS deadband at or below 3 km/h, and 0-4 km/h is 35-45% of urban samples (sourced)
2. The first tick of every drive contributes zero
3. The trailing tick is lost
4. `MAX_DT_SEC = 90` truncates conversation gaps - and the recorder is **gated off during
   conversation** (`:110`), so talking while driving is dead reckoning nobody is doing
5. Integer km/h resolution

**This is the number ticket 10 question 4 needed.** A 10% one-directional undercount over a month
is not a figure that can be rendered bare.

### The acceptance window is wrong at both ends

`MIN_TICK_MILES..MAX_TICK_MILES` = 0.001 to 5.0 miles **admits bad data at both ends and rejects
nothing useful**:

- **Floor = 1.61 m**, below typical 2-5 m GPS static jitter. So **idling with the engine on accrues
  phantom miles** - and the GPS branch takes precedence exactly when `010D` correctly reads 0.
- **Ceiling = 600 mph at 30 s**, so it catches only gross teleports. A 2-mile multipath jump passes.

### Two silent-zero paths, both new

1. **`wanted("010D")` latches off permanently after 3 consecutive failures within one `run()`** -
   `failCounts` only resets inside `add()`, which is only called when the PID is requested. On a
   car with no GPS fix, distance then stops accruing **with no signal at all**.
2. The known `Elm327Io` quiet-link defect (`.scratch/android-auto/issues/13`) means a capability
   probe for `0131` on today's transport can only produce a trustworthy **positive**, never a
   trustworthy negative.

### Consequences

| Ticket | Effect |
|---|---|
| 10 (odometer) | Question 5 **answered: no better source exists.** Integration stands, so the ticket is entirely about disclosure - and question 4 now has a number: **~10%, one-directional, always under.** Adds a new decision: switch the estimator to prefer OBD speed over GPS |
| 10 | The 0.001-5.0 window and the `wanted()` latch are both concrete defects to fix, not just disclosure |
| 03 | Closed. No follow-up research |

### Assumptions ledger

- `sourced`: the CCD bus assignment and pin numbers, the ELM327 transceiver set, PID `$A6`'s MY2019
  mandate, J1979-DA Table B27 on `$21`, CARB 1968.1's required-signal list, the `$31` saturation
  point, the VSS deadband share of urban samples.
- `reasoned`: the ~10% aggregate figure, assembled from community-reported ranges plus the five
  named loss mechanisms, adjusted for LEGION's much lower poll rate. Community sources are named
  as such in the research file.
- `traced`: the `wanted("010D")` latch and the acceptance-window arithmetic are read off LEGION's
  own source.
- Not verified by me directly: I am relaying a research subagent's report and have not personally
  read the J1979-DA tables or the ELM327 datasheet. The `traced` items above are the exception -
  those are this repo's code and are checkable here.
