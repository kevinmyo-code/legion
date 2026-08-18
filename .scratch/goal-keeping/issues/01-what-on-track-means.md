# What "on track" actually means, per shape of goal

Type: grilling
Status: open
Blocked by: -

## Question

Every other ticket on this map depends on this one, because "kept on track" is undefined until
"on track" is.

`Goal` admits at least four shapes today, and they do not share an answer:

| Shape | Example | What the app can compute |
|---|---|---|
| Metric + target + deadline | "175 lbs by June" | Everything: current, trend, projection |
| Metric + target, no deadline | "save $30k" | Current and trend; "on track" has no time to be measured against |
| Deadline, no metric | "ship the deck by March" | Only whether the date has passed |
| Prose only | "be a better dad" | Nothing |

Settled decision 2 already fixes the prose case: ask, never judge. This ticket owes the other three
a definition each, and owes all four an answer to what "off track" means - which is the state that
actually triggers speech.

Decide:

1. **Is "on track" a boolean, a band, or a projection?** `GoalProgress.accumulationProgress` returns
   a raw fraction and deliberately does not clamp - a driver past the goal gets > 1.0. A boolean
   throws that away; a projection needs a rate, which needs history the app may not hold.
2. **What does a no-deadline goal measure against?** Without a date, "behind" is not computable at
   all. Either these goals get a derived pace, or they are honestly untrackable and fall to the
   check-in path with the prose goals.
3. **Direction.** `GoalProgress`'s doc says direction-ambiguous metrics never call it - "lose 10 lbs"
   is not accumulation. Does the goal store learn direction, and if so, does that need a schema
   change or does it live in `metricKey`'s convention the way `IngestMethod` widened without one?
4. **What counts as OFF track**, precisely enough that a deterministic rule could evaluate it. This
   is the sentence a trigger engine reads. If it cannot be written here, nothing downstream is
   honest.
5. **Does "on track" need to survive a revision?** The lineage means a goal can have gotten easier.
   A driver who moved the target from 175 to 185 is on track against the current row and not against
   the one he set in January. Which is the truth the app reports, and does it say the other one
   exists?
