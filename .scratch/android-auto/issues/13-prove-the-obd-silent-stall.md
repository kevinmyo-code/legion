---
map: android-auto
ticket: 13
title: Prove or kill the OBD silent-stall claim
type: task
status: kiv
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Prove or kill the OBD silent-stall claim

## Question

Ticket 05 traced a defect in shipped source: when the Bluetooth link to the ELM327 goes **quiet**
rather than being torn down, `Elm327Io.readUntilPrompt` polls `input.available()` and never blocks on
`read()`, so it returns `""`, which `isFailureResponse` treats as a car-side failure. LEGION then
runs its ISO 9141-2 K-line recovery ritual against a **Bluetooth** problem and leaves
`_connectionState` at `CONNECTED`. It reports the car as fine.

That claim is **`traced`, not `tested`** - read out of the source by a research agent, never
observed. `memory/MEMORY.md` is explicit that a relayed claim is not a verification, and that agents
have reported green while the build was failing. So it gets proved before anything is built on it.

This is experiment **T4** from the research findings. It is the cheapest of the four, **needs no head
unit**, and settles the central claim on its own.

Do:

1. Connect to the ELM327 over **RFCOMM**, start normal PID polling, confirm live data.
2. **Physically pull the dongle from the OBD port mid-poll.** Do not disconnect in software - the
   point is to produce a quiet link, not a torn-down socket.
3. Record exactly what LEGION does: what `sendCommand` returns, whether `consecutivePidSilence`
   climbs, whether `reinitProtocolLocked()` fires, what `_connectionState` reads afterwards, and what
   the UI tells Kevin the car is doing. **The device filters app logs (`memory/MEMORY.md`, OPPO
   A17k), so surface the diagnostic in the UI rather than trusting logcat.**
4. Repeat on the **BLE** transport, to check finding 6 - that `GattInputStream.closed` is written and
   never read, so a dropped GATT link is even quieter.

Outcomes:

- **Confirmed** - the claim is promoted to `tested`, and the fix (distinguish `""`, meaning nothing
  arrived on the socket, from `"NO DATA"` / `"BUS INIT: ERROR"`, meaning the adapter answered and the
  car did not) becomes a build ticket. It is local to `sendCommand`.
- **Not reproduced** - say so plainly and record what actually happened. A `traced` claim that does
  not survive contact is exactly what this ticket exists to catch, and the finding goes to
  `memory/library/lessons.md` either way.

**This is a defect in the fleet aspect, not an Android Auto feature.** It sits on this map because
this map surfaced it, and because a car surface whose headline value is live fleet data cannot be
designed on top of a stall that reports success. If it is confirmed, the fix should not wait for the
rest of the map.
