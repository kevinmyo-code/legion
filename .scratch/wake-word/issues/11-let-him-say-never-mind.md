---
map: wake-word
ticket: "11"
title: "Let him call it off with his voice"
type: task
status: open
status-detail: "Kevin, 2026-08-20: 'then i wanna be able to verbally stop listening. oh nothing, i dont need you right now > yes sir, be here if you need me'."
blockers: ["10"]
blocked-by: ["[[10-acknowledge-the-wake]]"]
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
