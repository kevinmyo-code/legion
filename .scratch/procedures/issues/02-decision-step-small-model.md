---
map: procedures
ticket: "02"
title: "The decision step: what a small model may choose and how it is labelled"
type: grilling
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The decision step: what a small model may choose and how it is labelled

## Question

When a procedure needs judgment (e.g. "is this recall worth raising?"), what may the model do,
on which key, and how does the result say a model chose?

## Recommendation

- **The model picks one of the branch names the definition lists. It never writes a figure, a
  date, a name or a sentence the user hears.** Output is constrained JSON (`{"branch": <enum>}`);
  anything else counts as a failed step and the run takes the definition's `on_fail` branch.
- **On the household's own key**: `LEGION_GEMINI_KEY` via `ingest.statements.gemini_key()`
  (`server/ingest/statements.py:499`), model `GEMINI_MODEL` (`statements.py:89`,
  `gemini-3.5-flash-lite` today), HTTP the way `brief/news.py:391-404` does it. Key unset: the
  step fails in words ("no model is configured, so this step did not choose") and the run
  continues on `on_fail`.
- **Labelled twice**: the step trace stores `chosen_by = model` with the model name and the
  branch; the run's result text says "(a model chose to ...)" next to anything that branch
  produced. A deterministic `check` says nothing extra.
- **Tokens and an ESTIMATED cost per decision are stored** like `brief.models.Brief`
  (`cost_estimate_micro_usd`), labelled estimate.
- **None of the first procedures needs one.** Ship the kind, prove it with a test procedure, use
  it when a real procedure needs it.

## Open for Kevin

- Is a decision step allowed at all inside a procedure that also writes? Recommend **no** in v1:
  a model must not steer which write happens.
