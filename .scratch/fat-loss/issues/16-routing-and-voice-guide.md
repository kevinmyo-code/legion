---
map: fat-loss
ticket: "16"
title: "G4 to G6: get_health to fleet, voice guide copy, body routing"
type: build
status: built
status-detail: "built 2026-11-05 on feat/fat-loss, targeted JVM tests green; Live setup payload ~24,608 tokens (was ~24,609), ceiling 24,650 unchanged; owed: a calorie question routed to ask_body on the phone"
blockers: ["01"]
blocked-by: ["[[01-kevins-rulings]]"]
tags: [ticket]
---
# G4 to G6: get_health to fleet, voice guide copy, body routing

- **G4** `get_health` (the car's health) moves from `DISPATCHED["body"]` to fleet; the pinning test
  moves with it.
- **G5** Voice guide: the wrong "When did I last..." lines (`VoiceGuideData.kt`,
  `tools/voice_guide_copy.py`) are fixed.
- **G6** Calories, protein, maintenance and "is this target right for me" route to `ask_body`;
  `ask_goals` stops claiming them. Token-neutral against `LiveSetupPayloadSizeTest`.
