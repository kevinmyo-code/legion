---
map: engine-mcp
ticket: "11"
title: "The phone bridges engine tools into its Live session"
type: build
status: open
status-detail: ""
blockers: ["04", "10"]
blocked-by: ["[[04-how-the-phone-consumes-engine-tools]]", "[[10-the-engine-mcp-endpoint]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# The phone bridges engine tools into its Live session

## Build (shape fixed by 04)

Whatever 04 picks, these hold:

- Calls go out on the phone's existing device token (`EngineAuth`), through the existing transport
  in `backend/engine/`.
- **Engine unreachable is a sentence, not an empty result.** "The engine is unreachable, so nothing
  was read" - never "you have nothing due".
- **No mail-touched content crosses the bridge.** A turn that touched an `EPISODIC_EXCLUDED_TOOLS`
  tool does not forward that content as an MCP argument. Test it.
- The outcome-verb rule holds: a bridged write (when there are any) is reported done only on a
  successful result in that turn.
- **A hands path** for anything the bridge newly makes reachable by voice (ADR 0035), calling the
  same engine code.
- New tool names get voice-guide copy (`tools/voice_guide_copy.py`) or `voice_guide.py` fails.

## Verification

- Unit: declaration translation, offline sentence, mail exclusion.
- On the A25: ask a question only the engine can answer, with the engine up and with it down.
