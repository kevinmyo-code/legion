---
map: async-lookups
ticket: "08"
title: "Build: runs 5 and 6 follow-ups (echo floor learns the user, empty holding line nudge wording, clipped onset after a reply)"
type: build
status: open
status-detail: "Charted 2026-10-29 from A25 runs 5 and 6. Barge-in itself now fires (fix/barge-in-sustain, ADR 0064 amendment); these are the three things the reply_end lines showed next to it."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: runs 5 and 6 follow-ups

Kevin, 2026-10-29: *"enough of the testing. the voice is pretty solid now."* So these are built from the
captures, owe nothing on the phone until the next ordinary conversation, and must not change what a
run 6 conversation already does right.

## What the captures showed

1. **The echo floor learns the user's own voice.** `BargeInGate.learn()` feeds every window that
   does not qualify into the echo percentile. In run 6 the reply after a barge-in ("Understood.",
   40 windows) began while the user was still speaking: `echo_floor=9785 threshold=14677`, and the
   user's next words had `speech_windows=19 loud_windows=7 best_span=7/8`, one window short, at a
   threshold five times the one the fire before it had. For the first `LEARN_WINDOWS` (10) windows
   of every reply the floor is `DEFAULT_ECHO_FLOOR` 12,000 and the threshold 18,000, which on the
   A25 speaker in call mode is thirty times the real echo (every other run 5 and 6 reply learned
   400 to 550). Fix shape, to decide in the build: do not learn from a window while Silero says
   speech (`prob >= SPEECH_PROB`) in the first second after a fire or an `activity_end`; and seed
   the floor from the previous reply's learned percentile instead of 12,000 when one exists. Pin
   both with `BargeInGateTest` cases that replay the run 6 numbers.
2. **The empty-holding-line nudge makes the model apologise instead of act.** Run 6: the user asked
   for suggested events, the model said "Checking." with no tool call, `empty_holding_line` fired,
   and the model answered "My apologies, I'm ready for your next request." The user had to ask
   again. The nudge text in `HoldingLineWatch` / `StillCheckingNudge` should tell the model to run
   the lookup it implied, or answer, in this turn, and never to apologise or wait for a repeat.
   Presence test beside the existing nudge tests; the system-note wording must not name the
   assistant.
3. **A word that lands right after a reply drains reaches the model clipped.** Run 5: "stop" said
   as the reply ended arrived as "top" and the model called `end_conversation` on it. The normal
   (non-barge) start sends a 400 ms pre-roll; check whether the Silero start window itself eats the
   plosive, and whether `onLocalSpeech` during the drain tail can seed the ring earlier. Measure
   before changing: log the pre-roll bytes sent on a normal start next to the barge one.

## Verification

- `./gradlew compileDebugKotlin -Pnokey`, `./gradlew testDebugUnitTest` (JUnit XML totals, 0
  failures), `./gradlew detekt` (4 pre-existing), `python -m doctest tools/voice_audit.py`.
- The run 6 fire (prob 0.99, level 29,738, threshold 3,000) must still fire under the new learning
  rule, and the 192 ms backchannel must still not.
