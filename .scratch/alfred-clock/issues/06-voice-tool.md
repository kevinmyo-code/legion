---
map: alfred-clock
ticket: "06"
title: "The clock voice tool"
type: build
status: built
status-detail: "built 2026-11-09 on feat/alfred-clock; clock tests green, full suite and assembleFast not yet run to completion. Owed: Kevin's run on the A25 (ticket 08)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The clock voice tool

## What

- ONE `clock` declaration, action enum, terse. Same `ClockController` as the screen (ADR 0035).
- Actions: alarm_set, alarm_cancel, alarm_list, timer_start, timer_pause, timer_resume,
  timer_cancel, timer_left, stopwatch_start, stopwatch_stop, stopwatch_lap, stopwatch_reset,
  bedtime_on, bedtime_off.
- Every result says what changed in words; a failure says nothing changed.
- `manage_item.exact`'s "wake me up at exactly 6am" example routed wake-ups to reminders; it now
  points at `clock`. That trim pays for most of the new declaration.
- `LiveSetupPayloadSizeTest` stays green against dev merged with `integrate/2026-11-09`.

## Built (2026-11-09)

- `clock` is 693 chars, ~173 tokens. Merged with `integrate/2026-11-09`: ~24,633 of the 24,650
  ceiling, ~17 tokens of headroom. Not raised. The next tool needs a trim or a ruling.
