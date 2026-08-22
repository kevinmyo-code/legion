---
map: goal-plans
ticket: 01
title: "The doctrine the recommender reasons from"
type: research
status: resolved
status-detail: "2026-08-21 - findings + draft playbook; two numbers are genuinely contested"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The doctrine the recommender reasons from

## Question

Settled decision 4: **research happens once, offline, and becomes the shipped playbook text.** No
runtime web search. This is that research.

The output is prose that will live in a `PrimingTopic` alongside the existing BIO playbook - text
Kevin can read and edit on his phone, guarded by `requiredPhrases` so an edit cannot delete a safety
boundary.

Establish, from primary sources only (position stands, systematic reviews, government guidance -
never a fitness blog, never a supplement vendor):

1. **Energy targets for fat loss with muscle retention.** How a maintenance estimate is derived
   (Mifflin-St Jeor and the alternatives, and where each is known to be wrong), what deficit range
   the evidence supports for retaining lean mass, and what rate of loss is where the evidence stops
   supporting it.
2. **Protein.** g/kg bodyweight for fat loss with resistance training, and how that differs from
   maintenance or a surplus. Cite the position stands directly.
3. **Training volume.** Sets per muscle per week and where the dose-response curve flattens.
   Frequency. What the evidence says about training a muscle once versus twice weekly.
4. **Equipment-constrained substitution.** This one is specifically for Kevin's own example - *"i
   dont have access to a gym, can we mix in kettlebell workouts"*. What the evidence says about
   free-weight and bodyweight substitution for a machine-based programme, and which movement
   patterns genuinely have no good substitute.
5. **The boundaries this doctrine must NOT cross**, phrased as text that can become
   `requiredPhrases`: pain and injury, diagnosed medical conditions, disordered eating, minors,
   PEDs. The BIO playbook already carries these words - **read them and stay consistent rather than
   inventing a second phrasing.**

## Deliverable

Findings with per-claim URLs to `.scratch/goal-plans/research/01-doctrine.md`, tagged
`[position-stand]`, `[review]`, `[gov]` or `[not-established]`.

**Then a draft playbook**: the prose a recommender would actually be primed with. Terse, imperative,
and honest about uncertainty - the existing four playbooks are the model for tone and length.

**Where the evidence is genuinely contested, say so in the playbook itself** rather than picking a
side. A recommender that states a contested number as settled is the failure this whole map's
honesty rules exist to prevent.

## Answer - 2026-08-21

Full findings with per-claim citations: [`research/01-doctrine.md`](../research/01-doctrine.md).
The draft playbook is there to be lifted into a `PrimingTopic` by
[ticket 02](02-recommender-and-playbook.md).

### The finding that shapes the whole map

**Two of the numbers a recommender most wants to state confidently are genuinely contested**, and
neither is a rounding detail:

1. **The protein denominator.** The ISSN's 2.3-3.1 g/kg for hypocaloric periods traces back to a
   source that states it **per kg of LEAN mass**, inside a document whose other positions use total
   bodyweight. For a lean person the two are close; **at ~35% body fat they differ by about a
   third** - the gap between a sane target and an unreachable one.
2. **The rate of loss.** 0.5-1.0%/week is the recommended band, but the evidence for its top end
   comes from lean, resistance-trained athletes. Whether someone carrying more fat can safely lose
   faster is `[not-established]`.

Settled decision 5 said say-it-once rather than hedge forever. **These two are the exception**: they
are contested in the literature, not merely estimated, and the playbook states the disagreement
rather than picking a side.

### Two things it refuses to invent

- **There is no established point where added volume REVERSES hypertrophy.** The meta-regressions
  show flattening, not a downturn. Any specific "junk volume" number is secondary-source folklore,
  and the playbook says not to invent one.
- **Kettlebells have never been equated against barbell training for hypertrophy.** Kevin's own
  example turns out to have the weakest evidence base of anything asked about - so the playbook
  recommends them as reasonable and says plainly that no meta-analysis backs the equivalence.

### The substitution gaps are reasoned, not tested

Loaded knee flexion, vertical pull without an anchor, heavy hinge past the point bodyweight reaches
failure inside ~30 reps, and loaded calf/hip abduction. **Derived from the load-independence
finding's own precondition rather than from a trial that tested them**, and tagged that way in both
the findings and the playbook. A recommender claiming a home gym has no gaps would be overclaiming.

### The refusals have real sources behind them

The kidney one is the sharpest: **CKD guidance is 0.55-0.80 g/kg/day, which directly contradicts
every protein recommendation in the doctrine.** That is why it must be a refusal and a referral
rather than an adjusted number - the recommender has no business quietly halving a target it does
not understand the reason for.
