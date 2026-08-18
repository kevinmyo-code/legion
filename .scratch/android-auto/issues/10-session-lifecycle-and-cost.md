---
map: android-auto
ticket: 10
title: "How long does the call stay open, and what does that cost?"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# How long does the call stay open, and what does that cost?

**Unblocked 2026-08-13** (tickets 01 and 04 resolved). Two findings land directly on this ticket:
Telecom imposes **two hard 5-second budgets** - the notification after `addCall`, and remote-surface
callbacks - so **answer, hold and disconnect must never block on a Gemini round trip**; and Android
14 forbids starting a `microphone` foreground service from the background, with an active telecom
call **not** on the exemption list, which is the likeliest place the whole design fails.

## Question

A call is a session that stays open. `service/GeminiLiveSession` is a WebSocket to Gemini Live on
**Kevin's own BYO key**, and a commute is an hour. Nobody has yet said who ends it or what an open
session costs while nothing is being said. Ticket 01 says what the call surface's own lifecycle is
(does Telecom kill it, does the head unit end it); ticket 04 says whether the mic is even open the
whole time.

Decide:

1. **Who ends the call?** Kevin taps end, LEGION hangs up after N seconds of silence, the projection
   ending kills it, or it lives for the whole drive by design.
2. **Is the socket open the whole time, or reconnected per exchange?** Gemini Live is a persistent
   session with server VAD - keeping it open is the natural shape and also the expensive one. A
   tap-to-talk-per-question model closes between turns and costs less, at the price of latency and
   losing conversational context.
3. **What an idle open session actually costs.** This is a number nobody has, and the map should not
   decide on a guess. If it cannot be established from documentation, say so and name the measurement
   - `memory/MEMORY.md` already carries "digest token measurement" as deferred work, so this is not
   the only place that needs it.
4. **Reconnection.** A car drives through dead zones. When the socket drops mid-drive, does LEGION
   reconnect silently, say something, or end the call? A call UI showing "connected" over a dead
   socket is the exact silent-failure shape CLAUDE.md keeps warning about.
5. **Does the session survive unplugging?** The phone leaves the car with the session live. Does it
   continue on the phone, or die with the projection? (This is one of the fog items on the map; if it
   sharpens here, resolve it here.)
6. **Battery and heat.** An hour of open socket, mic, foreground service, OBD polling and projection
   on an OPPO A17k. Worth a stated expectation even if it can only be measured later.
7. **Does the existing `AriaForegroundService` host this, or does the `ConnectionService`?** The map
   lists "one service or two" as fog; if ticket 01's answer makes it obvious, graduate it here rather
   than leaving it.
