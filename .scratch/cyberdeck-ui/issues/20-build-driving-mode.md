---
map: cyberdeck-ui
ticket: 20
title: "Build: driving mode"
type: task
status: resolved
status-detail: ""
blockers: ["13"]
blocked-by: ["[[13-build-shell]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: driving mode

## Question

Per ticket 11: offer on OBD connect (UPLINK panel + Alfred strip prompt, never auto), full-bleed
three-readout glance screen (giant type, live OBD values + Alfred voice-state line), one EXIT
key, instant exit on link drop, no dialogs, no theatre. Verify GlanceCardController/Phase
reusability at build time rather than assuming (L10). Keep-screen-on flag while active;
brightness/contrast checked in the ship pass.

## Answer

Built 2026-08-08 (coding agent, worktree, merged). DrivingModeScreen: full-bleed, no shell
chrome (LegionShell skips StatusLine + keys for the DRIVING route), three giant readouts (RPM,
coolant, Alfred phase line via the strip's own phaseLabel), values from the same 2s DB-poll the
UPLINK panel uses (deliberately NOT raw OBD commands - TelemetryRecorder owns the socket, no
visible mutex; traced), worded staleness, keep-screen-on, one EXIT key + instant exit on link
drop, zero theatre. Entry: FleetScreen's DRIVE MODE row, now live. L10 verdicts recorded:
Phase/CompanionPhase reused; GlanceCardController read in full and deliberately NOT reused
(ephemeral voice-tool payload store, wrong lifecycle). compile + tests green (tested).
Deferred: Alfred-strip offer (strip has no generic action-tap mechanism today - named follow-up),
on-device daylight/keep-screen-on/link-drop checks to ticket 21.

## Amendment: manual override (Kevin, 2026-08-08, on seeing the deck)

"give button to enter drive mode even without dongle pairing." The DRIVE MODE row is now always
shown on UPLINK - wording tells the truth per link state ("available" vs "manual, no link") -
and the link-drop auto-exit is latched to entry state (enteredWithLink), so a no-link manual
entry is never insta-ejected by its own exit watch; EXIT key is the only way out in that case.
This amends ticket 11's "offer on OBD connect" trigger: connect still offers, but the entry no
longer REQUIRES a link.

## Amendment 2: cockpit hub (Kevin, 2026-08-08, approved mock)

"drive mode needs to look like a cockpit hub." Rebuilt to the approved mock
(claude.ai/code/artifact/99e48aa0-dcb6-429c-a9cc-760f...): HUD line, 270-degree Canvas arc dial
(RPM leading, speed fallback, dead NO LINK track), redline as a fixed gauge-scale marking in raw
DeckRed (annotation on the scale, not a state verdict - documented why it does not breach ticket
03), two bracket pods with mini-bars (COOLANT + whichever of RPM/speed the dial is not showing),
Alfred strip, outlined amber EXIT (replacing the old red fill - EXIT is a control, not a
failed-gate verdict). Pure geometry in DrivingDialMath.kt, 14 JUnit tests. All behavior
invariants kept (poll mechanism, staleness wording, enteredWithLink latch, keep-screen-on, no
theatre). Behavior delta: all three PIDs poll every tick. Visual check remains ticket 21's.
