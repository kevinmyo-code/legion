---
map: drive-ui
ticket: 02
title: Measure the real round trip on Kevin's car
type: task
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-bus-reality-research]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Measure the real round trip on Kevin's car

## Question

[The bus reality](01-bus-reality-research.md) gives the theory. This ticket gets the number for
**this car, this adapter, this phone**, because L10 says the spike beats the doc every time.

Nothing on this map may pick a cadence before this resolves.

**What to measure**, with the dongle connected and the engine running:

1. **Round-trip time per PID**, sampled enough times to give min / median / p95 - not one reading.
   At minimum `010C` (RPM), `010D` (speed), `0105` (coolant).
2. **The cost of N PIDs back to back**, to confirm it is linear and to expose any per-burst penalty.
3. **Whether the 5000ms timeout is ever approached** in normal running, or whether it only matters
   during protocol re-init.
4. **What contention actually costs.** Time a PID read while a voice turn is live, and while the
   health monitor's Mode 03 is in flight, to size the mutex argument in
   [live cadence](03-live-cadence.md) with real numbers rather than a worry.
5. **Whether MAF (`0110`) answers at all** on this car - it decides whether instantaneous mpg is
   even on the table.

**Delivery.** This needs the car, so it is HITL: Kevin drives or idles with the dongle in. The
instrumentation should surface on-screen rather than logcat - `car/CarProbeLog.kt` already exists
for exactly this, and the A25's logcat behaviour is only partly trusted. Consider extending
`ui/CarProbeScreen.kt` rather than building anything new.

Record the numbers in the Answer. They become the shared input every later ticket cites.
