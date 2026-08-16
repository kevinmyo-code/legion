# Live cadence: how fast, and who owns the poll?

Type: grilling
Status: open
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
