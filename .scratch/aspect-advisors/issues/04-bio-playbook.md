# Research: the BIO coaching playbook

Type: research
Status: resolved

## Question

Assemble the evidence-based coaching framework the BIO advisor ships with: strength/hypertrophy
programming principles (progressive overload, volume landmarks, frequency, deload), protein and
calorie targets by goal (cut/maintain/gain), meal-plan construction heuristics, bodyweight-trend
interpretation (weekly averages, not daily noise), and sleep's interaction with training. Sources
must be licensing-clean to paraphrase into a shipped prompt (public research summaries,
government dietary guidelines, widely-published training principles - no verbatim copying of
paywalled programs). Deliverable: a distilled playbook draft in `research/bio-playbook.md` sized
to live inside a SubAgent brief (target: a few hundred lines max), with a sources list, flagging
anything that must be phrased as an estimate or carries a see-a-professional boundary.

## Answer

Playbook drafted at `../research/bio-playbook.md` (~190 lines), written as direct instructions to
the coach: PAG activity floor; hypertrophy programming (double progression, 10-19 sets/muscle/week
band, 0-3 RIR, frequency serves volume, reactive deloads only); goal table (cut 0.5-1%/wk at
1.6-2.2+ g/kg protein, gain 0.25-0.5%/wk at 1.4-2.0 g/kg, per-meal 0.25 g/kg); meal-plan
heuristics off the DGA plate pattern; weekly-average-only bodyweight reading with expected
water-shift artifacts; sleep as a first-order training variable. Ends with mandatory-estimate list
and hard see-a-professional stops (pain, medical conditions, disordered-eating signals ->
CrisisDetector, minors, PEDs).

Assumptions ledger:
- researched: ISSN protein and diets-and-body-composition position stands, PAG 2nd ed., DGA
  2020-2025 AMDRs/meal pattern, Schoenfeld 2019 frequency meta-analysis, Plotkin 2022 load-vs-rep
  progression, Coleman 2024 deload RCT, Saner 2020 sleep x MPS, pre-sleep protein reviews,
  Garthe 2011 loss-rate study, bodyweight-fluctuation literature (all via search summaries of
  open-access texts, not full-text reads).
- reasoned: the 0.25-0.5%/wk lean-gain band (literature gives ~1 lb/wk max as gradual; band
  tightened for a trained lifter); stall-diagnosis ordering; "repeatable beats optimal" adherence
  heuristic; the 2-week rule before adjusting calories.
- reasoned: licensing-clean claim rests on all sources being open-access or US-government works
  and only facts/ranges being restated; no full-text was reproduced.
