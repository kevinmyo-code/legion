---
map: claude-session
ticket: "04"
title: "Build: the laptop worker (main session, helpers, report.py, install)"
type: build
status: kiv
status-detail: "Parked with the map (Kevin 2026-10-29: dont build it yet)."
blockers: ["02"]
blocked-by: ["[[02-spike-main-session]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Build: the worker

`tools/claude_session/`, Python 3.11+, stdlib only. Shape from the map, as ticket 02 settled it.

## Files

- `worker.py`: heartbeat (20 s), claim, keep-awake on mains (ruling 7), the main session process,
  cancel, file upload on request, back-off when the engine is unreachable.
- `report.py`: what helpers call to finish a task (`--task`, `--status`, `--summary`,
  `--question`, `--found PATH` repeatable). Writes to a local queue the worker drains.
- `main_session_prompt.md`: the main session's appended system prompt: you are the main session
  for a household assistant on <machine>; each `[task N]` message gets a background helper
  subagent so you stay free; helpers finish with `report.py`; summaries are for someone listening
  in a car, figures and file names in words; list found files, never upload them; the ask list
  (ruling 6): stop before the step and report `needs_confirmation` with one question; never claim
  an outcome you did not observe; scope (ruling 5) for this machine.
- The ask-list hook (ticket 02 question 9), installed into the worker's own settings, not Kevin's
  interactive `~/.claude/settings.json`.
- `install.ps1` (asks the spoken name and scope, writes config to `%APPDATA%\legion-claude\`,
  registers "LEGION Claude session" at logon, runs through lock), `uninstall.ps1`, `status.ps1`.
- `README.md`: install, device-token issue (exact endpoint from `server/household/`), the Windows
  lid-close setting, logs.
- `test_worker.py` (unittest): fake engine, fake `claude` emitting stream-json, two tasks at once,
  a follow-up, a cancel, a requested file, the 30 min stop, `CLAUDECODE` cleared.

## Rules

- No `ANTHROPIC_API_KEY`. The installed CLI on the subscription.
- Scope: Alienware `--add-dir` every fixed drive; work laptop only its configured repos root.
- Logs never contain request, summary or file paths.
- The main session rotates daily (new session id), and on restart resumes the last one.
