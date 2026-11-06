---
map: body-coach-2
ticket: "02"
title: "Prototype: Change plan sheet, proposal card, Suggest a meal sheet"
type: prototype
status: resolved
status-detail: "Kevin dropped the sheets the same day (less screens, voice first); the prototype stays as a record. 2026-11-06: one static HTML in direction A's style, synthetic numbers, four frames (sheet, proposal card, meal sheet, edge states) in the orchestrator's scratchpad, coach2-prototypes/"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# Prototype: Change plan sheet, proposal card, Suggest a meal sheet

Direction A (fat-loss ticket 20). One static HTML at 390 x 844 per frame, synthetic numbers only:

1. **Change plan sheet** from the workout card: days a week (2/3/4), which days, split, equipment,
   joints to go easy on, cardio finisher, alternatives for one exercise, "Show the change".
2. **Proposal card** on the workout card: the question, why, a diff (kept with progress, out, new
   and calibrating), Accept and Dismiss, plus the voice exchange that makes the same card.
3. **Suggest a meal sheet** from the Today card: time and what is left, ranked options with a
   one-tap Log, groceries too big as bought by name, the estimate note.
4. **Edge states**: late with little left, already over, under 800 logged in the evening.

Verification: rendered with headless Chrome and read back before the phone build starts.
