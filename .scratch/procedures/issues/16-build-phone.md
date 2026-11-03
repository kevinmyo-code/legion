---
map: procedures
ticket: "16"
title: "Build: phone declares run_procedure (direct, non-blocking when read-only)"
type: build
status: open
status-detail: ""
blockers: ["04", "05", "12", "15"]
blocked-by: ["[[04-how-clients-reach-run-procedure]]", "[[05-engine-down-and-two-implementations]]", "[[12-build-runner-and-tool]]", "[[15-build-first-procedures]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Build: phone declares run_procedure (direct, non-blocking when read-only)

## What

- `LiveToolbox.kt`: a `run_procedure` declaration whose `name` enum comes from the engine's
  `tools/list` (cached per process like `askEngine()`'s client, `LiveToolbox.kt:6533`), dispatched
  through `EngineMcpClient.callTool` (`backend/engine/EngineMcpClient.kt:111`).
- `GeminiLiveSession.withToolBehavior`: NON_BLOCKING + `WHEN_IDLE` when the listed procedure's
  `writes` is false; BLOCKING otherwise. `LiveNonBlockingReadToolsTest` extended; ADR 0065
  amended (new ADR or amendment, whichever Kevin rules).
- Ask/resume flow: the next user utterance after `needs_answer` is sent as `resume`.
- Engine down: falls back to the local tools and says so (ticket "Engine down").
- Prompt: a short clause next to `LOOKUP_HOLD_CLAUSE` in `ai/AriaBrain.kt`: prefer a procedure
  when one matches; repeat its caveats.
- Hands path: the procedure is reachable from the matching screen (car, money) through the same
  controller call.

## Verify

- JVM tests with a mock transport: read-only stamped NON_BLOCKING, writing stamped BLOCKING,
  engine-down fallback sentence.
- On the phone: "how's the car" and "how's money" each once, conversation record shows one
  `run_procedure` call. Owed as a device run.
