---
map: procedures
ticket: "17"
title: "Build: head unit declares run_procedure directly"
type: build
status: open
status-detail: ""
blockers: ["04", "12", "15"]
blocked-by: ["[[04-how-clients-reach-run-procedure]]", "[[12-build-runner-and-tool]]", "[[15-build-first-procedures]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: head unit declares run_procedure directly

## What

- `headunit/` `LiveToolbox.kt`: direct `run_procedure` declaration beside `ask_engine` (731),
  BLOCKING (no NON_BLOCKING path exists on the head unit). Follows `headunit/CLAUDE.md` rules:
  short sentences, glance card auto-dismiss 5 s, audio-first.
- **Driving fit**: a `say` variant `short` (one or two sentences, worst item first, "the rest is on
  your phone") selected by the caller kind `headunit`. No ask step while driving: a procedure
  needing an answer returns "this needs a yes; ask me when parked" and does not wait.
- `AskEngine.CAR_LOCAL_TABLES` stays as is.

## Verify

- JVM test: the declaration, the short variant chosen.
- In the car: "anything due on the car" once; record shows one call instead of the ask_engine
  chain. Owed as a device run.
