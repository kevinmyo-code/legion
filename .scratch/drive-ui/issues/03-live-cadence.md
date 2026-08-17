# Live cadence: how fast, and who owns the poll?

Type: grilling
Status: open - answers settled, BLOCKED on ticket 02 for the numbers
Blocked by: 01, 02

## Question

With the real numbers on the table, decide the architecture. This is the ticket the whole map hangs
on, and it is not a screen-local change.

Today: the screen reads Room every 2s; `TelemetryRecorder` writes to Room every 30s; the screen
therefore renders data up to 30 seconds stale even with a live link. Decide:

1. **Does the driving screen get a fast lane, and whose is it?** Three shapes:
   (a) `TelemetryRecorder` speeds up while drive mode is foreground and slows back after;
   (b) the screen owns its own read loop through the same `commandMutex`;
   (c) a third owner both defer to. Settled decision 6 says the screen deliberately does NOT talk to
   the port today, citing interleaving risk - **answer that argument, do not ignore it.**
2. **What cadence, per PID?** RPM and speed move every moment; coolant does not. A single tick rate
   for all three wastes the bus on a reading that changes once a minute. `PidSpec.fast` already
   exists as an unused property for exactly this and has no consumers.
3. **What does the extra data cost?** `obd_samples` is ~18 MB/year at 30s and already holds 18,645
   rows. At 1 Hz it is a different table. Does fast-lane data get **written at all**, or is it
   display-only and never persisted? Display-only is the cheap answer and it breaks nothing
   downstream - argue it.
4. **`isEngineRunning` (settled decision 5).** It is published off `TICK_MS` and `AriaForegroundService`
   uses it to decide whether to sync. Any cadence change moves that lag. Decide deliberately.
5. **Contention with voice.** `TelemetryRecorder` already skips its tick entirely when
   `ConversationState.isBusy`, "so PID reads never contend with voice on the mutex-guarded port". A
   fast lane makes that collision far more likely. What yields, and does the screen show it yielding
   rather than just freezing?
6. **The four PID lists (settled decision 4).** Consolidate or leave them. If consolidating, say
   what owns the canonical list.

## Answer (settled in principle; the numbers still wait on ticket 02)

**Stark's recommendations, put to Kevin 2026-08-16 in the 29-question blast, unopposed.** Recorded
now so the design can proceed, but **this ticket does not close until
[ticket 02](02-measure-the-bus.md) supplies real numbers** - Kevin does not have the car.

1. **`TelemetryRecorder` owns the fast lane.** It speeds up while drive mode is foreground and slows
   back after. One owner of the port; no second command stream, so settled decision 6's interleaving
   argument is answered rather than overridden.
2. **Per-PID tiers, not a uniform tick.** RPM and speed every cycle; coolant every tenth. Since each
   PID costs 150-250 ms **linearly** (ticket 01) and coolant changes once a minute, tiering nearly
   doubles the rate on the readings that actually move. `PidSpec.fast` already exists unused for
   exactly this.
3. **Fast-lane data is DISPLAY-ONLY and is not written to `obd_samples`.** 18 MB/year at 30 s
   becomes roughly 500 MB/year at 2 Hz, and nothing downstream reads it. This also keeps the
   `obd_samples` retention question out of the fog.
4. **`isEngineRunning` is DECOUPLED from the display cadence.** It gates Drive sync in
   `AriaForegroundService`; sync policy must not change because a screen is open (settled
   decision 5).
5. **Voice wins every collision, and the screen SAYS SO.** `TelemetryRecorder` already skips its
   tick entirely when `ConversationState.isBusy`; at 2 Hz that becomes common. Readings go
   stale-styled with worded cause, never silently frozen.
6. **Send the ELM327 responses digit (`010C1`) - but only behind ticket 02.** Ticket 01 found it is
   NOT CAN-only and is never used today, and it is worth most of the 1.3 -> 2.7 Hz gap. It asserts
   how many ECUs will answer, and asserting that wrongly loses replies. **Measure first.**
7. **The four PID lists consolidate** behind one canonical registry (settled decision 4). Build
   detail, not a taste call.
