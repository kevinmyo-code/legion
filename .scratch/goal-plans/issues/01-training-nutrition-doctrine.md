---
map: goal-plans
ticket: 01
title: "The doctrine the recommender reasons from"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
