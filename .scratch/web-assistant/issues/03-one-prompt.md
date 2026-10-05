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
