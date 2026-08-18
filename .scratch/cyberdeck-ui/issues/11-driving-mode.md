---
map: cyberdeck-ui
ticket: 11
title: Driving mode
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Driving mode

## Question

Kevin (2026-08-07, during the shell resolution): "when im connected to an obd dongle (means im
in a car and driving) i should have the option to go into a driving style UI."

Decide: what the driving UI shows (live OBD readouts? glance-size numbers? Alfred front and
center?); how it triggers (OBD connect OFFERS it - "the option", not auto-switch - where does
the offer surface?); how it exits (disconnect? manual?); glanceability rules (type sizes,
touch targets, information ceiling - this is the one surface where LESS data is the design);
and what of the old head-unit/glance heritage is reusable (`service/GlanceCardController`,
`service/Phase`, the fleet OBD stack are all live code).

Safety framing: a driving UI's job is to need no looking. Voice remains the primary interface
while driving; the screen is a readout, not a control panel.

## Answer

Resolved 2026-08-08 by delegation (Kevin: "resolve them all with ur default recs").

1. **Trigger: an OFFER, never an auto-switch** (Kevin's own framing: "the option"). When the OBD
   link comes up, the offer surfaces in two places (per the FLEET resolution): the UPLINK panel
   and an Alfred strip prompt. One tap enters.
2. **Exit: one giant EXIT hard-key, or the link dropping.** Either returns instantly to the
   normal shell; no confirmation dialogs while driving, ever.
3. **Content: glance ceiling of THREE readouts.** Giant-type live OBD values (default: speed or
   RPM, coolant temp) plus one Alfred status line showing voice state. Full-bleed ground, huge
   touch targets, no lists, no charts, no stream, no navigation. The screen's job is to need no
   looking; voice remains the primary interface (§7 pull-based posture: ask Alfred, don't hunt).
4. **Heritage: `service/GlanceCardController` and `service/Phase` are candidate machinery**, to
   be verified at build time, not assumed (L10: run the real build).
5. Motion: driving mode gets NO theatre - it is exempt from even the three rationed moments.
   Brightness and legibility rules from the daylight-contrast fog apply doubly here.
