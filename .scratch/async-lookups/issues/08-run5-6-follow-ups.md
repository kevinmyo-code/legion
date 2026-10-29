---
map: async-lookups
ticket: "08"
title: "Build: runs 5 and 6 follow-ups (echo floor learns the user, empty holding line nudge wording, clipped onset after a reply)"
type: build
status: built
status-detail: "Built 2026-10-29 on fix/async-lookups-08: the echo floor skips speech windows for 1 s after the user was last heard and seeds each reply from the last learned floor; both holding-line notes say act in this turn, never apologise or wait; a normal start sends 544 ms of pre-roll and every start logs a pre_roll_ms line. Owed on the phone: one ordinary conversation, reading reply_end echo_floor after a barge-in, the nudge reply after an empty holding line, and pre_roll_lead_ms on starts right after a drain."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Built, 2026-10-29 (`fix/async-lookups-08`)

1. **Echo floor.** `BargeInGate` skips learning a window Silero calls speech (>= 0.5) for 1 s
   (31 windows) after the user was last heard: its own fire, speech during the verdict watch, or
   `onUserTurnEnd()`, which `TurnTaker` calls beside `activityEnd`. The guard counts every window,
   played or not, so it runs on across the gap between replies; under server VAD only a fire arms
   it. Until a reply has learned 10 windows its floor is the last learned floor in the session;
   12,000 only before any. `BargeInGateTest` replays run 6: fire at 0.99 / 29,738 / 3,000, the next
   reply holds 500 while Kevin's 9,785 windows are skipped, and his next words fire; the contrast
   case without the guard lands on 9,785 / 14,677 and ratchets to 19,500. The 192 ms backchannel
   test is unchanged. ADR 0064 and `decisions.md` amended.
2. **Nudge wording.** `HoldingLineWatch.EMPTY_INSTRUCTION`: call the tool for the lookup implied, or
   answer from what it has, in this same turn; no tool, say so in one line and offer the nearest
   thing. Both holding-line notes add "Do not apologise, and do not wait for the user to ask again."
   Presence test in `HoldingLineWatchTest` (no assistant name, no em dash).
3. **Clipped onset.** Measured by reading, not on the phone. A normal start confirms on the 7th
   Silero window, 224 ms after the first at >= 0.5. The byte ring takes each 100 ms mic buffer
   (`CHUNK_BYTES` 3,200) whole before its windows are read, so 0 to ~100 ms of the pre-roll lies
   after the confirming window. 400 ms therefore reached only 76 to 176 ms before the first counted
   window. Right after a drain the policy is not fed while `playbackActive` is still true: up to
   20 ms poll and 60 ms settle in `awaitPlaybackDrained`, plus one buffer read under the old flag,
   about 180 ms in all. A word begun there ("s" of "stop") is counted only from the first window
   after, so its onset can sit about 500 ms before the confirmation. **400 ms could not hold it;
   `PRE_ROLL_MS` is now 544 ms** (the barge-in figure; the ring was already that size), reaching 220
   to 320 ms before the first counted window. `START_PROB` unchanged. Every start logs
   `LIVE_LATENCY pre_roll_ms= pre_roll_bytes= barge= pre_roll_onset_ms= pre_roll_tail_ms=
   pre_roll_lead_ms= [pre_roll_after_reply_ms=]`; `pre_roll_after_reply_ms` equal to
   `pre_roll_onset_ms` means the candidate began on the first window after the drain. Not changed:
   the drain tail itself (whether the AEC in call mode attenuates the user's first phoneme while
   the reply's last audio plays is not visible in code).
