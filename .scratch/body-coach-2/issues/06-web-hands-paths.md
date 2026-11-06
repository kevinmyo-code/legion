---
map: body-coach-2
ticket: "06"
title: "Web: the same Body checklist"
type: build
status: open
status-detail: "ready; lower value than the phone (Kevin trains with the phone); folds in fat-loss ticket 26"
blockers: ["03", "04", "05"]
blocked-by: ["[[03-engine-catalog-and-templates]]", "[[04-engine-programme-proposals]]", "[[05-engine-meal-suggestions]]"]
tags: [ticket]
---
# Web: the same Body checklist

`server/frontend/src/screens/body-workouts.tsx` still edits the legacy weekly plan (fat-loss ticket
26). Replace it with what ticket 07 puts on the phone, through the same routes:

- today's workout as a checklist (tick, edit numbers), its label and gym or home;
- kcal and protein eaten and left; carbs and fat when known;
- a pending programme proposal as one line with Accept and Dismiss.

No plan-change form and no meal-suggestion panel (Kevin, 2026-11-06: voice only). The web
assistant already reaches every engine tool.

## Verification

- `npm run build` and the body screen's vitest file.
