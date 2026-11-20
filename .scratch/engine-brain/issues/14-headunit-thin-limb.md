---
map: engine-brain
ticket: "14"
title: "Head unit as a thin limb"
type: build
status: open
size: M
blockers: ["13"]
blocked-by: ["[[13-web-on-the-engine]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Head unit as a thin limb

Stage 13. After the web (Kevin). `headunit/` is a separate project.

**Amended 2026-11-19 (Kevin, ADR 0104):** this is HU-2, shipped by OTA after HU-1. The head unit adopts the shared `voice/` module and the engine's voice on `docs/contracts/voice-client-v1.md`, with Moose picked by surface on the engine. Live staying one OTA as rollback is the plan's default, not ruled (`docs/contracts/voice-client-v1.md` section 15); the Gemini key and the sub-agents that use it leave in HU-3.

## Verification (gates)
- [ ] Head unit smoke over its OTA build (no ADB).
