# Map: Drive UI

Label: `wayfinder:map`
Effort: `.scratch/drive-ui/`
Charted: 2026-08-16

## Destination

**The driving screen shows the car as it actually is, at the fastest cadence the bus genuinely
allows, and makes a DRIVE legible rather than three instantaneous numbers.** Decisions locked for:
poll cadence and who owns it, gauge design, trip-level content, the stale no-motion rule, the
temperature-unit split, and layout at the real screen size.

Destination is DECISIONS, same shape as `.scratch/hands-and-senses/` and `.scratch/android-auto/`.
Each cluster graduates its own build once its decisions land. Exception: [the temperature
unit](issues/07-temperature-units.md) is small enough that its ticket carries the build spec.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room v22), `com.kevin.legion`. Read
`CLAUDE.md` for rules and `memory/MEMORY.md` for state before deciding anything.

**Where this came from.** Kevin, 2026-08-16, after looking at the driving screen on the A25: "lets
make the driving screen better, i thnk rpm polling can be more often now that we run on a phone and
not the android head unit (midnight era) and the gauges can be improved too. the entire drive UI can
be improved."

### The premise check that reframed it - binding on every ticket

Run 2026-08-16 before charting. All `traced`.

| Assumption | Reality |
|---|---|
| The driving screen polls the car | **It makes ZERO OBD calls.** It reads Room - three `getLatest()` queries every 2s (`DrivingModeScreen.kt:179-181`). It renders the DATABASE. |
| RPM polling is slow because of the head unit | The screen's data is written by `TelemetryRecorder` at **`TICK_MS = 30_000L`**, and that value is justified by **storage and battery** ("~18 MB/year at this cadence"), not CPU. |
| The Midnight-era fast-PID ban is holding us back | It is **already dead code.** `ObdGauges.kt:10-13` bans RPM/load/MAF/throttle citing "starves the head unit's single Bluetooth radio... A2DP music stutter (field call 2026-07-12)" - and `ObdGauge` has **no callers anywhere**. `PidSpec.fast` has no consumers either. The ban was abandoned; nothing replaced it. |
| The phone is the constraint | **The car is.** Every command is `commandMutex`-serialised, one PID per round trip, **no batching**, 5000ms timeout. The 1998 XJ negotiates **ISO 9141-2 slow init**, which `ObdBluetoothManager.kt:780-784` says "routinely blew past the old 3000ms budget under bus contention". |

**So the honest headline: a large improvement over 30 seconds is available, but a smooth tachometer
probably is not.** How much is reachable is [ticket 01](issues/01-bus-reality-research.md)'s job to
establish and [ticket 02](issues/02-measure-the-bus.md)'s to measure. **No ticket may assume a
cadence before those two resolve.**

**Standing preferences for this effort (Kevin, 2026-08-16):**
- Kevin is at the abstraction layer. Bring him forks with real cost or taste; decide implementation
  without asking (`library/decisions.md`, 2026-08-16 altitude ruling).
- Estimates labelled as estimates, and **staleness said in words** - the current screen's
  `19 HOURS AGO` on every reading is correct behaviour and must survive any redesign.
- Nothing that requires a Kevin-hosted backend.
- **Install and look.** Every UI finding today came from a screenshot, not from the 1406 tests.

### Settled while charting (2026-08-16)

