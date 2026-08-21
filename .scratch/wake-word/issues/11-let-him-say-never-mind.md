---
map: wake-word
ticket: "11"
title: "Let him call it off with his voice"
type: task
status: resolved
status-detail: "RESOLVED 2026-08-20, verified by voice on the A25 including the guardrail: dismissal ends the chat and an ordinary no does not."
blockers: ["10"]
blocked-by: ["[[10-weak-pickup-on-a-drive]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Let him call it off with his voice

## Question

The other half of the wake-word contract. Kevin: *"then i wanna be able to verbally stop listening.
> oh nothing, i dont need you right now > yes sir, be here if you need me."*

A turn opened by voice should be closeable by voice. Today the only way to end one is
`LiveSessionController.onTap()` a second time, or `session.stop()` - both of which need a hand and a
screen, which defeats the point of having called it with a wake word.

**There is no end-conversation tool.** A grep for `end_`, `stop_`, `goodbye` and `dismiss` across
`LiveToolbox`'s ~89 declarations returns nothing. The session ends by tap, or by whatever timeout
already governs it.

The work:

1. **A tool the live model can call** when the driver signals they are done - `LiveToolbox` is the
   place, and it is a small declaration. A tool handles arbitrary phrasing ("never mind", "nothing",
   "I'm good", "go away") without a phrase list to maintain, which local matching cannot.
2. **Ordering is the whole problem.** "Yes sir, I'll be here if you need me" must be SPOKEN before
   the session dies. A tool call that stops the session immediately cuts off the sign-off, and the
   driver hears the assistant get halfway through saying goodbye. Establish how the session
   sequences a final utterance against teardown, and prove it rather than assuming it.
3. **The sign-off is the persona's**, not a hardcoded string - the same `Personas` path everything
   else in-character uses.
4. **It must not fire on an ordinary "no".** "Do you want me to add that?" / "no" is not a dismissal,
   and a tool that ends the session there would be maddening. The tool description carries that
   distinction, and `CLAUDE.md` sec 4's posture applies: a tool that fires when it should not is
   worse than one that occasionally does not fire.

Blocked on [Say something back when the wake word fires](10-acknowledge-the-wake.md), which settles
what a wake-initiated turn sounds like at the opening end. The sign-off should match the register
that decision picks, and deciding them in the wrong order means writing the closing line twice.

## Answer

Built 2026-08-20. `end_conversation`, declared in `LiveToolbox` and handled in
`LiveSessionController` alongside the other session-scoped tools the toolbox returns null for -
the controller owns the session, so it is the only place that can end one.

### The ordering trap, solved by arming rather than firing

Stopping the session inside the tool handler would cut the sign-off off mid-word: **the model has not
spoken it yet when the tool returns.** So the tool only sets `dismissAfterTurn`, and
`LiveEvent.TurnComplete` in conversation mode consumes it. That is the one moment where "he has
finished speaking, hang up now" is true rather than hoped, and it is the same event the mic-reopen
already hangs off.

The flag also resets on every `onTap`. A stale one - armed by a turn the driver cut short before
TurnComplete - would hang up the NEXT conversation the instant the assistant finished its first
sentence, which would be indistinguishable from a bug.

### The guardrail is the description, and a test guards the description

The tool description carries the do-NOT-call case in words: not when the driver is answering "no" to
something the assistant asked, not when declining one suggestion, not when pausing to think. Only
when dismissing the assistant itself.

`LiveToolboxEndConversationTest` (4 tests) pins that the tool is declared exactly once, takes no
arguments, asks for a sign-off, and still carries the do-not-call wording. **It guards presence and
phrasing, never obedience** - the same honest limit `AriaBrainHonestyClauseTest` states about the
speech-honesty clause. Whether the live model actually declines to hang up on an ordinary "no" can
only be established by talking to it.

### NOT yet verified by voice

Installed on the A25 and unexercised. The three things that need a person: that the sign-off is
spoken in full before the session closes, that "never mind" ends it, and that an ordinary "no" does
not.

### Verified by voice, 2026-08-20

Kevin: *"hey alfred, nevermind and no works as we expect."* Both halves confirmed on the device -
"nevermind" ends the conversation after the sign-off, **and an ordinary "no" does not**. That second
half is the one that mattered: the tests could only ever pin the wording of the guardrail, never the
model's obedience to it, and this is the only kind of evidence that could close that gap.
