---
map: dumb-phone
title: "Dumb phone mode"
charted: 2026-10-22
charted-by: "Kevin + Opus"
effort: "`.scratch/dumb-phone/`"
tickets: 6
open: 6
status: open
tags: [map]
---

# Dumb phone mode

**Kevin, 2026-10-22:** help his ADHD. No social media, no doomscrolling; only Mia and his parents
reach him. A manual switch turns LEGION's HOME into a short text list, holds every other
notification, silences every other caller, and bounces hidden apps back home. Prototype
**A, text list** (`research/Main.dc.html`; leave flow `Leave.dc.html`, digest `Digest.dc.html`,
bounce `Bounce.dc.html`). ADR 0062.

## Destination

**Shipped, and a day lived in it on the A25.** Toggle from HOME or the quick-settings tile. HOME
becomes: clock, date, "dumb for 2h 14m", nine kept entries, the chosen people, a Leave button.
Leaving costs a 60 s wall-clock wait and a typed sentence. Chosen people get through on any app;
everything else is held and summarised on leave (app and count only, never content).

## Tickets

- 01 build: mode state, Home A, Leave flow, quick-settings tile, settings row, setup screen shell
- 02 build: notification hold, digest, people picker, DND policy
- 03 build: call screening
- 04 build: accessibility bounce
- 05 build: streak and time-in-dumb counter (grayscale was dropped, Kevin 2026-10-22)
- 06 build: Matrix code rain (Home A background, live wallpaper, screensaver)

## Open on hardware

setSilenceCall on Samsung's dialer, setBypassDnd honoured, the person matcher against real
WhatsApp and Messages notifications, the bounce, the code rain on the phone. `ROLE_CALL_SCREENING` displaces
Samsung Smart Call.
