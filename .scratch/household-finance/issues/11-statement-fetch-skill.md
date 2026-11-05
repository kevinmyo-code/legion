---
map: household-finance
ticket: "11"
title: "Statement fetch skill for the Claude worker"
type: build
status: open
status-detail: "charted 2026-11-05"
blockers: ["04"]
blocked-by: ["[[04-statement-drops]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Statement fetch skill for the Claude worker

## What
- Worker skill `statement-fetch` (private skills folder, not the repo).
  - Triggered when Alfred hears "fetch the statements", or when Gmail shows a statement-ready notice (read-only Gmail carve-out) and Kevin says yes.
  - Opens each bank's or CPF's site in Chrome and STOPS at the login for Kevin to sign in (Singpass or bank token).
  - Downloads the latest statement PDF(s) into the Drive statements folder.
  - Read and download only (ruling 7): never transfers, never changes settings, never types a password, never bypasses a check.
- Reports which statements were fetched and which are still missing.
## Verification
- A dry run with Kevin.
