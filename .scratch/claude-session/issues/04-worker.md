---
map: claude-session
ticket: "04"
title: "Build: the laptop worker (main session, helpers, report.py, install)"
type: build
status: built
status-detail: "Built 2026-11-01 on feat/claude-session-worker: suite green against a fake engine and fake claude. Owes: install.ps1 run by Kevin, a live run against the real CLI and the ticket 03 engine (ticket 07)."
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

- `send_mail.py`: what a helper calls to send, only after the task's `needs_confirmation` came
  back yes (ruling 14): from Alfred's Gmail over SMTP with the app password in the worker config,
  `--to`, `--subject`, `--body`, `--attach` repeatable, Kevin always cc'd. Refuses without a
  confirmation token the worker issues for that task. The send's success or failure goes into the
  task's summary in words.

## Rules

- No `ANTHROPIC_API_KEY`. The installed CLI on the subscription.
- Scope: Alienware `--add-dir` every fixed drive; work laptop only its configured repos root.
- Logs never contain request, summary or file paths.
- The main session rotates daily (new session id), and on restart resumes the last one.

## Addition (Kevin 2026-11-01: "yes to status extras")

- Heartbeat carries `progress: {task_id: "<latest task_progress description>"}` for running tasks,
  from the stream's `system/task_progress` events (mapped to the task via the Agent `tool_use_id`).
  Description text only, capped at 120 chars; never logged locally.

## Built (2026-11-01)

`tools/claude_session/`, see its README. Contract choices ticket 03 must match or overrule:

- **Cancel:** the worker posts `finish` with `status: failed`, summary "Cancelled before it
  finished, as asked." (the API has no `cancelled` finish status). Engine may map
  `cancel_requested` + `failed` to `cancelled`.
- **Confirmation yes:** arrives as a new task with `parent` set to the `needs_confirmation` task
  (no confirm endpoint). The worker issues a one-task token on any such follow-up; the main
  session judges whether Kevin's words are a yes. An explicit `confirmed: true` on the claimed
  task would be stronger.
- **Claim payload read:** `id`, `request`, `parent`, `deliver`. A dict `{tasks: [...]}` is also
  accepted.
- **Heartbeat body adds** `progress: {"<task id>": "<step>"}`.
- **Upload:** multipart fields `file_id` and `file`. Refused (logged only, nothing told to the
  engine) when the path is not one that task listed, the file is gone, over 25 MB, or the machine
  is `listed`.
- **Work laptop:** `found` always sent empty; no WebFetch/WebSearch; shell limited to read-only
  git, listing/reading commands and report.py by the hook.
- Tasks running when the worker restarts finish `failed` ("restarted"); not re-sent.
