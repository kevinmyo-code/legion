---
map: body-coach-2
ticket: "03"
title: "Engine: catalog, templates, sessions a week, gym or home"
type: build
status: built
status-detail: "built 2026-11-06 on feat/body-coach-2: equipment and joint tags, kettlebell and home variants, upper/lower, sessions a week, knee-friendly defaults, walking finisher, per-exercise ranges; tests/test_body_coach_2.py; owed: engine deploy with body_coach 0004"
blockers: ["01"]
blocked-by: ["[[01-decisions]]"]
tags: [ticket]
---
# Engine: catalog, templates, sessions a week, gym or home

`server/body_coach/programme.py`:

- `Exercise.equipment` is the equipment it can be done with (any one): barbell, dumbbell, machine,
  kettlebell, bodyweight; `joints` the joints it loads most (shoulder, knee, lower back).
- `TEMPLATES`: full body (A, B) and upper / lower (A to D) with day labels; the lower body leads
  with knee-friendly work (Kevin, 2026-11-06); `Slot.ranges` for a choice whose range differs.
- `CARDIO_SLOT`, the walking finisher, in minutes. `Filters`, `at_home`, `resolve_day` (a slot
  holds its swap if allowed, else its first allowed choice not already in the day, else is paused
  and named), `next_day_key(template)` cycling the template's days.

`server/body_coach/workouts.py`: a session's `location` (gym or home, rebuilt while nothing is
ticked), sessions a week instead of weekdays (`training_day` means a session is still due this
week), day labels in every word, history's range from the stored rows. Migration 0004.

## Verification

- `tests/test_body_coach_2.py`: every template under every equipment, joint and cardio combination
  resolves with no repeat and nothing banned; the default lower body carries no knee tag; home is a
  kettlebell and bodyweight only; per-exercise ranges; the four-day rotation; minutes.
- `tests/test_workouts.py` green, its squat expectations moved to the split squat default.
