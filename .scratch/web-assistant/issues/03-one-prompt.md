---
map: web-assistant
ticket: "03"
title: "One prompt for every client: the persona, the frame, the honesty clause"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# One prompt for every client: the persona, the frame, the honesty clause

## Question

The prompt layer (`ASSISTANT_FRAME`, `CANNOT_CLAUSE`, personas, tool-use instructions) lives in
Kotlin. A web assistant needs the same rules.

- One source of truth served by the engine (the phone fetches it too, with a bundled fallback), or a
  generated copy for the web, or a second hand-written prompt (drift risk)?
- Which persona speaks to Mia: Kevin's chosen companion, or her own?
- The "never hardcode an assistant name" rule and the `PromptRoleNamingTest` equivalent on the web.

## Ruled 2026-10-04 (Kevin): Mia talks to Dorothy

*"her own companion. we already have dorothy. give that to her."* Dorothy is an existing companion
profile on Kevin's phone (`CompanionProfile`; see `CompanionSwitchTest`). On the web, Mia's
assistant is Dorothy and Kevin's is his own active companion. So companion definitions (name,
persona, voice) must be available to the engine per member, not only in the phone's local profile
store. How they get there is part of this ticket.
