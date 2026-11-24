---
map: engine-brain
title: "Engine brain: Alfred's agent graph on the engine; the phone and every device are limbs"
charted: 2026-11-17
charted-by: "Opus (plan 2026-11-16, ruled by Kevin 2026-11-17)"
effort: "`.scratch/engine-brain/`"
tickets: 17
open: 16
status: open
tags: [map]
---

# Engine brain

**Kevin:** *"lets plan the fuller agent graph and build it after"*. Ruled on 2026-11-17 (ticket 00; ADRs 0095 to 0101). Every example in this map and its tickets is invented, because the repo is public.

## Destination

He says "what do I have today and can I afford lunch".
- The phone hears it and sends the words to the engine.
- A code router hands the turn to the day and money specialists together.
- Code has already run the obvious read. The model answers in one round, and the phone speaks it sentence by sentence.
- A trace page shows each step, its cost and its time.
- A prompt fix to that answer ships by engine deploy. It goes out only if every eval suite still clears its bar.

## Shape
- **The engine** runs the router, the specialists, the loop, the guards, the traces and the evals.
- **The phone is a thin limb** with no fallback brain. It keeps:
  - wake word;
  - speech-to-text and text-to-speech (Cloud and Laptop, unchanged);
  - barge-in;
  - the crisis pre-check;
  - the confirm UI;
  - phone-only tools through the relay;
  - the ID vault;
  - simple commands.
- **Mail** runs on the engine: read-through only, never stored, and never acted on in the turn that read it.

## Stages (one ticket each; ship when the gates are green, report after)
| Ticket | Stage |
|---|---|
| 01 | Measure |
| 02 | Runtime and evals, dark |
| 03 | Engine homes for Room-only data (records split to 15) |
| 04 | The switch: limb contract, relay, per-member flag |
| 05 | Mail on the engine (Kevin grants consent once) |
| 06 | Specialists, router, router-run reads |
| 07 | Retire the phone brain |
| 08 | Engine-native writes |
| 09 | Derived knowledge |
| 10 | Procedures device step |
| 11 | Telemetry: OBD via the phone |
| 12 | Evening due sweep |
| 13 | Web on the engine |
| 14 | Head unit as a thin limb |
| 15 | Engine homes for user-aspect records (split from 03) |
| 16 | Alfred and Moose on one engine |

## Not in this map
- On-phone or cloud-hosted voice (dropped).
- Patterns (off).
- `ask_alfred` on `/mcp` (no).
- Smart-home actuators: they need their own ruling first.
