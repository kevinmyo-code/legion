---
map: wake-word
ticket: "10"
title: "Say something back when the wake word fires"
type: grilling
status: resolved
status-detail: "RESOLVED 2026-08-20, verified by ear on the A25. Kevin: 'hey alfred, nevermind and no works as we expect.'"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer

**Kevin, 2026-08-20: ship it, and the false-trigger cost is accepted.** "i agree. false triggers cost
and i accept it." Option 1 - the model speaks first on a wake-initiated turn - since there is no
local TTS and Android's own voice is not the persona's.

### Why it was silent, found in the code rather than guessed

Cold start already sends a `GREETING_PROMPT`. The warm-resume path did not:

```kotlin
else -> {
    set(Phase.LISTENING, "Listening...")
    s.beginConversation(null)   // silent by construction
}
```

Kevin's socket was warm, so his trigger took the one branch that says nothing. Correct for a tap -
the screen visibly changes - and wrong for a voice trigger, which has no screen.

### Built

`WAKE_ACK_PROMPT` on all three paths that can open a wake turn: warm resume, cold start, and the
stale-warm fallback (a dead socket must not swallow the acknowledgement, since the driver still spoke
and still heard nothing). Carried by `EXTRA_FROM_WAKE_WORD` on the existing `ACTION_TALK` intent, so
every other sender - the strip, the Android Auto play button - is untouched and still silent.

**It acknowledges rather than greets, deliberately.** The prompt forbids greeting, asking what the
driver wants, or offering anything: they already know what they want to say, which is why they
called. Answering a question they had pre-empted would be worse than the silence.

### NOT yet verified by ear

Kevin confirmed "wake word works" after the install, but that was about the trigger. **Nobody has
reported hearing the acknowledgement**, and the same field session found the engine deaf in the Jeep
([Deaf in the Jeep, fine outside it](12-deaf-in-the-jeep.md)), so this stays `built` rather than
resolved until someone says they heard it.

### Verified by ear, 2026-08-20

Kevin, after using it: *"hey alfred, nevermind and no works as we expect."* The acknowledgement
speaks. `on-device`, not `reasoned`.
