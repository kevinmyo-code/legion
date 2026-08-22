---
map: goal-plans
ticket: 04
title: "The daily checklist, on Body and Home"
type: build
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-recommender-and-playbook]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The daily checklist, on Body and Home

## What to build

Kevin: *"revamp of BIO/body tab + revamped section in home tab"* (settled decision 7).

The checklist is what makes a plan a habit rather than a document. A day's items derived from the
accepted plan, tickable, visible at a glance.

- **Body tab**: the full picture - the plan, the targets, today's items, recent adherence.
- **Home section**: today's items only, at a glance, on the screen he actually lands on.

Ticking by voice should work through `manage_item`, which already handles exactly this shape - **do
not build a second ticking path.**

## Rules

- `mission-control` owns the aesthetics. Coordinate with it rather than inventing a look.
- **Adherence is shown, never scored.** A streak counter is a compulsion mechanic and CLAUDE.md §7
  bans it permanently. Showing which days had items completed is a record; ranking him on it is not.
- An empty or unaccepted plan reads as "no plan yet", never as zero progress. Same posture as
  `DigestText`'s "not logged, never 0".

## Verification

- Suite green on the pure derivation of a day's items from a plan.
- On the phone: both surfaces, with a real accepted plan, plus the empty state before one exists.
