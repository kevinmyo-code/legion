---
map: fat-loss
ticket: "13"
title: "Phone: the Monday 8 am weekly weight line"
type: build
status: open
status-detail: ""
blockers: ["05"]
blocked-by: ["[[05-trend-and-waist]]"]
tags: [ticket]
---
# Phone: the Monday 8 am weekly weight line

Build item E. Its own raise (`weekly_weight_line`, WELLBEING) and its own Monday 08:00 alarm.

- Facts from `GET weekly-line/`: trend weight, rate per week against the band, the goal's
  remaining distance, waist change when there is one. **Silent when there is too little data**
  (fewer than 8 weigh-ins in 14 days, or no rate yet), or when `weekly_line_on` is false.
- Compulsion test (CLAUDE.md section 7): (a) anchored to his own scale and his own goal; (b)
  actionable that morning; (c) never mentions missed weigh-ins, logs, days or engagement, no streak;
  (d) "stop the weekly weight line" (`set_body_profile`, `weekly_line_on: false`) silences it
  forever, as does the WELLBEING switch. No daily nudge exists anywhere in this map.
