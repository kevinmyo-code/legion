---
map: web-assistant
ticket: "03"
title: "One prompt for every client: the persona, the frame, the honesty clause"
type: decision
status: resolved
status-detail: "Opus proposal on Kevin's rulings: one source file, generated for both clients"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Answer (2026-10-04)

Kevin ruled Mia talks to **Dorothy** and Kevin to his own active companion. Opus's proposal, built
unless vetoed:

- **One source of truth for the shared clauses** (`ASSISTANT_FRAME`, `CANNOT_CLAUSE`, the
  tool-use and unreadable-vs-empty rules): a text file in the repo that a generator turns into the
  Kotlin constants AND a TypeScript module, with a drift check that fails the build, the same posture
  as `tools/voice_guide.py`. No hand-maintained second copy.
- **The web prompt is assembled by the engine**, not the browser, and baked into the locked token's
  setup, so a browser cannot alter it.
- **Companions per member live on the engine:** name, persona fragment, voice, owner. Kevin's phone
  pushes its companion roster up (Dorothy assigned to Mia); the web reads the signed-in member's
  companion. The phone keeps its local profiles as today.
- The web gets its own `PromptRoleNamingTest` equivalent: no hardcoded assistant name in shared copy.
- The web frame adds one honest line the phone does not need: what this client cannot do (phone-only
  capabilities, ticket 04).
