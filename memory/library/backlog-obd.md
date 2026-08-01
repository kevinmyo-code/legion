# Backlog: OBD

> **STATUS: FROZEN ARCHIVE (banner added 2026-08-01).** This shelf is Midnight AI history: a
> head-unit car launcher with a commercial model and a city-pop design language. All three of
> those premises died in the 2026-07-30/31 pivot to LEGION. Nothing below governs LEGION.
> Read it as reference for why something was built the way it was. **Do not act on its
> blockers, sprints, backlog items, or hardware notes.** Live rules are in CLAUDE.md; live
> state is in memory/MEMORY.md. See CLAUDE.md §11.


OBD, telemetry, emulator harness, diagnostics dev tooling. Maintained by the librarian.

## OBD emulator harness (C2, 2026-07-08) — dev tool, no field verify needed

`ObdBluetoothManager`'s command/parse logic (`sendCommand`/`drainInput`/`readUntilPrompt`) already
only touched generic `InputStream`/`OutputStream`, so a transport seam was cheap: new
`vehicle/ObdTransport.kt` has the `ObdTransport` interface plus `RfcommTransport` (wraps the
existing real-dongle connect logic, unchanged behavior) and `TcpTransport` (connects to
`10.0.2.2:35000`, the Ircama ELM327-emulator's default, and `10.0.2.2` is the Android emulator's
alias for the host machine's loopback). Toggle: Settings -> Manage OBD -> "Use OBD emulator
(DEBUG)", gated behind `BuildConfig.DEBUG` so it's unreachable in a release build regardless of
the stored `DebugSettings.obdEmulatorEnabled` value. Flipping it calls
`ObdBluetoothManager.forceReconnect()` so the switch takes effect immediately.

**Usage note:** `10.0.2.2` is a QEMU-only alias that resolves to the host machine's loopback, it
only works running the app on an Android Studio emulator (AVD), not a real device or the physical
head unit. That's the easy path: run the Python ELM327-emulator (`ELM327-emulator` package, `-m
elm -n 35000`, optionally `-s car` for the Toyota Auris Hybrid scenario) on the dev machine, run
the app on an AVD, flip the toggle. Testing from the physical head unit instead would need either
the dev machine's real LAN IP (both on the same network) or `adb reverse` mapping a device port to
the host, neither is wired up yet, since the AVD path covers "test before a real drive" already.
Enables scripted failure-injection testing (truncation, timeouts, missing AT responses) the
emulator supports, without needing the Cherokee.

Built-in ELM327-emulator scenarios: `car` (Toyota Auris Hybrid, default), `mt05` (Delphi ECU,
motorbikes/ATVs), `default` (generic PIDs), `engineoff`. No 1998 Jeep Cherokee XJ scenario exists;
building one requires the `obd_dictionary` tool scanning a real ELM327 adapter against the actual
Cherokee, then `merge <module>` in the emulator CLI. For now, `scenario car` is close enough to
exercise parsing/tools/voice integration even though the specific PIDs differ from the XJ.

## BLE OBD ELM327 support (2026-07-12) — code done, pending Outlander validation

Bluetooth Low Energy (GATT) ELM327 dongle support added this session (commits dedce3d, 3b43c1c,
2d599c5 on main). Motivated by Kevin's personal FlyRoadTech dongle (HM-10 chipset, BLE-only, never
bonds, for iPhone/iOS use case). Architecture:

**New transport layer:** `vehicle/ObdTransport.kt` + `BleTransport` (was previously RFCOMM-only):
- `BleTransport`: HM-10-compatible GATT service discovery (primary: FFE0 service, FFE1 RX/TX
  characteristics), fallback to Nordic UART (NUS), generic fallback to detect-and-try. Wraps
  the existing command/parse logic (`sendCommand`/`drainInput`/`readUntilPrompt`) which is
  transport-agnostic.
- `RfcommTransport`: unchanged, wraps the existing real-dongle Bluetooth classic (RFCOMM) path.
- `TcpTransport`: unchanged, mirrors the OBD emulator harness (see above).

**Device registry persistence:** `ObdDeviceRegistry` per-MAC `isBle` boolean flag, persisted in
SharedPreferences (`obd_devices_BLE_${mac}` key pattern). Once a dongle is selected/paired, the
transport type is locked to that device's flag for future connections, no re-detection cost.

**Discovery UI:** Existing OBD picker integrated with BLE scan via merged `startDiscovery`:
- Collects both classic Bluetooth bonded/nearby devices + BLE scan results
- Displays both in the same picker, labeled "SELECT" (unpaired/active) vs "PAIR" (bonded, classic only)
- Transport type (BLE vs RFCOMM) is auto-detected on first SELECT or visually inferred from the device
  name pattern (BLE dongles like FlyRoadTech advertise "HM-10" or similar in their name)
- Per-MAC `isBle` flag commits to the choice after the first successful connection

**Builds/tests green:** gradlew assembleDebug + testDebugUnitTest pass; no device-specific
dependencies added (BleTransport uses framework-standard `BluetoothGattCallback`, no external
BLE library). Zero impact on RFCOMM code path or emulator path.

**Pending on-device validation (2026-07-12):** Outlander commute with FlyRoadTech BLE dongle
(Kevin's daily driver, [[hardware.md#Test vehicles]], phone-only test rig). Validation checklist:
- BLE scan completion + device advertisement display
- First connection (transport auto-detect or manual BLE select)
- Command/response flow over GATT (HM-10 characteristic write/read vs Nordic UART)
- Per-MAC flag persistence across app restart/reconnect
- Fallback behavior if scan times out or device disappears mid-session
- RFCOMM devices still work unmodified on the same picker (regression test)

## PENDING GRILL 2026-07-16 — Track + Drift modes (lap times, sessions, high-rate telemetry logs)

**UNRESOLVED FEATURE IDEA, NOT DECIDED. Kevin's dump, filed not grilled.** Track and drift modes in
the TrackAddict shape: lap timing, per-session records, high-rate OBD telemetry logs. Tribe fit is
excellent - [[../../CLAUDE.md]] §1's target is JDM / affordable-enthusiast, and track days are where
that tribe actually is. Two safety/strategy notes worth keeping: it is squarely §9.1-safe (anchored
to falsifiable reality - a lap time is either real or it isn't, the opposite of unfalsifiable memory
about the user), and it pushes drivers toward real-world community (track days, meetups), which §9.1
counts as a safety win, not just growth.

**The two hardware realities to settle BEFORE anyone scopes this. Both are already-known constraints,
not speculation:**

1. **The 30s telemetry cadence is useless for track, and speeding it up is a documented regression
   risk.** `TelemetryRecorder` runs at 30s (engine-on). Lap/track telemetry wants ~10Hz. But §12
   records that RPM and other fast-changing PIDs were REMOVED from Lights Out on 2026-07-12
   *because polling them stuttered phone Bluetooth music on the head unit's shared radio*. So
   high-rate OBD polling is in direct tension with an already-observed hardware failure on the
   primary target. B30's LIVE tab (15s poll) is also still unvalidated against A2DP for the same
   reason. **This is the blocker to answer first: does the radio allow it at all?** A track session
   with music off is a possible dodge - worth grilling, since nobody plays Bluetooth music on a hot
   lap anyway.
2. **Drift mode probably needs an IMU the head unit does not have.** Drift scoring needs yaw rate and
   slip angle, i.e. a gyro + accelerometer. Cheap AOSP 8-10 double-DINs frequently ship without any
   IMU. Same shape as the TPMS finding (see [[MEMORY.md]]): a good instinct with a hardware floor
   under it. **Check `SensorManager` for TYPE_GYROSCOPE / TYPE_ACCELEROMETER on the real unit before
   this gets planned.** If absent, drift mode is either dead or a BYO-hardware path.

**Other open questions for the grill:** (3) storage - `obd_samples` is ~18MB/yr at 30s; at 10Hz that
is ~300x, so a track log needs its own table/format and its own retention, NOT the existing
`obd_samples` pipe. (4) GPS rate - lap timing needs the position fix rate the head unit's GPS
actually delivers; cheap units are often 1Hz, and TrackAddict's own guidance is that 1Hz is not
enough (external 10Hz GPS is the norm). Unknown on the XJ rig; measure it. (5) Competition -
TrackAddict (HP Tuners) and Harry's LapTimer are mature and cheap-to-free; this is a crowded,
technically demanding niche where we would be the newcomer, and §1's "do not fight AlterGames on
features" logic likely applies here too. Our differentiator would be the same one as everywhere else:
nobody in it is art-directed, plus Zero noticing something in the session data.

**Priority note:** this is a big feature and it is NOT the critical path. ROADMAP.md's #1 hard truth
is that we are positioned on art that does not exist; ticket 05 and Zero's art come first. Filed for
later, deliberately.
