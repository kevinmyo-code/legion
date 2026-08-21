---
map: wake-word
ticket: 10
title: "The wake word sometimes does not hear him on a drive"
type: bug
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The wake word sometimes does not hear him on a drive

## The report

Kevin, from a real drive, 2026-08-21: *"wake word sometimes weak (doesnt hear me)."* Otherwise
acceptable - it worked often enough that he called the feature usable.

Filed separately from
[the silent-audio bug](../proactive-mode/issues/13-silent-after-focus-loss.md) found in the same
drive, deliberately. That one was an audio OUTPUT focus trap and is fixed; **a fix to output must
never be credited with an input improvement it did not make.** Two symptoms in one report are not
one bug.

## What is NOT yet established

**Nothing.** There is no root cause here yet, and the honest state of this ticket is that it has a
symptom and no diagnosis. Do not start from a theory.

Candidates worth separating before anything is changed:

1. **Road and cabin noise** raising the floor past what the recogniser can pick out. Most likely, and
   the least interesting.
2. **Audio focus on the INPUT side.** The same drive proved focus loss was real and unhandled on
   output. Whether a loss also disturbs capture is unknown - `GeminiLiveSession`'s own listener doc
   was explicit that "nothing has yet shown focus loss is actually implicated in the *sometimes it
   doesn't hear me* reports". That sentence is still true; the output fix does not settle it.
3. **Mic contention with Spotify or the car's Bluetooth HFP profile**, which can hand the mic to the
   phone at a different sample rate.
4. **The recogniser's own threshold** being tuned for a quiet room.

## How to establish it, before touching anything

`WakeWordEngine` already logs. A drive with a log pull afterwards should show whether the engine was
listening at all during a miss, whether it heard audio and rejected it, or whether it never got the
microphone. **Those three are different bugs** and the fix for each is different.

`MidnightEvents.silentMicTurn` already exists for a related shape - a real conversational turn where
almost no audio was forwarded - and fires a retrievable non-fatal precisely because a breadcrumb
alone never gets pulled. Check whether it fired during the misses.

## The rule this ticket is under

`GeminiLiveSession`'s audio path carries a standing warning about its own delicacy, and the
2026-08-17 listener comment is the worked example of obeying it: instrument first, react once the
evidence exists. **That instrumentation is what made the silent-audio bug findable within minutes.**
Do the same here rather than tuning a threshold on a hunch.
