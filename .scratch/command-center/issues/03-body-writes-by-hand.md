---
map: command-center
ticket: "03"
title: "The Body tab learns to write"
type: build
status: built
status-detail: "Built: six dialogs and per-row delete, all same-function-tested against voice dispatch. Owes the tap-through on the phone."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# The Body tab learns to write

Survey verdict: Body renders all four data streams and can write NONE of them. Targets are drawn on
meters the user cannot type a number into. `undo_last_log` means a misheard log is only fixable by
the same failing channel.

## Build

1. **Add affordances** for meal, sleep, bodyweight, workout set - small dialogs on the panes that
   already render each stream, calling the SAME controller functions the voice tools call
   (`MealController`, `SleepController`, etc. - trace each from LiveToolbox dispatch first).
2. **Editable targets**: tap the meal/sleep target values to type a number. Same setters the voice
   tools use. The generated-plan flow is unchanged and remains the recommended path; this is the
   manual override ADR 0035 requires.
3. **Undo/delete on rows**: each recent-log row gets a delete, reaching the same code
   `undo_last_log` reaches (trace it; if undo is last-write-only, per-row delete via the DAO the
   controller already owns is acceptable - say which you built).

## Rules

- ADR 0035: same controller, never a UI copy. No score/streak/percentage. Estimates labelled.
- Match mission-control; these are dialogs on existing panes, not new screens.

## Verification

- Suite green both ways. A test that UI write path and voice write path reach the same function per
  stream. On the phone: log a meal by hand, delete it, type a target.
