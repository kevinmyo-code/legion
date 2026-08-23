---
map: hands-and-senses
ticket: "32"
title: "Sitreps happen when asked, never on a schedule"
type: build
status: built
status-detail: "Built: scheduler and alarm deleted, not defaulted off. DIGEST.hasContent back to false since its only raise is gone. Wellbeing digest untouched."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Sitreps happen when asked, never on a schedule

Kevin, 2026-08-22: *"sitreps stay tap only or via voice activation only."*

Ruled after being told that the newsletter fix put `NEWS` in `SitrepModule.DEFAULT_ON`, which meant
a SCHEDULED sitrep would perform a live Gmail fetch and a real LLM summary without being asked.

## The rule

A sitrep is produced when Kevin asks for it - the Home card's tap, or `get_sitrep` by voice. **There
is no automatic sitrep.** Not at a set hour, not on app open, not on boot.

## Build

1. **Retire the scheduled path**: `sitrep/SitrepScheduler.kt`, `SitrepAlarmReceiver`, the schedule
   row in the proactive-speech settings, and the boot rearm. Remove them rather than defaulting them
   off - a switch that must stay off is a trap someone flips later.
2. **`NEWS` stays default-on for on-demand asks.** The reason it was off (a background fetch nobody
   configured) is exactly what this ticket removes, so the concern dies with the scheduler.
3. **The wellbeing digest is NOT affected.** It is a different feature Kevin asked for
   (goal-plans 05) with its own schedule and its own compulsion-test reasoning. Leave it alone.
4. **The Room row and its migration stay** if removing them costs a migration - an unused table is
   cheaper than a destructive one. Say which you did.
5. Voice guide copy for `get_sitrep` must not promise a scheduled brief.

## Verification

- Suite green both ways, one run fresh. `python tools/voice_guide.py` exit 0.
- A test that nothing arms a sitrep alarm on boot or app start.
- On the phone: no sitrep arrives unasked; tapping the card and saying "sitrep" both still work.
