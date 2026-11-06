---
map: fat-loss
ticket: "04"
title: "Engine: the energy estimate"
type: build
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-body-profile]]"]
tags: [ticket]
---
# Engine: the energy estimate

Build item B. Pure functions in `body_coach/energy.py`; one view.

- **BMR, Mifflin-St Jeor:** `10 kg + 6.25 cm - 5 age + 5` (male) or `- 161` (female). Integer kcal,
  rounded once at the end.
- **Maintenance = BMR x activity factor** (1.2 / 1.375 / 1.55 / 1.725). `basis: "formula"`.
- When the adaptation gate of ticket 06 is met, the observed maintenance from the trend replaces
  it: `basis: "trend"`, `basis_days: N`. The formula figure is still returned beside it.
- **Suggested target** = maintenance - `rate% x weight_kg x 7700 / 7 / 100`, for the profile's
  rate (clamped to 0.25 to 1.0 %BW/week); never at or below 800 kcal (the plan generator's floor).
- **Protein g** = `protein_g_per_kg x min(goal_kg, current_kg)`, default 2.0 g/kg, range 1.6 to 2.2.
  The goal weight is the active goal row with `metric_key = bodyweight_lbs`.
- Weight is the trend weight (ticket 05) when there is one, else the latest reading.
- Missing inputs (no profile field, no weigh-in) produce no figure for what they feed and are named
  in `missing`; never a guess.
- Every figure is labelled an **estimate** (CLAUDE.md section 4 rule 5) in `words` and by
  `"estimate": true`.

Callers: `get_energy_estimate` (phone, behind `ask_body`; engine MCP), `GoalPlanAgent` (ticket 11),
the BIO digest ENERGY line (ticket 12), the Body tab Energy card (ticket 21), web `/body` (ticket 09).

## Verification

- Unit tests on synthetic people: both sexes, each activity level, the protein min rule, the 800
  floor, missing inputs, kg readings converted.
