---
map: cyberdeck-ui
ticket: 06
title: Today as the deck home
type: grilling
status: resolved
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-deck-design-language]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Today as the deck home

## Question

What does "my life at a glance" show when the deck boots? Today currently stacks gap rows (meals,
sleep, workouts, budget, maintenance). As the deck's home: which readouts earn the first screen,
in what order, at what depth (number-only vs sparkline vs mini-panel), what is one tap away, and
what does the screen do when a whole domain is silent (not-logged is not zero - §4 discipline).

Also: does Today remain a screen among screens, or become the shell's persistent top layer?
(Interacts with the navigation shell ticket - resolve after or alongside it.)

## Answer

Grilled with Kevin, 2026-08-07. (The shell ticket had already settled that Today stays the
single home surface behind the HOME key - no launcher grid.)

1. **INTAKE is the hero**: big kcal number + meter with pace tick at top. The most-logged,
   most-checked domain earns the focal point. Rotating/attention-sorted hero explicitly
   declined - an instrument's reading is always in the same place.
2. **Fixed order, always**: INTAKE hero -> SYSTEMS SWEEP (sleep, training-week, ledger burn,
   fleet; one checklist row each) -> AGENDA (today's timed items) -> ALERTS (`0 QUARANTINED`
   when clean; the only panel that ever turns red).
3. **Silent domain = stated, never hidden**: `SLEEP - NOT LOGGED` remains a row. A checklist
   that omits a system reads as "system missing"; §4 wording discipline holds.
4. **Depth: numbers and meters only, ZERO charts on home.** Trends live in the modules; home is
   the pre-flight check. Each panel taps through to its module.
5. **Attention travels by tag, not position**: `SET PLAN` / `PACING HOT` advisories sit on their
   rows; nothing reorders.
