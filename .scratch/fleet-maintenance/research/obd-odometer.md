# Research: can an ELM327 read the real odometer on a 1998 Jeep Cherokee (XJ)?

Ticket: `.scratch/fleet-maintenance/issues/03-reading-a-real-odometer-over-obd.md`
Researched: 2026-08-15
Tags on every claim: `sourced` (URL given), `field-report` (community/forum evidence, named as such,
NOT specification), `traced` (read in this repo's source), `reasoned` (derived, not verified).

## Verdict

**No. The dashboard odometer on a 1998 XJ cannot be read by a generic ELM327, by any PID, any mode,
or any header trick. It is not on a bus the ELM327 has a transceiver for.**

Two independent facts each close it on their own:

1. The XJ's odometer lives in the instrument cluster on Chrysler's **CCD-bus**, which occupies DLC
   **pins 3 and 11**. The ELM327 IC has physical interfaces for J1850 (bus+/bus-), ISO K/L line, and
   CAN only. There is no CCD (SAE J1567) transceiver in it, so pins 3 and 11 are not merely
   unsupported in software - they are unwired.
2. **The Cherokee kept CCD to the end of production** (1998 was the year most other Chrysler lines
   moved to PCI/J1850-VPW, which an ELM327 *can* speak). The XJ is on the wrong side of that split
   for its entire run.

No standard PID returns an odometer on this vintage either: **PID $A6 (Odometer) did not exist**;
CARB first mandated an odometer PID for **MY2019**.

Downstream consequence for ticket 10: there is no better source on the wire. Kevin's ruling
(manual reading wins, resets the baseline) is the ceiling of what this vehicle supports. Design
around drift, and disclose it.

---

## 1. PID $31 and PID $21 - required in 1998? And how do you see a reset?

### Neither is required, and neither is an odometer

The CARB OBD II regulation that governs a 1998 MY vehicle is **13 CCR 1968.1**. Its `(l) SIGNAL
ACCESS (1.0)` enumerates, exhaustively, what must be available to a generic scan tool:

> "calculated load value, diagnostic trouble codes, engine coolant temperature, fuel control system
> status (open loop, closed loop, other; if equipped with closed loop fuel control), fuel trim (if
> equipped), fuel pressure (if available), ignition timing advance (if equipped), intake air
> temperature (if equipped), manifold air pressure (if equipped), air flow rate from mass air flow
> meter (if equipped), engine RPM, throttle position sensor output value (if equipped), secondary
> air status (upstream, downstream, or atmosphere; if equipped), and vehicle speed (if equipped)."

**No distance parameter of any kind appears in that list.** `sourced`
[ww2.arb.ca.gov/.../regact/obdii/finreg.pdf](https://ww2.arb.ca.gov/sites/default/files/barcu/regact/obdii/finreg.pdf)
(section (l)(1.0); the same document's `(k)(2.0)` incorporates **SAE J1979 "June 1994 / July 1996"**
as the applicable revision, and `(k)(1.0)` names J1850, ISO 9141-2 and ISO 14230-4 as the permitted
link layers.)

So on a 1998 vehicle both $21 and $31 are optional. Support is discoverable only at runtime, by
reading the $00/$20 bitmaps, and **on ISO 9141-2 an unsupported PID gets no reply at all**:

> "For all protocols except ISO 14230-4, the ECU shall not respond to unsupported
> PID/OBDMID/TID/InfoType ranges unless subsequent ranges have a supported PID/OBDMID/TID/InfoType."

`sourced` SAE J1979-DA (Rev. OCT2011), Appendix A, Table A1 -
[e90post.com attachment (J1979DA_201110)](https://www.e90post.com/forums/attachment.php?attachmentid=1460101&d=1468796937)

Community sources put real-world $31 support at **"most 2006+ diesel and most 2008+ gasoline"** -
a decade after this truck. `field-report`
[csselectronics.com OBD2 PID overview](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979)

### PID $21 is disqualified by its own definition, not just by support

J1979-DA Table B27, verbatim:

> "Conditions for 'Distance traveled' counter:
> - reset to $0000 when MIL state changes from deactivated to activated;
> - **accumulate counts in km if MIL is activated (ON)**;
> - **do not change value while MIL is not activated (OFF)**;
> - reset to $0000 if diagnostic information is cleared either by service $04 or at least 40 warm-up
>   cycles without MIL activated;
> - do not wrap to $0000 if value is $FFFF."

`sourced` (same J1979-DA PDF). **$21 counts only while the check-engine light is lit.** On a healthy
car it is permanently 0. It is a "how long have you been ignoring this fault" counter, not a
distance source. Close this branch.

### PID $31 - reset detection, and why it still fails as an odometer

J1979-DA Table B37, verbatim:

> "This is distance accumulated since DTCs were cleared (**via external test equipment or possibly, a
> battery disconnect**). This PID is not associated with any particular DTC. It is simply an
> indication for I/M (Inspection/Maintenance) of the last time external test equipment was used to
> clear DTCs. **If greater than 65535 km has occurred, CLR_DIST shall remain at 65535 km and not wrap
> to zero.**"

`sourced` (same J1979-DA PDF). Three killers, in order:

| Problem | Consequence |
|---|---|
| A **battery disconnect** resets it | On a 28-year-old XJ, that is a routine event (winter storage, alternator work, a dead battery). The reset is indistinguishable from a scan-tool clear. |
| It **saturates at 65535 km** and does not wrap | ~40,700 mi. At 10k mi/yr it pins after four years and then reads a constant forever - silently, with no flag. |
| It is optional and probably absent | See above. |

**How a consumer detects the reset rather than reading a negative delta.** There is no reset flag.
The available methods, best to worst:

1. **Monotonicity.** $31 is defined as non-decreasing between clears. `newValue < lastValue` is a
   reset, full stop. It is the only detector that always works, and it is what any consumer must
   implement first. `reasoned`
2. **Corroborating counters.** $30 (warm-ups since DTCs cleared) and $4E (engine run time since DTCs
   cleared) are cleared by the *same* event and by the same wording ("via external test equipment or
   possibly a battery disconnect"). A drop in $31 accompanied by $30 and $4E also dropping is a
   confirmed clear; a drop in $31 alone is a corrupt read. `sourced` (J1979-DA Tables B36, B37, B59)
3. **Readiness monitors.** Service $01 PID $01 readiness bits all return to "not complete" after a
   clear. Slower and noisier, but independent. `reasoned`

Note the failure mode monotonicity cannot catch: **saturation**. Once $31 pins at 65535 the delta is
0 forever, which reads as "the car did not move", not as an error. A consumer must treat
`value == 65535` as *unusable*, not as *unchanged*. `reasoned`

Also note $30 and $4E saturate the same way (255 warm-ups, 65535 min), so detector 2 degrades on an
old vehicle exactly when you need it. `sourced` (J1979-DA Tables B36, B59)

---

## 2. Is the cluster odometer addressable at all? No.

### Where the number lives

On CCD-era Chrysler products the PCM does not hold an odometer. It computes a distance *increment*
and broadcasts it; the cluster integrates and stores it. The reverse-engineered CCD message tables
list:

- **ID `0x84` - "Injector pulse width and mileage increment", "PCM TO BCM MESSAGE | INCREMENT MILEAGE"**
- ID `0xCC` - "Mileage and target engine idle speed", "ACCUMULATED MILEAGE"
- ID `0xCE` - "Vehicle distance / odometer"
- ID `0x24` / `0xB4` - vehicle speed / VSS signal

`field-report` (reverse-engineered, not a Chrysler document)
[chryslerccdsci.wordpress.com/ccd-bus](https://chryslerccdsci.wordpress.com/ccd-bus/)

The author of that project, building a CAN-to-CCD translator to drive an XJ cluster, states it
plainly: *"Odometer is incremented by it listening to the DIST_INCR_ID (0x84) code. So the
microcontroller has to calculate the distance traveled since the last transmission based on speed and
time then send it using 0x84."* `field-report`
[naxja.org thread 1131637](https://naxja.org/threads/can-bus-to-ccd-bus-protocol-translator-chrysler-pcm-simulator.1131637/)

**This is the single most useful finding on the page and it cuts both ways.** The dashboard odometer
on this truck *is itself a speed-integration*, done by the PCM from the same VSS that feeds PID
`010D`, transmitted as increments the cluster accumulates. LEGION's estimator is not approximating a
fundamentally better measurement - it is approximating the same measurement at 1/many the sample
rate. See §4 for what that implies about which fallback to prefer.

### Why the ELM327 cannot reach it

- **CCD sits on DLC pins 3 and 11.** *"The CCD bus is on OBD2 connector pins 3 and 11... many testers
  don't have those pins, and even OBD-2 scanners often don't support them."* `field-report`
  [chryslerccdsci.wordpress.com](https://chryslerccdsci.wordpress.com/) /
  [OBD-I compatibility issues](https://chryslerccdsci.wordpress.com/2019/09/11/obd-i-compatibility-issues-and-solutions/)
- **The Cherokee never left CCD.** *"Chrysler's CCD network (7.8 Kbps) began to fade in the 1998 model
  year to be replaced by the PCI Bus (10.4-10.8 Kbps) on most models, but **the Cherokee held on to
  the CCD bus until the bitter end of its existence**."* `sourced` (trade press, not a spec)
  [fenderbender.com - All aboard Chrysler's PCI Bus](https://www.fenderbender.com/running-a-shop/operations/article/33024665/all-aboard-chryslers-pci-bus)
  This is the unlucky detail: had the XJ moved to PCI in 1998 like the rest of the line, PCI is
  J1850 VPW and the ELM327 speaks it (protocol 2).
- **The ELM327 has no CCD front end.** Its OBD-side pins are `J1850 Bus+`/`J1850 Bus-`,
  `ISO K (pin 21)` / `ISO L (pin 22)`, and `CAN Tx/Rx`. Its complete protocol list is J1850 PWM,
  J1850 VPW, ISO 9141-2, ISO 14230-4 (x2), ISO 15765-4 CAN (x4), J1939 CAN, and two user-defined
  **CAN** protocols. CCD (a differential 7.8 kbps bus, SAE J1567) is not among them and cannot be
  reached by a user-defined protocol, because both user slots are CAN. `sourced` ELM327 datasheet
  v2.0, "AT Command Descriptions - SP h" and the pin descriptions -
  [ELM327DS.pdf](https://cdn.sparkfun.com/assets/learn_tutorials/8/3/ELM327DS.pdf)
- **SCI is equally out of reach, for the same reason.** Chrysler's enhanced/PCM channel of this era is
  SCI (7812.5 or 62500 baud, proprietary command set, DRB-III territory), on its own DLC pins.
  *"Standard OBD2 scanner connectors are missing SCI-bus pins and only grant access to the CCD-bus and
  power pins."* `field-report`
  [chryslerccdsci.wordpress.com/sci-bus](https://chryslerccdsci.wordpress.com/sci-bus/) and the
  Tindie/PCBWay listings for the dedicated CCD/SCI scanner that exists precisely because generic
  tools cannot do this.

### Is there a Mode 22 path?

**No.** The ELM327 can *send* mode 22 (see §3), and the datasheet documents it, but three things have
to be true for it to return an odometer and none of them is:

1. The target module must be on the protocol the ELM327 negotiated. The cluster is not on the K-line.
2. Mode 22 (SAE J2190 "Enhanced E/E Diagnostic Test Modes") must be the manufacturer's enhanced
   protocol. Chrysler's enhanced access in 1998 is **SCI with a proprietary command set**, not a
   J2190/UDS-style `22 xx xx` request. `reasoned` from the SCI sources above.
3. You would need the PID number. Elm Electronics: *"The PIDs used with mode 22 are usually
   proprietary to each manufacturer and are generally not published widely, so you may have difficulty
   in determining the ones to use with your vehicle. Elm Electronics does not maintain lists of this
   information."* `sourced` (ELM327 datasheet, "Setting the Headers")

Do not spend more time here. A CCD transceiver is ~$30 of hardware and a different transport
entirely; it is a separate project, not a PID.

---

## 3. ELM327 command surface, and what failure looks like

### What LEGION sends today

`ObdBluetoothManager.kt:574-586` and `:834`: `ATZ` -> `ATE0` -> `ATL0` -> `ATSP0` -> `0100` ->
`ATDPN`. **No `ATH1`, no `ATSH` anywhere in the repo.** Header manipulation would be new code, not a
tweak. `traced`

### The sequence header manipulation would require

Straight from the datasheet's "Setting the Headers" section, `sourced`:

1. `AT SP 3` (or leave `ATSP0` and let it settle) - headers are best set *after* a protocol is live:
   *"If experimenting, it is not necessary but may be better to set the headers after a protocol is
   active."*
2. `AT H1` - turn header display on, then monitor to learn who is actually talking. *"By monitoring
   your system for a time with the headers turned on (AT H1), you can quickly learn the main addresses
   of the senders."*
3. `AT SH xx yy zz` - `xx` = priority/type, `yy` = receiver/target, `zz` = transmitter/source (`F1`
   is the customary scan-tool address). ISO 9141-2's default is `68 6A F1`; `6A` is the target you
   would change. There is also `AT SH xyz` (11-bit CAN convenience) and `AT SH ww xx yy zz` (29-bit
   CAN) - both irrelevant here.
4. `22 xx xx` for physical addressing, or a plain mode 01 request for the retargeted module.
5. `AT ST FF` if replies are slow: *"You may find that some requests, being of a low priority, may not
   be answered immediately, possibly causing a 'NO DATA' result. In these cases, you may want to
   adjust the timeout value, perhaps first trying the maximum (ie use AT ST FF)."*

The datasheet's own verdict on this whole exercise, verbatim: **"Many vehicles will simply not
support these extra addressing modes."** `sourced`

### The practical failure mode when a module cannot be reached

**`NO DATA`, in almost every case.** Not a timeout the caller sees, not garbage:

> "NO DATA - The IC waited for the period of time that was set by AT ST, and detected no response from
> the vehicle. It may be that the vehicle had no data to offer for that particular PID, that the mode
> requested was not supported, that the vehicle was attending to higher priority issues, or in the
> case of the CAN systems, the filter may have been set so that the response was ignored, even though
> one was sent."

`sourced` (ELM327 datasheet, Error Messages and Alerts). The adjacent messages, for completeness:
`UNABLE TO CONNECT` ("tried all of the available protocols, and could not detect a compatible one");
`BUS INIT: ...ERROR` (a forced protocol failed to initialize and no others will be tried);
`STOPPED` (the request was interrupted); `?` (command not understood). `sourced`

**`NO DATA` is not a distinguishing signal.** An unsupported PID, an unreachable module, an engine
that is off, and a dead K-line all produce it. `ObdResponseParser.FAILURE_MARKERS` already lumps
`NO DATA`, `UNABLE`, `STOPPED`, `CAN ERROR` and `BUS INIT` into one bucket, and its own doc comment
records that a dead K-line was answering with the init errors rather than `NO DATA`. `traced`
(`ObdResponseParser.kt:29-49`)

### The inherited defect (noted, not fixed here)

Per the ticket: `Elm327Io` polls `available()` and never blocks on `read()`, so a quiet link returns
`""` and reads as a healthy car. Any new PID request inherits this. Concretely for this work: a probe
for `0131` on a car that does not support it will come back empty and be indistinguishable from a
probe on a car whose adapter has gone silent - so a "does this vehicle support $31" capability check
built on today's transport **cannot produce a trustworthy negative**. It can only produce a
trustworthy positive (a well-formed `41 31 xx xx` reply). Design any probe to require the positive.
`traced` + `reasoned`, defect is `.scratch/android-auto/issues/13`.

---

## 4. How far off is 30-second-tick speed integration? Closer to 10% than to 1%.

**Answer for ticket 10's disclosure wording: treat it as a 5-15% undercount, not a 1% rounding
error.** Say "estimated" and say "reads low".

### The field evidence

Torque (the most-used OBD-II Android app) computes trip distance the same two ways LEGION does, and
its users report the same direction of error. From the developer's own forum: one user measured
**"approximately 10% error on average, sometimes reaching 20%"**, and crucially found the error
tracked how *often* the app got a speed sample - **"3.5% error"** on a screen displaying few PIDs
versus **"approximately 20%"** on a screen displaying many (which slows the poll loop). Another user
on an Acura CL reported **"5-15% lower than car odometer"**. The vehicle in question was polling
**"about 8 PIDs/sec"** on ISO 9141. `field-report`
[torque-bhp.com - Trip Distance No Longer Accurate](https://torque-bhp.com/community/main-forum/trip-distance-no-longer-accurate/)

That last detail is the alarming one for us. **LEGION samples speed once per 30 s - roughly 0.03 Hz
against Torque's ~8 Hz aggregate**, i.e. two to three orders of magnitude sparser than the
configuration that already produced 3.5%, and sparser still than the one that produced 20%. `traced`
(`TelemetryRecorder.TICK_MS = 30_000L`)

### Why it undercounts rather than scattering

Sampling alone is **not** the reason. A zero-order-hold sample taken at a phase uncorrelated with the
driving is an unbiased Monte-Carlo estimator of mean speed; its error is random and shrinks as
1/sqrt(N) over a month of ticks. If sampling granularity were the only term, a month of driving would
land inside ~1%. `reasoned`

The undercount comes from five one-directional losses, all of which survive averaging:

| # | Mechanism | Direction and rough size | Tag |
|---|---|---|---|
| 1 | **VSS low-speed deadband.** "The ECU typically disregards speed signals below a certain speed threshold (<= 3 km/h) to prevent false triggering from signal noise", and speeds of 0-4 km/h are **"35-45% of samples in urban trajectories"**. Creep in traffic reports as 0 and contributes no distance. | under, 1-3% urban | `sourced` [arxiv 2501.00242](https://arxiv.org/html/2501.00242) |
| 2 | **The first tick of every drive contributes zero.** `lastTickAt == 0L` so `dtSecDist = 0.0`, and `prevLoc` is null so the GPS branch cannot fire either. One full tick of travel per drive, gone. | under, ~30 s of travel per drive | `traced` (`:199-213`) |
| 3 | **The last partial tick before shutdown is lost.** Distance between the final tick and engine-off is never accumulated. | under, up to 30 s per drive | `traced` |
| 4 | **Skipped ticks are truncated, not interpolated.** `if (!isConnected \|\| ConversationState.isBusy) continue` skips accumulation, and when the loop resumes `dtSecDist` is capped at `MAX_DT_SEC = 90.0`. A 5-minute conversation while driving discards ~3.5 minutes of distance outright. | under, usage-dependent | `traced` (`:110`, `:56`, `:199`) |
| 5 | **Integer km/h.** PID `010D` is "1 km/h per bit"; if the ECU truncates rather than rounds, the bias is -0.5 km/h always. | under, ~1% at 45 km/h average | `sourced` (J1979-DA Table B14) + `reasoned` |

Mechanisms 2 and 3 dominate on short trips and are the reason "mixed driving" matters more than total
mileage. For a drive of D minutes there are 2D ticks and roughly 1-2 of them are lost, so the loss
fraction is ~1/(2D) to ~1/D: a 60-minute highway run loses ~1.7%, a 15-minute errand loses 3-7%, a
5-minute trip to the shops loses **10-20%**. A month of mostly short trips therefore lands in the
same 5-15% band the Torque users report, arriving there by a different route. `reasoned`

### One more failure that is worse than inaccuracy

`wanted("010D")` returns false once `failCounts["010D"] >= MAX_CONSECUTIVE_FAILS (3)`, and the counter
is only ever reset **inside `add()`, which is only called when the PID is requested**. Three
consecutive speed failures therefore latch PID `010D` off for the entire remaining life of the
`run()` coroutine - distance silently falls back to GPS-only, permanently, with no user-visible
signal. On a car with no GPS fix (which is the stated reason speed integration exists) that means
distance quietly stops accruing. `traced` (`:143-157`, `:165-168`)

This is not a ticket-03 fix, but ticket 10 cannot honestly disclose "estimated, reads low by ~10%"
while a latch exists that can take the estimate to zero without saying so.

---

## 5. GPS versus speed integration, and the 0.001-5.0 mile window

### When GPS wins

| Condition | Better source | Why |
|---|---|---|
| Continuous fix, open sky, steady motion | **GPS** | Measures ground truth; immune to mechanisms 1 and 5 above (no VSS deadband, no 1 km/h quantization). Net positional error 2-5 m. `field-report` [azuga.com](https://azuga.com/blog/gps-vs-odometer) |
| Non-stock tyre size | **depends - see below** | GPS is right about the ground; the VSS is right about the dashboard. |
| Tunnels, parking structures, urban canyon, cold start | **OBD speed** | GPS produces nothing, or produces a teleport. This is the case the fallback exists for. `traced` (`:182-198`) |
| Stopped with the engine running | **OBD speed** | Reports a clean 0. GPS jitters. See the floor discussion below. |
| Winding roads at 30 s sampling | **OBD speed** | Chord-vs-arc: summing straight lines between sparse fixes is a known underestimate of a curved path, worsening with both tortuosity and sampling interval. `sourced` [Rowcliffe 2012, Methods in Ecology and Evolution](https://besjournals.onlinelibrary.wiley.com/doi/10.1111/j.2041-210X.2012.00197.x) - animal-movement literature, but the geometry is identical. At 30 s a car at 60 km/h chords 500 m at a time; negligible on a highway, several percent through a grid of 90-degree turns. `reasoned` |

**The non-obvious call.** Because the XJ's dash odometer is itself the PCM integrating the same VSS
(§2), a tyre-size change moves the dash odometer and PID `010D` together and leaves GPS alone. Kevin's
rule is that a **manual dashboard reading wins and resets the baseline**. An estimator whose job is to
interpolate *between dashboard readings* should therefore prefer the source that shares the
dashboard's reference frame - **OBD speed - even though GPS is closer to physical truth.** Preferring
GPS makes the between-readings drift larger, not smaller, on any XJ that is not on stock tyres (which
is most of them). The current code prefers GPS. `reasoned` - flag this to ticket 10 as a decision, not
a bug.

### The acceptance window: it admits bad data at both ends, and rejects almost nothing

`MIN_TICK_MILES = 0.001`, `MAX_TICK_MILES = 5.0`, applied to the GPS chord only, with no reference to
`dtSecDist`. `traced` (`:64-65`, `:209`)

**Floor: 0.001 mi = 1.61 m.** Consumer GPS net error is 2-5 m `field-report`, so **stationary jitter
routinely clears the floor**. Sitting at a long light or idling in a driveway with the engine running
produces a stream of ticks each contributing a few metres of phantom distance - and it does so
*preferentially*, because the GPS branch takes precedence over the OBD branch, so the tick where
`010D` correctly says 0 km/h is exactly the tick where GPS noise gets counted instead. Order of
magnitude: 30 min/day of engine-on idling is ~60 ticks x ~4 m = ~240 m/day, ~7 km/month of pure
fiction. Not fatal, but it is the wrong sign and it partly masks the §4 undercount, which is worse
than either error alone. `reasoned`
- Fix shape: raise the floor to ~0.005 mi (8 m, ~1 km/h sustained over 30 s), or simply require
  `speedKmh > 0` before accepting a GPS chord.

**Ceiling: 5.0 mi.** Over a nominal 30 s tick that is 600 mph; over the 90 s `MAX_DT_SEC` cap, 200
mph. It therefore **never rejects legitimate driving** - so no, it does not throw away good data. But
it barely rejects bad data either: a 1-3 mile multipath jump or a cold fix landing after a short
blackout passes cleanly and injects miles that are never removed. `reasoned`
- Fix shape: make the ceiling a function of `dtSecDist` (e.g. 100 mph equivalent -> 0.83 mi at 30 s),
  or cross-check the chord against the concurrently sampled `010D` and reject when they disagree by
  more than ~2x.

**A related latent problem worth one line.** `lastLocation` is updated from `LocationController.state`
whenever it is non-null, with no check that the fix is *fresh*. If the controller holds a stale
cached Location across a gap, the chord spans an unknown interval while the window still judges it as
if it were one tick. `traced` (`:201-210`) + `reasoned`

---

## What this means for ticket 10

1. **There is no real odometer to read.** Stop designing around the possibility. `sourced`
2. **The estimator is the only automatic source, and it reads low - budget 5-15%, not 1%.** The
   disclosure must say the number is an estimate and that it undercounts. `field-report` + `reasoned`
3. **Prefer OBD speed over GPS for this specific purpose**, because the dashboard odometer that
   resets the baseline shares the VSS reference frame. `reasoned`
4. **Three fixes are cheap and change the error materially**: carry `dtSecDist` across skipped ticks
   instead of capping at 90 s; do not lose the first tick of a drive; raise the GPS floor and tie the
   ceiling to elapsed time. `reasoned`
5. **The `010D` fail-latch and the `Elm327Io` quiet-link bug can both take the estimate to zero
   silently.** An honest disclosure cannot be written until at least the silence is fixed. `traced`

---

## Assumptions ledger

| # | Claim | Tag | Source |
|---|---|---|---|
| 1 | 1998 XJ instrument cluster is on the CCD-bus; the Cherokee kept CCD for its whole run | `sourced` | [fenderbender.com](https://www.fenderbender.com/running-a-shop/operations/article/33024665/all-aboard-chryslers-pci-bus) (trade press) |
| 2 | CCD occupies DLC pins 3 and 11; generic OBD-II scanners lack/ignore them | `field-report` | [chryslerccdsci.wordpress.com](https://chryslerccdsci.wordpress.com/) |
| 3 | ELM327 supports exactly 12 protocols (J1850 PWM/VPW, ISO 9141-2, ISO 14230-4 x2, ISO 15765-4 x4, J1939, USER1/USER2 both CAN); its OBD pins are J1850 bus+/-, ISO K/L, CAN Tx/Rx | `sourced` | [ELM327DS.pdf](https://cdn.sparkfun.com/assets/learn_tutorials/8/3/ELM327DS.pdf), "SP h" + pin descriptions |
| 4 | Therefore the ELM327 has no CCD transceiver and cannot reach the cluster | `reasoned` | from 1-3 |
| 5 | CARB 1968.1 (l)(1.0) lists required generic-scan-tool signals and includes no distance parameter | `sourced` | [ww2.arb.ca.gov finreg.pdf](https://ww2.arb.ca.gov/sites/default/files/barcu/regact/obdii/finreg.pdf) |
| 6 | The J1979 revision incorporated for this era is June 1994 / July 1996 | `sourced` | same, (k)(2.0) |
| 7 | PID $21 accumulates only while the MIL is ON and does not change while OFF | `sourced` | [J1979-DA OCT2011 Table B27](https://www.e90post.com/forums/attachment.php?attachmentid=1460101&d=1468796937) |
| 8 | PID $31 resets on a scan-tool clear "or possibly a battery disconnect" and saturates at 65535 km without wrapping | `sourced` | J1979-DA Table B37 (same PDF) |
| 9 | $30 and $4E reset on the same event and saturate similarly, so they corroborate a reset | `sourced` | J1979-DA Tables B36, B59 (same PDF) |
| 10 | Monotonicity is the only always-available reset detector; saturation defeats it | `reasoned` | from 8-9 |
| 11 | On non-ISO-14230-4 protocols an ECU does not respond to unsupported PIDs | `sourced` | J1979-DA Appendix A, Table A1 (same PDF) |
| 12 | PID $A6 (Odometer) is a much later addition; CARB mandated an odometer PID from MY2019 | `field-report` | [Wikipedia OBD-II PIDs](https://en.wikipedia.org/wiki/OBD-II_PIDs); corroborating primary CARB text not located |
| 13 | Real-world $31 support is "most 2006+ diesel and most 2008+ gasoline" | `field-report` | [csselectronics.com](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979) |
| 14 | XJ odometer is incremented by CCD message 0x84 sent by the PCM; the cluster accumulates it | `field-report` | [naxja.org 1131637](https://naxja.org/threads/can-bus-to-ccd-bus-protocol-translator-chrysler-pcm-simulator.1131637/), [chryslerccdsci ccd-bus](https://chryslerccdsci.wordpress.com/ccd-bus/) - reverse-engineered, not a Chrysler document |
| 15 | Therefore the dash odometer is itself a PCM speed-integration off the same VSS as PID 010D | `reasoned` | from 14 |
| 16 | AT SH takes `xx yy zz` (priority / target / source), ISO default header is `68 6A F1`, AT H1 reveals sender addresses | `sourced` | ELM327 datasheet, "Setting the Headers" |
| 17 | "Many vehicles will simply not support these extra addressing modes"; mode 22 PIDs are proprietary and unpublished | `sourced` | ELM327 datasheet, "Setting the Headers" |
| 18 | Chrysler's enhanced channel in 1998 is SCI (7812.5 / 62500 baud, proprietary), not a J2190-style mode 22 on the K-line | `field-report` + `reasoned` | [chryslerccdsci sci-bus](https://chryslerccdsci.wordpress.com/sci-bus/) |
| 19 | Unreachable module / unsupported PID surfaces as `NO DATA` after the AT ST timeout, not a hang or garbage | `sourced` | ELM327 datasheet, "Error Messages and Alerts" |
| 20 | LEGION's init is ATZ/ATE0/ATL0/ATSP0/0100/ATDPN with no ATH or ATSH anywhere | `traced` | `ObdBluetoothManager.kt:574-586`, `:834` |
| 21 | `ObdResponseParser` collapses NO DATA / UNABLE / STOPPED / CAN ERROR / BUS INIT into one failure bucket | `traced` | `ObdResponseParser.kt:29-49` |
| 22 | Torque users report trip distance 5-15% (up to 20%) below the car odometer, worsening as the poll rate drops | `field-report` | [torque-bhp.com](https://torque-bhp.com/community/main-forum/trip-distance-no-longer-accurate/) |
| 23 | ECUs disregard wheel-speed below ~3 km/h; 0-4 km/h is 35-45% of urban samples | `sourced` | [arxiv 2501.00242](https://arxiv.org/html/2501.00242) |
| 24 | PID 010D is 1 km/h per bit and may be VSS-, calculated-, or bus-derived | `sourced` | J1979-DA Table B14 |
| 25 | First tick of every drive and the final partial tick contribute zero distance | `traced` | `TelemetryRecorder.kt:199-213` |
| 26 | Skipped ticks truncate to MAX_DT_SEC = 90 s rather than interpolating | `traced` | `TelemetryRecorder.kt:56`, `:110`, `:199` |
| 27 | Combined, these give 1.7% loss on a 60-min drive and 10-20% on a 5-min drive | `reasoned` | arithmetic from 25-26 |
| 28 | `wanted("010D")` latches off permanently after 3 consecutive failures within one `run()` | `traced` | `TelemetryRecorder.kt:143-157`, `:165-168` |
| 29 | Consumer GPS net error is 2-5 m, so the 1.61 m floor passes stationary jitter | `field-report` + `reasoned` | [azuga.com](https://azuga.com/blog/gps-vs-odometer) |
| 30 | Sparse point-sampling of a curved path underestimates distance, worsening with tortuosity and interval | `sourced` | [Rowcliffe 2012](https://besjournals.onlinelibrary.wiley.com/doi/10.1111/j.2041-210X.2012.00197.x) - analogous domain |
| 31 | The 5.0 mi ceiling equals 600 mph at 30 s / 200 mph at 90 s, so it rejects no legitimate driving | `reasoned` | arithmetic |
| 32 | Zero-order-hold sampling is unbiased in isolation; the undercount is entirely from the one-directional mechanisms listed | `reasoned` | not empirically verified against a drive-cycle trace |
| 33 | Preferring OBD speed over GPS reduces drift *against the dashboard odometer* on non-stock tyres | `reasoned` | from 15; not measured on Kevin's vehicle |
