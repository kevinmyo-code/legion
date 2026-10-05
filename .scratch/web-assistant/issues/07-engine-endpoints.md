---
map: web-assistant
ticket: "07"
title: "Engine: the token-minting endpoint and the session-authenticated tool path"
type: build
status: open
status-detail: ""
blockers: ["02", "04"]
blocked-by: ["[[02-who-runs-the-tools]]", "[[04-web-tool-surface]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Engine: the token-minting endpoint and the session-authenticated tool path

## Build

Per 02 and 04: a session-authenticated endpoint that mints a Live ephemeral token from
`LEGION_GEMINI_KEY` (rate-limited, audited, refused in words when the key is absent), and the tool
path the browser calls, through `visible()`, leak-tested. pytest green.
