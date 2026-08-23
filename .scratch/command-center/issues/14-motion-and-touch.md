---
map: command-center
ticket: "14"
title: "The app learns to move"
type: build
status: built
status-detail: "Built: motion tokens, route fade, press response, pane entrance, animateContentSize. Alarm panes never animate. Owes the on-phone look including animator-scale-0."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The app learns to move

Kevin, 2026-08-22: *"the UI still doesnt feel polished enough."* The screenshots show layouts that
are broadly right and an app that is COMPLETELY STATIC - no pressed feedback beyond the default
ripple, no entrance, no transition between routes. Static reads as unpolished even when the layout
is correct.

The frame-clock-only motion ban was a HEAD-UNIT constraint and is LIFTED (CLAUDE.md sec 7:
"Motion is NOT restricted anymore. Use normal Compose animation"). Nothing has used the permission
since it was granted.

## Build - a small vocabulary, applied everywhere, not per-screen flourishes

1. **Route transitions**: one fade-through (or slide-up for drilldowns) on the NavHost, defined
   once. No per-screen overrides.
2. **Pressed states**: Deck buttons and tappable rows get a uniform press response (scale 0.97 or
   surface shift, one spec in the Deck components).
3. **Pane entrance**: DeckPane content fades/slides in ONCE on first composition of a screen -
   subtle, fast (under 250ms), never on every recomposition, never staggered theatre.
4. **State changes animate**: checklist tick, tile value updates - animateContentSize /
   Crossfade where a value visibly swaps.
5. **The listening indicator** already breathes? Check; if not, the Tap-to-talk dot is the one
   place a gentle idle pulse is earned.

## Rules

- **One motion spec file** (durations, easings) in `ui/theme/` - screens consume tokens, never
  invent timings. Two screens with different fade durations is the drift this prevents.
- Respect reduced-motion: read `Settings.Global.ANIMATOR_DURATION_SCALE` semantics for free by
  using Compose animation APIs (they scale) - no hand-rolled clocks.
- Nothing moves that carries an ALARM state - a quarantine tag or safety row appears instantly.
- No motion on the driving screen (drive-ui ticket 12 owns that surface's rules).

## Verification

- Suite green both ways. docs_check no drift.
- On the phone: tab switches transition, presses respond, nothing pulses except the one earned
  dot, and animator scale 0 (developer setting) still renders everything instantly and correctly.

---

## The driving-screen question, ruled 2026-08-22

The NavHost fade applies to every destination including `DrivingModeScreen`, which carries a
standing **zero-theatre** rule (mission-control ticket 08). The build flagged this rather than
deciding it, and offered a one-line per-route exclusion.

**Kevin: "nah leave it."** The fade stays uniform.

Worth stating why this does not contradict ticket 08: zero-theatre governs what moves ON that
screen while driving - gauges, values, ambient decoration - because a glance costs road attention.
A 200ms fade as the screen ARRIVES is not something glanced at mid-drive; it is the transition
into the mode, before any driving attention is being spent on it. The rule about the screen's
contents is untouched, and nothing on that screen animates.
