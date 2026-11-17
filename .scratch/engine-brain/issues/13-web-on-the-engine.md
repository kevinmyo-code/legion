---
map: engine-brain
ticket: "13"
title: "Web assistant onto the engine's turn endpoint"
type: build
status: open
size: M
blockers: ["07"]
blocked-by: ["[[07-retire-the-phone-brain]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Web assistant onto the engine's turn endpoint

Stage 12. Web last (Kevin).

## Verification (gates)
- [ ] Web eval at its bar.
- [ ] Recruiter visits are still refused at the turn endpoint and keep their own door.

## Note (2026-11-17, ADR 0102)
- The web assistant now has a cascade engine on the engine (`POST /api/assistant/turn`): speech-to-text, the runtime's own loop (`agents.runtime.run_loop`) over the web tool door, text-to-speech. Default `cascade` for recruiter visits, `live` for members; switch per role (`assistant/voice_engine.py`).
- What this ticket still owes: moving MEMBERS' web turns onto the agent graph (`/api/agents/turn`, its router, specs and confirm gate) and retiring Live. The browser side (record an utterance, read SSE, play PCM) is already built and can be pointed at the turn endpoint.
- Recruiter visits keep their own door (this ticket's gate): the turn route refuses them, and `/api/assistant/turn` runs their fence from the tool door's code.
- Ticket 04 is unchanged: the web is not a limb under the contract yet.
