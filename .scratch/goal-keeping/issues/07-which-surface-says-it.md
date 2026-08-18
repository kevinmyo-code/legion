# Which surface says it, and how it coordinates with the five categories

Type: grilling
Status: open
Blocked by: 04

## Question

Goal state has three possible homes and today it is in the middle one, mute: the GOALS panel renders
meters on four screens, the ALERTS pane already emits an ADVISORY row for an overdue goal, and
nothing is ever spoken.

Decide:

1. **Spoken, shown, or both** - and whether "both" means the same thing twice. A line spoken while
   driving and a row sitting on Today are different products with different failure modes.
2. **Which of the five categories a goal raise maps to.** `.scratch/proactive-mode/` settled that
   every raising ticket across every LEGION map maps its lines onto Safety, Timing, Wellbeing, Fleet
   or Digest, and that nobody invents a sixth switch. A goal nudge is plausibly Timing, Wellbeing or
   Digest depending on the goal, which is a problem worth naming now rather than at build time.
3. **What ALERTS does once goals can also speak.** Two surfaces reporting the same overdue goal, one
   silently and one aloud, is the temperature-unit bug in a new costume.
4. **Whether a goal raise can be answered.** `ProactiveBus.speakIfAllowed` opens a session and the
   line lands mid-conversation, so Kevin can reply - and ticket 03 wants that reply captured as a
   check-in. Say so explicitly or it will not happen.
5. **The driving case.** Most spoken interaction happens in the car. A goal nudge that only ever
   fires while driving is the car feature this map exists to stop building.
