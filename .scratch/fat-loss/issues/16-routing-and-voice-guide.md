---
map: fat-loss
ticket: "16"
title: "G4 to G6: get_health to fleet, voice guide copy, body routing"
type: build
status: open
status-detail: ""
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
