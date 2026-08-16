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
| 6 | **The screen deliberately does not talk to the port**, "sending a second, independent command stream from this screen risks interleaving with that loop on the same wire" (`DrivingModeScreen.kt:75-85`). | [Live cadence](issues/03-live-cadence.md) may overturn this, but must answer the contention argument rather than ignore it - the mutex is real (`ObdBluetoothManager.kt:97`, `:813`). |

## Decisions so far

<!-- one line per closed ticket -->

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

- **Instantaneous mpg.** Wants MAF (`0110`) plus speed, and whether a 1998 XJ reports MAF usefully
  is unknown. Waits on [the bus reality](issues/01-bus-reality-research.md) establishing PID support.
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
