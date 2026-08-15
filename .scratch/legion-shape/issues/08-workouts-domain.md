# What is the workouts domain?

Type: grilling
Status: resolved (2026-08-07, Kevin)
Blocked by: 05

## Question

Does not exist in any form. Kevin: *"i want the ai to be able to make a plan, and i log my workouts
and my weight to it and see how far we have stuck to it or not etc."*

1. **What is a workout plan, as data?** Days, exercises, sets, reps, weights, progression? Or looser
   - "squat three times a week"? This is ticket 05's "target" in its most complicated coat, and the
   most likely to break a shared vocabulary.
2. **Who makes the plan.** Kevin wants the AI to. One-shot sub-agent, a conversation, or a template
   it fills in? A generated plan is a *reported* fact by ticket 02 - it is the model saying so.
3. **What is a logged workout?** Per set, per exercise, or per session? Voice-first means it must be
   sayable in one breath: "three sets of squats at 225" has to land somewhere sensible.
4. **Weight.** He named it here. Is bodyweight part of this domain, its own thing, or a plain
   reported measurement? Currently in the map's fog.
5. **What is the gap?** "How far we have stuck to it" - sessions completed against sessions planned,
   volume against planned volume, or something per-exercise?
6. **The first closed loop.** The smallest version that is genuinely useful: probably log by voice
   and ask "how am I doing this week" out loud. Say what is in it and what is deferred.

---

## Resolution (2026-08-07, Kevin - D20-D24)

**20. A plan is loose: exercises per week, with target sets.** Not a periodised program, no
progression model, no percentages of a one-rep max. Simple first.

**21. The AI writes the plan, and the plan is a REPORTED fact** - it is the model saying so. It is
also a *target*, which ticket 05 D3 places outside the tiers; the resolution is that the plan's
**existence** is a target, while any claim it makes about you ("your squat max is X") is reported.

**22. A logged workout is per SET.** "Three sets of squats at 225" has to land in one breath, because
voice is the primary way in. Per-session logging would force a second interaction.

**23. Bodyweight is its OWN thing**, a plain reported measurement with its own target later. Not a
field on the workout log. Kevin named them together; they are recorded separately because one is an
activity and the other is a measurement.

**24. The gap is sessions done versus sessions planned, this week.** Not volume, not tonnage, not
per-exercise adherence. Simple first; the richer gaps are a later want, not a hole in the design.
