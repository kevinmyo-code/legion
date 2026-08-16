# What cadence can this car's bus actually sustain?

Type: research
Status: open
Blocked by: -

## Question

Every cadence decision on this map is guesswork until this resolves. Kevin's car is a **1998 Jeep
Cherokee (XJ)**, which `ObdBluetoothManager.kt:780-784` records as negotiating **ISO 9141-2 slow
init**. LEGION talks to it through an ELM327 over Bluetooth SPP, one PID per round trip, serialised
on `commandMutex`, with no batching anywhere in the codebase.

Establish, from ELM327 documentation, the OBD-II standards, and reputable technical sources:

1. **Is multi-PID batching even possible here?** A single request returning several PIDs
   (`01 0C 0D 05`) is widely described as a CAN-mode feature. Confirm or refute for **ISO 9141-2**
   specifically. If batching is CAN-only, that is a hard ceiling on this car and the whole map must
   design around one-PID-per-trip.
2. **Realistic round-trip time per PID on ISO 9141-2.** The protocol's own timing rules (inter-byte
   spacing, P1-P4 timings, bus initialisation) bound this independently of the adapter. Give the
   floor the protocol imposes, not a vendor's best case.
3. **What the ELM327 adds.** Its own processing latency, the effect of `ATE0` (echo off, already
   set), whether adaptive timing (`ATAT0/1/2`) is in use or available and what it changes, and
   whether `ATSP0` auto-search costs anything per command once settled.
4. **PID support on a 1998 XJ.** Which of the PIDs LEGION already asks for are plausibly supported
   on a pre-2001 Chrysler: `010C`, `010D`, `0105`, `0104`, `0110` (MAF), `012F`, `0106`, `0107`,
   `010F`. MAF matters specifically because instantaneous mpg depends on it.
5. **Bluetooth SPP contribution.** Whether the transport adds meaningful latency versus the bus
   itself, and whether `Elm327Io`'s byte-at-a-time read with a 20ms idle sleep
   (`Elm327Io.kt:43-59`) is a material cost at these speeds or noise against the bus.

Write findings to `research/01-bus-reality.md`, cite every claim to its source URL, then append the
Answer here and set Status: resolved.

**State plainly which claims are vendor marketing, which are the standard, and which are community
report.** A number from a forum is not the same as a number from ISO 9141-2.
