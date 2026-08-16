# What moves on a screen that has never moved

Type: grilling
Status: open
Blocked by: -

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
