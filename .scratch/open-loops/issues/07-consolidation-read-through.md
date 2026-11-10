---
map: open-loops
ticket: "07"
title: "Consolidation and the review ignore read_through marks"
type: build
status: open
status-detail: "found 2026-11-10 while building open loops; not fixed there"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Consolidation and the review ignore read_through marks

## Finding

`member_memory.consolidate.transcript()` drops a turn only when a tool in `READ_THROUGH_TOOLS` ran.
It ignores the `read_through` audit event (`devicelogs/voice_audit.py`), so a carry turn after a
spoken brief or mail line, a camera look, a grounded answer and an `ask_engine` or `ask_fleet` web
mark all reach the hourly memory job and the nightly review's `conversations`. CLAUDE.md section 7
says consolidation reads the person's words "and tool-free replies only, so the read-through rule
above holds by construction"; that holds only for the listed tools.

## Fix

Close the turn on a `read_through` event as `server/open_loops/words.py` does (decided at turn
close), and taint the rest of the session's assistant lines as the tool path already does. Tests with
a carry turn, a look and grounding. The head unit question (unmarked grounding) is its own call.
