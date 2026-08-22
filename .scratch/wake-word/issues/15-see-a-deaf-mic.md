---
map: wake-word
ticket: 15
title: "Make a deaf microphone visible"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Make a deaf microphone visible

## The evidence this comes from

Two captures on the A25, 2026-08-22, one failing and one working. They are IDENTICAL up to the
moment of failure:

```
wake word releases the mic -> LIVE_TURN granted -> the ack turn speaks
-> ack turn ends with 0 bytes -> mic opens -> AudioRecord opens (VOICE_COMMUNICATION)
```

| | Working 12:57 | Failing 12:45 |
|---|---|---|
| After the mic opened | 118,400 bytes, heard *"Do I have anything today?"* | nothing, 17 seconds |
| Turn completed | yes | **never** |
| Error logged | none | **none** |

**Ruled out by evidence, not by argument:** the mic handoff (`MicArbiter` grants `LIVE_TURN`
cleanly), the wake word's release (`hadRecord=true`), `AudioRecord` construction (opens, no
throw), and platform silencing (the `isClientSilenced` callback exists and never fired).

**The failure is invisible.** `silent_mic_turn` already warns about a turn that FINISHES with zero
bytes - it fired correctly for the ack turn. Nothing at all covers a turn that NEVER finishes,
which is this bug. Server VAD ends a turn only after hearing speech begin, so a turn that never
ends means the server got silence. The mic is open, healthy, and deaf, and it hangs there forever
saying nothing.

## What to build

### 1. Signal level in the mic loop

Log the captured signal level periodically (about once a second, not per buffer) alongside the
running forwarded-byte count. RMS or peak amplitude computed from the PCM buffer already in hand.

**Levels only, never content.** This is a number describing loudness, not speech, and the
distinction is the whole reason this is safe to log at all. Do not log samples, and do not write
anything to Room - this is `Log`/`MidnightEvents` only.

### 2. A watchdog for a mic that is open and deaf

**The trap: "no bytes forwarded" is the WRONG trigger, and it is the obvious one.** A person who
triggers the wake word and then says nothing produces the same "no completed turn" as a broken
mic. Firing on that would nag the user for being quiet, which is both wrong and a compulsion-shaped
behaviour.

**The honest discriminator is the signal level.** A working mic in a silent room still returns room
noise - a small non-zero level. A deaf mic returns digital silence, at or near exactly zero. So:

- fire only when the mic has been open longer than a threshold **AND** the peak level over that
  whole window is pathologically near zero;
- a quiet room with a working mic must never trip it. **Say in the ticket's tests which case is
  which** rather than assuming a number is obviously right.

When it fires: record it (`MidnightEvents`), including how long and what the peak level was. Also
log `AudioRecord.recordingState` at that moment, since a stopped recorder and a deaf one are
different faults that look identical from outside.

### Recovery, deliberately scoped small

Reopening the `AudioRecord` once is a reasonable response and is where this should stop. **Do not
build a retry loop, and do not tell the user anything was fixed** - CLAUDE.md §7's outcome-verb
rule applies exactly here: nothing may claim recovery it did not observe. If a reopen happens, log
that it was attempted and log the level afterwards, so the NEXT capture answers whether it worked.

## Verification

- Suite green **both** ways: `./gradlew testDebugUnitTest` and `testDebugUnitTest -Pnokey`.
- The level calculation is a pure function over a PCM buffer, unit tested: digital silence, room
  noise, and speech-level input must be distinguishable.
- A test that a quiet-but-working mic does NOT trip the watchdog, and a digitally silent one does.
  These two tests are the ticket.
- On the phone: trigger the wake word and speak, confirm levels appear and the watchdog stays quiet.
  Then reproduce the failure if it recurs and confirm it is now visible in the log.
