---
map: web-assistant
ticket: "04"
title: "The tool surface on the web: engine tools, plus which phone-only ones move"
type: decision
status: open
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-who-runs-the-tools]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# The tool surface on the web: engine tools, plus which phone-only ones move

## Question

"Everything the engine has" is the 11 `/mcp` tools today (plus the bought log's, `.scratch/purchase-log/`).
Phone-only capabilities (`get_last_ticked`, voice-note reads, generated views, Spotify, OBD, Gmail,
navigation) are not on the server.

- Which phone-only reads should move server-side so the web can use them (tick history, voice-note
  summaries)?
- Which can never (Gmail: read-through only and never on the server; OBD; Spotify; navigation).
- The web assistant must say "I can't do that from here" for a phone-only capability, never pretend.
