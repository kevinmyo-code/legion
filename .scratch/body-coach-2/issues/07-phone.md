---
map: body-coach-2
ticket: "07"
title: "Phone: the Body checklist, the reads behind ask_body, voice"
type: build
status: open
status-detail: "ready: engine tickets 03-05 built and origin/fix/workout-reads landed; build after merging fix/workout-reads (its whole-programme read)"
blockers: ["03", "04", "05"]
blocked-by: ["[[03-engine-catalog-and-templates]]", "[[04-engine-programme-proposals]]", "[[05-engine-meal-suggestions]]"]
tags: [ticket]
---
# Phone: the Body checklist, the reads behind ask_body, voice

ADR 0035 as amended for Body (Kevin, 2026-11-06): the hands path is a checklist, not a sheet.

## Screen

- **Today's workout as a checklist** on the Body screen: one row per set, tick inline, tap the
  numbers to edit what was done (the same `WorkoutSessionController` and routes as today). The
  label is the engine's (`day_label`, "Upper 1"), and "at home" shows when the session is.
- **kcal and protein eaten and left**, from the engine (`GET meal-options/` or the Today card's
  own figures); carbs and fat only when known, never with a target.
- **Meal log and saved-meal log** stay as they are.
- **A pending proposal is one line** on the checklist (`today_body.proposal.words`, shortened to
  its first sentence) with Accept and Dismiss, the same as the calorie proposal.
- **No Change plan sheet, no Suggest a meal sheet** (dropped by Kevin).
- **Raise, don't delete:** fold the direction-2 session screen (fat-loss ticket 25) into the
  checklist. It is a large change to a screen built yesterday; ask Kevin before removing it.

## Voice

- `suggest_meal` and `get_exercise_alternatives` behind `ask_body` (reads; the options are in the
  result, so "state only what is here" still lets the model suggest from them).
- Writes ride `ask_engine` (fat-loss map decision 3): `propose_programme_change`,
  `accept_programme_change`, `dismiss_programme_change`, `log_meal_option`, `start_workout` with
  `location`. "Log that" offline falls back to the phone's `log_meal` saved-meal path.
- Voice guide copy (`tools/voice_guide_copy.py`) for every new tool; the live setup payload stays
  under its ceiling (`LiveSetupPayloadSizeTest`).

## Verification owed on the phone

1. Engine deployed with `body_coach.0004`; install `fast`.
2. "I want four sessions a week": the proposal line shows; Accept; the checklist says Upper 1.
3. "I'm at home today": the checklist rebuilds with kettlebell and bodyweight work.
4. "What should I eat?" at lunch and after 21:00; "log that" logs the first option.
5. Tick a set inline and edit its reps; the engine's session shows the edit.
