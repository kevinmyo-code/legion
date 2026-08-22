---
map: hands-and-senses
ticket: 27
title: "Measure which voice capabilities have no hands path"
type: task
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Measure which voice capabilities have no hands path

## Why this is a measurement first

ADR 0035 (Kevin, 2026-08-22): **anything LEGION can do by voice must also be doable by hand.**

The existing surface does not comply, and the size of the gap is **unknown**. 66 tools are declared
to the model. Some are plainly covered - `log_meal` has a Body screen, `manage_grocery` has a list.
Some plainly are not - `answer_call` has no button, which is the case that motivated the rule.
Most are unclassified, and nobody has looked.

**This repeats the socket lesson deliberately.** That ticket guessed 101 declarations and the truth
was 66; the guess was wrong in a way that would have changed what got built. A UI backlog argued
from a guessed number is the same mistake with a bigger budget attached.

## What to produce

A per-tool table: tool name, what capability it reaches, whether a hands path exists today, and
where. Three honest verdicts only:

- **covered** - a screen reaches the same capability, named with its file.
- **partial** - the data is visible but the ACTION is not (a value can be read on a screen but only
  changed by voice). This bucket matters most: it looks covered from a distance and is not.
- **none** - voice only.

**A tool that only observes and speaks is covered by the screen that already renders its data.**
`get_sitrep` and `ask_fleet` do not need a button; the rule is about capabilities, not prompts.

## What NOT to do here

**Do not build any UI in this ticket.** The output is the table and a recommended order, nothing
else. Building the easy ones while surveying is how the hard ones end up unmeasured and unbuilt -
and the hard ones are where the rule earns its keep, because they are hard precisely when voice is
least likely to work.

## Verification

- Every one of the 66 declared tools appears in the table exactly once. A tool missing from it is
  the failure mode; a count that does not reconcile against `LiveToolbox.declarations()` is a bug in
  the survey, not a rounding difference.
- Each `covered` verdict names the file that covers it, so the claim is checkable rather than
  asserted.
