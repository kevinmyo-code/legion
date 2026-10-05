---
map: web-assistant
ticket: "04"
title: "The tool surface on the web: engine tools, plus which phone-only ones move"
type: decision
status: resolved
status-detail: "Opus proposal: engine tools plus tick history; phone-only says so"
blockers: ["02"]
blocked-by: ["[[02-who-runs-the-tools]]"]
open-blockers: 0
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

## Answer (2026-10-04)

"Everything the engine has" = the 11 `/mcp` tools plus the bought log's four
(`.scratch/purchase-log/`). Opus's proposal, built unless vetoed:

- **Moves server-side now:** tick history (`last_ticked`, ADR 0049 wording), because Mia will ask it
  and it is a pure read of synced tables.
- **Readable on the web:** voice-note summaries already on the server (transcript and summary;
  audio stays phone-only).
- **Never on the web:** Gmail (read-through only and never on the server, §7), OBD and car live data,
  Spotify, navigation, phone calls, alarms, the generated views. The web prompt names these as
  "only on Kevin's phone" and the assistant says so in words instead of pretending.
