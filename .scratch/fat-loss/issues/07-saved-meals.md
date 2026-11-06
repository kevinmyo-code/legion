---
map: fat-loss
ticket: "07"
title: "Engine: saved meals"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss-engine (merged into feat/fat-loss); server suite 2917 run, 2 failures pre-existing on base (persona copy test); owed: engine deploy, a saved meal logged by voice"
blockers: ["01"]
blocked-by: ["[[01-kevins-rulings]]"]
tags: [ticket]
---
# Engine: saved meals

Build item F. `saved_meals`, shared (ruling 6).

- `name` (unique per household, case-insensitive), `description`, `calories_kcal`, `protein_g`,
  optional `carbs_g`, `fat_g` (ruling 3). The macros are **estimates** and every surface says so.
- `POST saved-meals/` with the numbers, or with `from_meal_log` (a logged meal's guid) to save what
  was just eaten. `DELETE saved-meals/<id>/` is a hard delete of the preset only.
- `POST saved-meals/<id>/log/` writes one `meal_logs` row (REPORTED, the saved estimate).
- Voice: engine tools (ticket 08); "log my usual breakfast" through `log_meal` (ticket 10).
