---
map: clerk
ticket: "01"
title: "Decision: the clerk is headless Claude Code on Kevin's laptop, on the subscription"
type: decision
status: resolved
status-detail: "Kevin 2026-10-29: 'i like A more' (the CLI on his own laptop, left on when he goes out), after rejecting the Agent SDK on an API key ('i was hoping more of using my monthly sub and not the api')."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Decision: headless Claude Code on the laptop

Three shapes were offered on 2026-10-29. The Claude Agent SDK as a worker beside the engine bills
per token on an API key; Kevin: *"i was hoping more of using my monthly sub and not the api."*
Claude Code cloud routines run on the subscription and need no machine, but the on-demand run API
needs his claude.ai login, so the engine would fire them through GitHub events, and the sandbox has
no logged-in browser. The CLI run headless (`claude -p`) on a machine logged into his account uses
the subscription and can later get a browser profile. Kevin: *"i like A more and i think we can use
it when i keep the laptop on."*

## Consequences

1. The worker is a poller on the laptop, not a server. Nothing inbound to the laptop.
2. Availability is a fact Alfred states, never hides: online with last-seen, or off since when.
3. No API key in the repo, the worker config, or the engine for this feature.
4. The engine holds the queue and the results so the phone, the head unit and the web see the
   same rows (ADR 0044).
5. ADR: `docs/adr/0066-the-clerk-runs-headless-claude-code-on-the-laptop.md` (ticket 02 writes it).
