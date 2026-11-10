---
map: open-loops
ticket: "07"
title: "Consolidation and the review ignore read_through marks"
type: build
status: resolved
status-detail: "fixed 2026-11-10 on integrate/2026-11-10-all (engine filter + tests)"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
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

## Resolution (2026-11-10, integrate/2026-11-10-all)

- `consolidate.transcript()` drops the whole turn on a `read_through` event or a redacted `text` /
  tool `response`, taints the rest of the session's replies, closes a turn on `session_start`.
- `READ_THROUGH_TOOLS` gains the engine's `THIRD_PARTY_TOOLS` (`brief_latest`, `github_status`).
- Head unit (its own call, built default, Kevin may veto): `log_transcript` keeps only his own
  words from a source `open_loops.words.can_mark_turns` refuses. Consolidation and the nightly
  review both read through it.
- Verification: `tests/test_member_memory_consolidate.py` look, carry, grounding, proactive,
  fleet_web, redaction, GitHub, head unit, review cases: done.
- Residual: a web `run_procedure` (e.g. a briefing that calls `brief_latest`) drops its own turn's
  reply but does not taint the session; a later tool-free reply quoting it can still reach the
  model. Not built.
