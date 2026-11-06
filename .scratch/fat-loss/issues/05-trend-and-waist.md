---
map: fat-loss
ticket: "05"
title: "Engine: weight trend and the waist log"
type: build
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-body-profile]]"]
tags: [ticket]
---
# Engine: weight trend and the waist log

Build item C.

- **EWMA trend** over local days (household zone): the day's mean reading in lbs; `trend += 0.1 x
  (w - trend)` on a day with a reading, carried unchanged over a gap; seeded with the first reading.
- **Rate**: trend change over the last 14 days as % of bodyweight per week (and lbs/week). Null
  with words when there are not 14 days of history.
- **First-week water drop is flagged, not extrapolated**: while the first weigh-in is under 21 days
  old, `early_water: true`, the words say so, and no milestone date is projected.
- **Milestone progress (G1)**: from the active `bodyweight_lbs` goal (unit-safe: a `kg` goal is
  converted), start = first trend point, remaining lbs, progress share, weeks at the current rate
  when the rate is toward the goal and not flagged.
- **Waist log** (`waist_logs`, shared, ruling 6): `measured_on`, `waist_mm`; `GET/POST waist/`,
  `DELETE waist/<id>/`; wire `waist_in` and `waist_cm`. Change over 28 days in the trend response.
- Replaces the two-reading delta on the Mass pane (phone side in ticket 21).

## Verification

- Tests: gaps carried, kg readings, the 14-day rate, the water flag, the milestone from a kg goal.
