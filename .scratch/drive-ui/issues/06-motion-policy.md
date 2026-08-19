---
map: drive-ui
ticket: 06
title: What moves on a screen that has never moved
type: grilling
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# What moves on a screen that has never moved

## Question

Settled decision 1: **this screen's total ban on animation is stale.** `DrivingModeScreen`'s doc
says "No theatre... No boot sweep, no draw-in, no ambient cursor, no continuous animation anywhere
in this file", and six comments justify it by retired ticket 04's head-unit "ambient-motion ration".
CLAUDE.md lifted that in two places. The A25 runs animation scales at **1.0**, and per `MEMORY.md`
the mission-control motion vocabulary "has never been observed by anyone, on any device".

So the question is open for the first time. Decide:

1. **Does the needle sweep?** A gauge that jumps between poll ticks reads as broken; a gauge that
   eases between them reads as a gauge - but easing invents intermediate values the car never
   reported. **That is an honesty question, not a taste one**, and it is this map's version of the
   estimates rule. If the needle animates between two real readings, is it lying about the values in
   between?
2. **What is the tick-to-tick treatment at 1 Hz versus at 30 s?** These are different problems. A
   30-second jump wants no pretence of continuity; a 1 Hz update might.
3. **The pods and the mini-bar.** Settled decision 2: the mini-bar is a hand-rolled Canvas
   specifically to avoid `DeckMeter`'s `DRAW_IN_MS` fill. That reason is now re-openable.
4. **Anything ambient at all?** `FleetScreen`'s uplink sweep exists and is gated on
   `deckMotionEnabled` plus connection state. Does drive mode get an equivalent liveness signal, or
   is a driving screen exactly where ambient motion is worst?
5. **`deckMotionEnabled()` stays regardless** - it reads the OS animator scale and is a genuine
   accessibility path. Any motion decided here is gated on it, no exceptions.

## Answer

**Resolved 2026-08-16 by Kevin.** Status: resolved.

**Q18 - does the needle ease between two real readings? KEVIN OVERRULED THE RECOMMENDATION.**
Stark recommended no value interpolation ever, on the grounds that at ~2 Hz the screen would draw
~28 invented frames between every pair of real numbers, and that this is the estimates rule
(CLAUDE.md section 4 rule 5) applied to motion.

Kevin: **"18 > interpolate its ok. we are adults we know the gauges are slow."**

**Interpolation between real readings is ALLOWED.** The reasoning is legitimate and is recorded
rather than merely accepted: this is a personal app for two adults who know the bus is slow, the
CLAUDE.md safety amendment already draws the line at deceiving the user rather than at smoothing a
display, and every reading on this screen already carries **worded staleness** - so nothing is
hidden from the driver even when the motion is smooth.

**But the design direction Kevin gave in the same message largely dissolves the question.** Ticket
04's segmented-bar language is **discrete by construction** - a column of blocks cannot imply a
value between two segments the way a swept needle can. So the honest and the handsome answer turn
out to be the same one, and interpolation ends up applying to segment-level transitions rather than
to a continuously-swept pointer.

**The rest of ticket 06, per Stark's recommendations, unopposed:**
- **Q19 - something must move so that slow does not read as frozen.** A freshness signal on each
  poll tick. Motion that signals recency is honest; motion that asserts a value is the thing to
  watch.
- **Q20 - `DeckMeter` is still NOT reused for the pods**, but the reason has changed. It is no
  longer "no animation allowed" (that ban is dead, settled decision 1); it is that `DeckMeter` is a
  continuous fill and the pods are becoming segmented.
- **Q21 - one ambient liveness signal**, gated on `deckMotionEnabled()` AND on the link being live.
  A no-link screen does not animate.
- **`deckMotionEnabled()` stays regardless.** It reads the OS animator scale and is a live
  accessibility path.
