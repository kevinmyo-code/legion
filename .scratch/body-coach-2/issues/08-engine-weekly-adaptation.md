---
map: body-coach-2
ticket: "08"
title: "Engine: the calorie adaptation on four weekly points"
type: build
status: built
status-detail: "built 2026-11-06 on feat/body-coach-2: 28-day window, a weigh-in in each of four weeks, 22 intake days; tests/test_body_coach.py moved; owed: engine deploy"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# Engine: the calorie adaptation on four weekly points

Kevin, 2026-11-06: weigh-ins are weekly; extra ones still count. `server/body_coach/adaptation.py`:
`WINDOW_DAYS = 28`, `MIN_INTAKE_DAYS = 22`, `MIN_WEIGH_IN_WEEKS = 4` (`Window.weeks()`), the water
week and the 14-day cooldown unchanged. `energy.py` says "from your own trend, 28 days". The
proposal words say "the last four weeks".

## Verification

- `tests/test_body_coach.py`: the window is 28 days in four weeks; three weeks with a weigh-in is
  under the gate; the in-band and clamped figures recomputed over 28 days; the REST proposal
  carries 28 intake and weigh-in days.
