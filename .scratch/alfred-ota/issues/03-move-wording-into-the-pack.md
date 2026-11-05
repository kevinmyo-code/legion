---
map: alfred-ota
ticket: "03"
title: "Move wording into the pack"
type: build
status: open
status-detail: "charted 2026-11-05; after 02"
blockers: ["02"]
blocked-by: ["[[02-phone-reads-the-pack]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Move wording into the pack

## What

Move these into the pack, in order of how often Kevin has asked for them to change:
1. **Tool-result framing lines:**
   - the recall note ("say only what the memory says")
   - the still-checking nudges
   - the holding lines
   - the Claude result announcement
2. **Tool descriptions** for the roughly 20 tools Kevin actually uses, from the 2026-11-03 baseline: calendar, checklists, fleet, music, memory, mail, claude_session.
3. **New rules:** the reply-in-English rule, and any other rule added by `fix/phone-daily-bugs`.

Every moved string keeps a bundled default. Nothing behaves differently on the day it moves.

## Verification

- A JVM test confirms the rendered system instruction is byte-identical before and after the move, using the bundled pack.
- The payload-size test is unchanged or smaller.
