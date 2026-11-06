---
map: fat-loss
ticket: "11"
title: "Phone: the plan generator uses the estimate and will not invent one"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss, targeted JVM tests green; owed: a plan generated on the phone before and after the profile is entered"
blockers: ["10"]
blocked-by: ["[[10-phone-coach-reads]]"]
tags: [ticket]
---
# Phone: the plan generator uses the estimate and will not invent one

Map decision 5. `GoalPlanAgent.generate` reads the energy estimate first.

- No profile or no weigh-in: no meal target is proposed; the result names the missing inputs and
  asks for them (a refusal entry the dialog and the voice path both say).
- With an estimate: the context carries it, and the code sets the plan's kcal and protein from it
  (carbs and fat optional, ruling 3). The model never picks the calorie number.
- The default workout goal is 3 full-body strength days with gym access (ruling 4).