| # | Decision | Consequence |
|---|---|---|
| 1 | **The no-theatre rule on this screen is STALE, not binding.** `DrivingModeScreen` forbids all animation in six doc comments citing retired ticket 04's head-unit "ambient-motion ration". CLAUDE.md lifted that twice (sections 2 and 7). Animation scales on the A25 are **1.0**, and per `MEMORY.md` that motion "has never been observed by anyone, on any device". | [Motion policy](issues/06-motion-policy.md) decides what the screen does now. A sweeping needle has never been possible before and is now. **`deckMotionEnabled()` stays** - it reads the OS animator scale and is a live accessibility path, not a dead constraint. |
| 2 | **`DeckMeter` and `DeckPane` were deliberately NOT reused here**, and the reasons are on record: `DeckMeter` animates over `DRAW_IN_MS` (`DrivingModeScreen.kt:474-478`) and `DeckPane`'s header "fights a pod's centered label-above-giant-value read" (`:471-473`). | Both reasons are downstream of settled decision 1. Re-examine them, do not inherit them. |
| 3 | **There is no arc/radial gauge primitive in `ui/common/`.** The only one in the app is `DrivingDial`, `private` inside `DrivingModeScreen.kt:399`, raw `drawArc`. | [Gauge design](issues/04-gauge-design.md) decides whether it graduates into `ui/common/` or stays local. Do not write a second one. |
| 4 | **Four separate PID lists exist** and only coolant (`0105`) is in all of them: the screen's 3 (`DrivingModeScreen.kt:107-109`), TelemetryRecorder's 9 (inline literals, `:209-221`), the health monitor's 2, and `LIVE_GAUGE_PIDS` (`FleetRows.kt:72-79`). A fifth, `PID_REGISTRY`, is used only by the voice tool. | [Live cadence](issues/03-live-cadence.md) owns whether these consolidate. No other ticket invents a sixth list. |
| 5 | **Raising `TelemetryRecorder.TICK_MS` moves a contract other code depends on.** `isEngineRunning` is published from that loop and read by `AriaForegroundService` to decide whether to sync, and its accuracy "lags reality by at most one `TICK_MS`" (`TelemetryRecorder.kt:90-94`). | Any cadence change accounts for that lag explicitly. It is not a screen-local change. |
| 7 | **Batching is impossible on this car and ~2 Hz is the practical ceiling** ([ticket 01](issues/01-bus-reality-research.md), 2026-08-16). Multi-PID requests are defined only in ISO 15031-5's CAN clause; the ELM327 datasheet agrees. | **Binding on every ticket.** Each PID costs 150-250 ms linearly, so "add one more reading" is a cadence decision, not a layout one. **No design may imply smooth continuous motion** - see [motion policy](issues/06-motion-policy.md). |
| 6 | **The screen deliberately does not talk to the port**, "sending a second, independent command stream from this screen risks interleaving with that loop on the same wire" (`DrivingModeScreen.kt:75-85`). | [Live cadence](issues/03-live-cadence.md) may overturn this, but must answer the contention argument rather than ignore it - the mutex is real (`ObdBluetoothManager.kt:97`, `:813`). |

## Decisions so far

<!-- one line per closed ticket -->

