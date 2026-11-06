---
map: fat-loss
ticket: "15"
title: "G2 and G3: partial meal targets, honest plan acceptance"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss, targeted JVM tests green; owed: 'set protein to N' by voice on the phone"
blockers: ["01"]
blocked-by: ["[[01-kevins-rulings]]"]
tags: [ticket]
---
# G2 and G3: partial meal targets, honest plan acceptance

- **G2** `set_meal_target` accepts a partial update ("set protein to N"): the fields not given are
  carried from the current target. Carbs and fat are optional (ruling 3); with no current target,
  calories are required and an unset carbs/fat is stored as 0 and never read back as a target.
- **G3** `acceptWholePlan` returns the real outcome of the meal and sleep writes; the dialog no
  longer says "Meal target set" when it was refused.
