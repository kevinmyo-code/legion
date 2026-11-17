---
map: engine-brain
ticket: "07"
title: "Retire the phone brain: the phone becomes a thin limb"
type: build
status: open
size: M
blockers: ["03", "05", "06", "15"]
blocked-by: ["[[03-engine-homes-for-room-only-data]]", "[[05-mail-on-the-engine]]", "[[06-specialists-router-and-router-run-reads]]", "[[15-engine-homes-user-aspect-records]]"]
open-blockers: 4
ready: false
tags: [ticket]
---
# Retire the phone brain: the phone becomes a thin limb

Stage 6 (ADR 0095).

## Build
Remove from the phone:
- the cascade's model loop;
- prompt assembly;
- the sub-agents;
- `ask_engine`;
- the pack overlay;
- the model draft in speculation.

Keep on the phone:
- STT and TTS;
- wake word and barge-in;
- the crisis pre-check;
- the confirm UI;
- the relay executor;
- the vault;
- simple commands.

## Verification (gates)
- [ ] Phone compile and the full chunked suite green; detekt adds no new findings.
- [ ] Engine unreachable: voice says it cannot reach the engine, and screens show the last data with its age.