- [What cadence can this car's bus actually sustain?](issues/01-bus-reality-research.md)
  — **~2 Hz, and batching is off the table.** Full findings with per-claim source labels in
  [research/01-bus-reality.md](research/01-bus-reality.md).
  **Multi-PID batching is CAN-only, settled twice independently**: ISO 15031-5 defines the optional
  PID#2-#6 rows only in its CAN clause (Table 127) and not in the ISO 9141-2 clause (Table 18), and
  the ELM327 datasheet says it in prose - "only if you connect to the vehicle with CAN". **One PID
  per round trip is a hard ceiling on this car; every ticket designs around it.**
  **Protocol floor is ~119 ms/PID (~8.4/s); realistic 150-250 ms**, so a 3-PID set lands at
  **~1.3-2.2 Hz today, ~2.4-2.7 Hz after tuning, against an unreachable 2.8 Hz floor.** Each extra
  PID costs 150-250 ms **linearly**.
  **One real unused lever exists**: the ELM327 **responses digit** (`010C1`, telling it to stop
  waiting after one reply) is NOT CAN-only and LEGION never uses it. `ATE0` saves ~1.3 ms and is
  hygiene, not a lever; adaptive timing defaults to AT1/ST=205 ms and is never overridden.
  **The MAF conclusion was FALSIFIED the same day** - see the correction on the ticket. It reasoned
  from the 4.0L being speed-density that `0110` would never answer; **Kevin's database has 166 MAF
  samples on that exact car**, so the PCM synthesises one. The mechanical claim was right and the
  conclusion drawn from it was wrong. `0104` is still the only near-mandatory PID in the set.
  Bluetooth is noise (<5%); the `Thread.sleep(20)` costs ~6% and is the cheapest millisecond
  available. **Biggest uncertainty, worth 2-3x on the number: how many ECUs answer, and the
  Chrysler PCM's actual P1/P2** - the standard delegates those to the manufacturer, which is
  precisely [ticket 02](issues/02-measure-the-bus.md)'s job.
  **Bottom line for the whole map: a live gauge on this car steps about twice a second. Any UI
  implying smooth continuous motion is lying about the bus.**

- [What moves on a screen that has never moved](issues/06-motion-policy.md)
  — **Kevin overruled the recommendation: interpolation is ALLOWED.** "interpolate its ok. we are
  adults we know the gauges are slow." Stark had argued no value-easing ever, since at ~2 Hz the
  screen invents ~28 frames between every pair of real readings. Kevin's counter stands on record:
  a personal app for two adults who know the bus is slow, with worded staleness on every reading.
  **The design direction then largely dissolved the question** - segment columns are discrete by
  construction and cannot imply a value between two readings, so the honest answer and the handsome
  one turned out to be the same. One liveness signal per tick; all motion gated on
  `deckMotionEnabled()` **and** on the link being live.
- [Pick one temperature unit and mean it](issues/07-temperature-units.md)
  — **Celsius, app-wide, rendered and spoken alike.** It already matches UPLINK and FAULTS, so
  DRIVE MODE's `177 F` is the outlier and moves. Distance and speed stay imperial - a deliberate
  mixed system, not an oversight. Conversion moves out of the call sites into one formatter,
  because each surface converting inline is exactly how three screens came to disagree.
- [What makes a DRIVE legible, not just a moment](issues/05-trip-content.md)
  — **Elapsed, distance, average mpg**, plus a post-drive summary on exit. **A drive needs a real
  boundary object** - today there is only a private local and day-granularity logs.
  **`finalizeDrive`'s gate must be split**: it currently writes no `TRIP_MILES` unless
  `gallons > 0.05`, so distance is held hostage to fuel maths it does not depend on. **mpg is not
  rendered live until [the scale bug](issues/09-mpg-scale-bug.md) resolves.**
- [What the gauges actually are](issues/04-gauge-design.md)
  — **Direction settled by Kevin with a reference photo**
  ([research/04-reference-dashboard.jpeg](research/04-reference-dashboard.jpeg)): "look at the bars
  and shit. retro futuristic vibes. think akira, think evangelion". **Segmented EQ-style columns
  replace the swept arc**, scale ticks printed on a grid behind the readout, coolant as a
  `COLD - HOT` fader, redline as orange caps that are scale annotation rather than state colour,
  amber-dominant, **dense and labelled rather than minimal**. **SPEED becomes primary, not RPM.**
  `RPM_SCALE_MAX` drops from 8000 to a per-vehicle ~5500, because a 4.0L XJ redlines near 5000 and
  the needle currently never leaves the bottom half. Prototype pending.
- [Layout for a phone that is 384 x 832](issues/08-layout.md)
  — **The dead third takes the trip block.** EXIT keeps no confirm (it is the only control; making
  it annoying is worse than a stray exit), the Alfred strip moves to thumb level above it, portrait
  only. Concrete layout lands with the prototype.

- [Every mpg figure for the Jeep is wrong by roughly 1.9x](issues/09-mpg-scale-bug.md)
  — **SUPPRESSED until a fill-up calibrates it** (Kevin's call, over the "label unverified"
  treatment provisional ledger rows get). Investigated against a copy of the real database, and
  **the first hypothesis was wrong**: sparse MAF sampling was suspected, but coverage inside the
  drive was **100%** and hand re-integration reproduced **29.0 mpg against the recorded 29.36** - so
  **the formula is faithful and the input is the suspect.** The engine is speed-density, so the PCM
  **synthesises** `0110` rather than measuring it, and the values run low: **median 4.15 g/s where a
  4.0L cruising should sit at 15-25.** Netting out a parked gap gives 17 mph average over 20.95
  miles - city driving, 14-15 mpg for this engine - against the 0.723 gallons LEGION computed:
  **~1.9x.** `MPG_TRIP` keeps being stored so a factor can be applied retroactively; suppression is
  display-only behind one flag. **`finalizeDrive`'s gate is split** so `TRIP_MILES` no longer
  depends on fuel maths - distance had been silently lost on any drive where MAF dropped out.
  **The bigger finding was incidental: there is no drive boundary.** That "drive" spans 610 minutes
  around a single 9-hour gap - two sessions `MAX_DT_SEC` stitched into one. Every recap and
  sparkline is aggregating between resets, not drives.

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Instantaneous mpg is BACK ON, but the number is wrong.** The no-MAF conclusion was falsified by
  Kevin's own data (166 `0110` samples on the Jeep). However the one finalised drive reads **29.4
  mpg**, and a 4.0L XJ does 15-18 - so LEGION's MAF integration is out by roughly 1.7x. Owned by
  [the mpg scale bug](issues/09-mpg-scale-bug.md); nothing renders live mpg until it resolves.

- **Landscape / mounted orientation.** The manifest is `unspecified` and the screen never touches
  orientation. Whether a phone in a vent mount wants a different layout waits on
  [layout](issues/08-layout.md) settling the portrait case first.
- **Night / low-light treatment.** The deck palette is already dark; whether driving at night wants
  something dimmer or higher-contrast is a real question and not yet sharp.
- **`obd_samples` retention.** 18 MB/year at 30s. If [live cadence](issues/03-live-cadence.md)
  raises the rate, retention and `purgeOlderThan` may need a policy. Cannot be specified until the
  cadence is chosen.
- **Whether drive mode ever auto-enters.** Today entry is always an offer and never self-triggered
  (`DrivingModeScreen.kt:63-65`). Auto-entry is a proactive raise and inherits
  `.scratch/hands-and-senses/issues/21-proactive-mode.md`'s rules, which are themselves unsettled.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **The Android Auto surface.** Owned by `.scratch/android-auto/`, including its open ticket 07.
- **Re-opening the phone-only pivot** or any head-unit accommodation. CLAUDE.md section 2.
- **Anything needing a backend**, a Kevin-hosted service, or comparative fleet data.
- **Replacing the ELM327 or the adapter.** Hardware purchase is not this map's business, even if
  [the bus reality](issues/01-bus-reality-research.md) finds the adapter is the limit.
