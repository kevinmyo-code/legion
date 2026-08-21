---
map: wake-word
ticket: "10"
title: "Say something back when the wake word fires"
type: grilling
status: open
status-detail: "Kevin, 2026-08-20, immediately after the first successful trigger: 'i do want a confirmation from the ai though, like hey alfred > at your service sir'."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Say something back when the wake word fires

## Question

Kevin said "hey alfred" on the A25, it went to listening, and **nothing said anything back**. His
words: *"i do want a confirmation from the ai though, like > hey alfred > at your service sir etc."*

Today `WakeWordEngine` broadcasts `ACTION_TALK`, which lands on `sessionController.onTap()` - the
identical path as tapping the strip. Tap-to-talk opens the mic and waits, because the driver looking
at a screen can SEE that it opened. **A voice trigger has no screen**, so silence is
indistinguishable from not having heard, which is the same not-knowing this whole map keeps running
into.

There is no local text-to-speech in LEGION. Every spoken line is Gemini Live in the persona's voice
(`speakOpener` -> `speakProactive`). So the choice is not "canned or generated", it is:

1. **The model speaks first on a wake-initiated turn.** A system line tells it to acknowledge
   briefly and then wait. Costs nothing extra - the session was opening anyway - and it is
   in-character, varied, and in the right voice. Latency is the session connect, which the driver
   already waits through.
2. **A fixed line from the persona**, like `Personas.greetings`, given an `acknowledgements` list.
   Instant and free, but it needs a voice to say it, and Android's TTS is not Alfred - a robotic
   line followed by the real voice is worse than silence.
3. **Something non-verbal** - an earcon, a tone. Cheapest and fastest, and says "I heard you"
   without pretending to be speech. Kevin asked for words, so this is the fallback, not the plan.

**The fork that actually matters, and why this is a grilling and not a task:**

> Today a false trigger is silent and costs nothing. **Make the assistant speak on every trigger and
> every false trigger becomes a Gemini call and a sentence said out loud in the room.**

That inverts the cost of getting it wrong, and it is exactly the thing
[How many false triggers is too many, and how would Kevin ever know?](07-false-triggers.md) has not
measured yet. Decide whether the acknowledgement waits on that number, or ships first and accepts
the risk.

Also to settle: does it acknowledge when the driver says nothing after? A "hey alfred" with no
follow-up should not leave a session open and billing.
