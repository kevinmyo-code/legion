---
map: cyberdeck-ui
ticket: 07
title: "Body surface: biometrics telemetry"
type: grilling
status: resolved
status-detail: ""
blockers: ["01", "02"]
blocked-by: ["[[01-deck-design-language]]", "[[02-chart-rendering]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Body surface: biometrics telemetry

## Question

The biohacking centerpiece. Body currently shows this-week/today gaps plus recent-log lists. What
does it become with real visualization: weight trend over time, sleep duration/quality history,
calorie + macro intake vs target across days, workout session adherence and per-exercise
progression (weight x reps over weeks). Which of these are charts, which are readouts, which are
drilldowns; what time ranges; how TrustTier REPORTED (everything here is self-reported) is worded
on a telemetry-styled panel without pretending sensor precision.

## Answer

Grilled with Kevin, 2026-08-07. **Sparkline panels, full charts in drilldowns** - the module
screen stays a one-glance readout; depth is one tap in. Full-chart-inline declined.

1. **Four panels, fixed order**: MASS (hero weight, 30d sparkline, delta) -> INTAKE (today vs
   target, 7d bars) -> SLEEP (last night, 7d bars vs target) -> TRAINING (sessions this week vs
   plan, tonight's sets).
2. **Drilldowns per panel**: full-height chart + the history list (the current recent-logs lists
   move here). TRAINING drills twice: module -> exercise list -> per-exercise progression
   (weight x reps over weeks - the biohacker's payoff chart).
3. **Time ranges**: fixed defaults on panels, no controls; one range selector inside drilldowns
   only (7d / 30d / 90d / all).
4. **Header carries `UPLINK: SELF-REPORT` once** (semantic ticket's universal-state rule); no
   per-row REPORTED tags.
5. **Not-logged days render as GAPS in bars, never zeros** (§4: zero eaten is a lie).
