---
map: body-coach-2
ticket: "05"
title: "Engine: what to eat from what's left"
type: build
status: built
status-detail: "built 2026-11-06 on feat/body-coach-2: suggest.py, meal_options.py, GET meal-options/, POST meal-options/log/, suggest_meal and log_meal_option; kcal and protein only, off-day week line; owed: engine deploy"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# Engine: what to eat from what's left

- `server/body_coach/suggest.py`, pure: targets and intake in, ranked options and words out
  (decisions 12 to 16).
- `GET /api/body/coach/meal-options/`: `clock`, `part_of_day`, `kcal_left`, `protein_left_g`,
  `eaten` (carbs and fat when a meal states them), `target` (kcal and protein only, Kevin's
  ruling), `week` (the last 7 logged days' mean), `options[]` (id, source, name, kcal, protein,
  carbs, fat, basis, fits, why), `too_big_as_bought[]`, `state` (`ok`, `late_little_left`,
  `over`, `under_floor`, `no_target`), `words`. Every figure carries `"estimate": true`.
- `POST /api/body/coach/meal-options/log/ {"option_id": ...}`: recomputes the option and logs it
  (saved meal through the saved-meal log; groceries as one `meal_logs` row with the as-bought
  figures). The body carries no figure.
- Engine tools: `suggest_meal` (read, "these are the options; suggest only from them"),
  `log_meal_option` (write).

## Verification

- Pure tests: fit, protein-first ranking, meal share by time of day, protein met, over, late, under
  800, no target, carbs/fat only when tracked, groceries never divided.
- View tests: options from saved meals and recent groceries (reversals out, older than 14 days
  out), log by id writes the engine's figures, a stale id refuses with nothing written.
