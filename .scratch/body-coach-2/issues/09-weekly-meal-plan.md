---
map: body-coach-2
ticket: "09"
title: "Weekly meal plan that hits the targets, and its grocery list in Pantry"
type: decision
status: open
status-detail: "Kevin ruled B (a weekly plan and a grocery list); how the engine gets the numbers for a meal Alfred designs needs one ruling"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# Weekly meal plan that hits the targets, and its grocery list in Pantry

Kevin, 2026-11-06: Alfred makes a weekly meal plan that hits the targets and turns it into a
grocery list in Pantry. Ruling 1: the model designs (meal ideas), the engine owns every number.

## The question for Kevin

A meal Alfred designs has no kcal or protein figure until something states one. Options:

1. **A food reference table in the engine** (per 100 g, generic values, labelled estimates). Alfred
   names ingredients; the engine computes the meal's figures and **scales the portions** so the
   week hits kcal and protein. Most faithful to ruling 1; a table to curate.
2. **Saved meals only.** The plan is built from his saved meals (his own estimates); Alfred
   proposes a new meal as a saved meal first, with its estimate said aloud, and he accepts it.
3. **Model estimates, labelled.** Alfred's own figure for each meal, said as an estimate. Breaks
   ruling 1's "the engine owns every number".

Recommendation: 1, with 2 as the first slice (it ships with no new data).

## Build, once ruled

- `meal_plans` / `meal_plan_items` (born tenanted): a week, seven days of meals, each meal's
  figures and their source; the plan's daily totals against kcal and protein.
- A proposal he accepts, like a programme change; accepting writes the grocery list as a Pantry
  checklist (the existing "Groceries" list) with one item per ingredient.
- The Monday check-in (ticket 10) reads next week's plan and list.
