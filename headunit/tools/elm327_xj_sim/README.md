# XJ ELM327 emulator

Simulated OBD-II dongle for debugging Midnight AI without a car. Speaks the
ELM327 AT set + OBD modes 01/02/03/09 with a **1998 Jeep Cherokee XJ (4.0L,
ISO 9141-2)** profile - the profile Ircama's emulator doesn't ship. Listens on
TCP `:35000`, which is what the app's debug OBD path connects to
(`TcpTransport` -> `10.0.2.2:35000`).

## Run against an Android Studio emulator (AVD)

1. `python elm327_sim.py --drive` on this machine.
2. Launch the app on an AVD (not a physical head unit - `10.0.2.2` is a QEMU-only
   alias for the host loopback).
3. In-app: Settings -> Your car -> OBD adapter -> **Use OBD emulator (DEBUG)** (debug builds only).

The app runs its full handshake (`ATZ/ATE0/ATL0/ATSP0/ATI/0100/ATDPN`) then
polls the 9 telemetry PIDs every 30s. `--drive` makes rpm/speed/coolant/MAF vary
so trends, MPG math, and cold-start bursts get real motion.

## Flags

| Flag | Effect |
|---|---|
| `--drive` | rpm/speed/coolant/MAF vary over a ~90s idle->accel->cruise cycle |
| `--mil P0301,P0171` | MIL on with stored codes (drives readiness, mode-03, freeze frame) |
| `--no-vin` | mode-09 VIN -> `NO DATA` (realistic for a '98 ISO 9141-2 car) |
| `--drop 010C` | never answer that command (timeout / dropout testing) |
| `--flaky 4` | drop ~1 in 4 PID replies |
| `--truncate` | chop mode-01 data lines in half (malformed-frame testing) |
| `--port N` | change port (default 35000) |

## No AVD? Use the JVM twin

`app/src/test/java/com/kevin/midnightai/vehicle/ObdPipelineSimTest.kt` stands up
the same XJ profile as an in-process fake and drives the real `Elm327Io` framing
loop + real `ObdResponseParser` across the full command sequence. Runs with no
AVD and no hardware:

    ./gradlew testDebugUnitTest --tests "com.kevin.midnightai.vehicle.ObdPipelineSimTest"
