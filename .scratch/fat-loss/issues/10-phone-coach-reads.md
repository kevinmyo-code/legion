---
map: fat-loss
ticket: "10"
title: "Phone: BodyCoachController, ask_body reads, saved meals in log_meal"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss, targeted JVM tests green; owed: engine deploy, then ask_body questions and 'log my usual breakfast' on the phone"
blockers: ["04", "05", "07"]
blocked-by: ["[[04-energy-calculator]]", "[[05-trend-and-waist]]", "[[07-saved-meals]]"]
tags: [ticket]
---
# Phone: BodyCoachController, ask_body reads, saved meals in log_meal

- `bodycoach/BodyCoachController` over the ticket 04 to 07 routes, `FinanceController`'s shape:
  fresh, saved with its age in words, or unavailable in words. Never a blank standing in for a
  failure.
- Dispatched behind `ask_body`: `get_energy_estimate`, `get_weight_trend`, `list_saved_meals`.
  Read-only, so ADR 0065's non-blocking proof still holds.
- `log_meal`: a description naming a saved meal ("my usual breakfast") logs the saved estimate
  through `BodyWriteThrough` (queued offline), and says it is an estimate.
- Voice guide copy for every new tool name.
