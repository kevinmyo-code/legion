---
map: body-coach-2
title: "Body coach 2: Alfred designs, tracks and recommends; the engine owns every number"
charted: 2026-11-06
charted-by: "Kevin + Opus"
effort: "`.scratch/body-coach-2/`"
tags: [map]
---

# Body coach 2: Alfred designs, tracks and recommends; the engine owns every number

**Kevin, 2026-11-06.** It started as two asks by voice: *"will i be able to tell it like hey i
wanna change the workouts. recommend me alternatives etc and change it accordingly"* and *"or ask
it what to eat based on what i have left calorie and macro wise etc?"*. Then a full interview on the
Body aspect: *"i want alfred to be able to design track and recommend workouts diets meals etc for
me and advise me"*, and *"i dont really need detailed screens... simple checklist of workouts to do
and calories and macroes ive consumed + whats remaining. most of it ill do by voice... the goal is
to use less screens"*. Goal unchanged from the fat-loss map: *"get lean, keep muscle"*.

**The organising idea (Kevin, later the same day):** *"basically formulating a plan, working
together with alfred, then locking it in and making adjustments as i go with alfred is the goal"*,
and *"i want alfred to set my calorie target"*. One versioned plan per member: draft by voice,
lock it in, then every change is a proposal that makes a new version (ticket 13).

## Destination

- **By voice, Alfred designs and recommends**: changes the workout plan, recommends alternatives,
  says what to eat from what is left, makes a weekly meal plan and its grocery list, advises.
  **Every number is the engine's**: kcal, protein, sets, reps, weights, progression, adjustments.
  The model chooses from the catalog and the options the engine returns, never invents a figure.
- **On the screen, a checklist**: today's workout to tick (tap a number to edit what was done),
  kcal and protein eaten and left (carbs and fat when known), meal and saved-meal log, and a
  pending proposal as one line with Accept and Dismiss. Nothing else; no sheets (ADR 0035, amended
  for Body).
- **Unprompted, three things only**: an evening protein shortfall, the Monday check-in, and plan
  adjustment proposals. Never a workout-due or "you haven't trained" nudge.

## Kevin's rulings

`memory/library/decisions.md`, 2026-11-06, "The Body interview"; ADR 0035, 0077 and 0078 amended.
In short: model designs, engine numbers (C); a checklist hands path (A); weekly meal plan into a
Pantry grocery list (B); flexible sessions a week, knee-friendly lower body by default, gym or home,
walking; nudges b, c, d; kcal and protein targets only (A); weekly weigh-ins, adaptation on four
weekly points (C); coach tone (B); supplements basics, eating out with estimates, neutral off days.

## Tickets, in order of value

| # | Ticket | Type | State |
|---|---|---|---|
| 01 | [Decisions](issues/01-decisions.md) | decision | resolved |
| 13 | [The plan: draft, lock, adjust; Alfred sets the target](issues/13-the-plan.md) | build | built |
| 02 | [Prototype](issues/02-prototype.md) | prototype | resolved; sheets dropped |
| 03 | [Engine: catalog, templates, sessions a week, gym or home](issues/03-engine-catalog-and-templates.md) | build | built |
| 04 | [Engine: programme change proposals](issues/04-engine-programme-proposals.md) | build | built |
| 05 | [Engine: what to eat from what's left](issues/05-engine-meal-suggestions.md) | build | built |
| 08 | [Engine: adaptation on four weekly points](issues/08-engine-weekly-adaptation.md) | build | built |
| 07 | [Phone: the Body checklist, the reads, voice](issues/07-phone.md) | build | built (slice) |
| 12 | [Phone: coach tone and the advice rules](issues/12-coach-tone-and-advice.md) | build | built |
| 10 | [Nudges: evening protein, Monday check-in, plan proposals](issues/10-nudges.md) | build | ready (engine), phone after 07 |
| 09 | [Weekly meal plan and its grocery list](issues/09-weekly-meal-plan.md) | decision | open |
| 06 | [Web: the same checklist](issues/06-web-hands-paths.md) | build | ready |
| 11 | [What the phone can count for walking](issues/11-walking-on-the-phone.md) | research | open |

## Not yet specified

- **Food numbers for a meal Alfred designs.** A saved meal carries his own estimate; a grocery
  carries the receipt reader's estimate for the whole item as bought. A meal Alfred designs from
  scratch has neither, and ruling 1 says the engine owns the number. Ticket 09 asks how.
- **The trend's rate with weekly weigh-ins.** The adaptation moved to four weekly points; the
  trend's own 14-day rate (the Monday line, the band) did not. Two weekly points make a noisy rate.
  Raise with ticket 10's Monday check-in.
- **Folding the direction-2 session screen into the checklist** (fat-loss ticket 25 built it).
  Kevin wants fewer screens; ticket 07 raises it rather than deleting it.

## Out of scope

- The model writing a number (ADR 0078 as amended; CLAUDE.md section 4 rule 5).
- Medical advice or a diagnosis. A sore joint filters exercises and says a physio is the next
  step if it persists (fat-loss ruling 9).
- Workout-due nudges, streaks, anything about time away (CLAUDE.md section 7, ruling 5).
- Fat burners or any supplement beyond protein powder and creatine.
- Kevin's personal numbers or health details in the repo (fat-loss ruling 10). Synthetic tests.
