---
map: clerk
ticket: "03"
title: "Build: the laptop worker (poller, claude -p runner, result upload, install)"
type: build
status: kiv
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: the laptop worker

`tools/clerk/` in the repo, Python 3.11+, stdlib only (urllib, json, subprocess, logging). Branch
`feat/clerk-worker` off `dev`. Built against the API table in the map; until ticket 02 is deployed
it is tested against a fake engine (a tiny `http.server` in the test file).

## Files

- `tools/clerk/clerk_worker.py`: the loop. `tools/clerk/README.md`: install and operate.
- `tools/clerk/install_clerk.ps1`: writes the config, registers a Task Scheduler task "LEGION
  clerk" at logon (hidden window, restart on failure, no battery restriction), starts it.
  `uninstall_clerk.ps1`. `clerk_status.ps1` tails the log.
- `tools/clerk/test_clerk_worker.py` (unittest, stdlib): fake engine, fake `claude` executable
  (a Python script on PATH that prints a canned `--output-format json` result), the whole loop
  once.

## Behaviour

1. Config at `%APPDATA%\legion-clerk\config.json`: `engine_url`, `device_token`, `machine`
   (default hostname), `tasks_root` (default `%USERPROFILE%\legion-clerk\tasks`), `claude_exe`
   (default `claude` on PATH), `max_turns` 60, `task_timeout_s` 1800, `poll_s` 5, `heartbeat_s` 20.
   The token is the same device-token mechanism the phone uses (`Authorization: Token <key>`);
   the README says how Kevin issues one (read `server/household/` for the issue endpoint and name
   it exactly).
2. Loop: heartbeat every 20 s (state idle or busy with the task id). Claim every 5 s when idle.
   On a task: create `tasks_root\<id>\` with `in\` (`request.md`, task metadata JSON) and `out\`.
   Run the CLI from inside that folder.
3. The CLI call, exactly, and the README repeats it:
   `claude -p <prompt> --output-format json --permission-mode acceptEdits --allowedTools Read Write Edit Glob Grep Bash WebSearch WebFetch --max-turns <n> --add-dir <task dir>`
   with `cwd = task dir`. For `kind == followup`: `--resume <parent session_id>` and the follow-up
   text as the prompt. Capture `session_id`, `num_turns`, `duration_ms`, `result` from the JSON
   output; a timeout kills the process tree and finishes the task `failed` with "timed out after N".
   The CLI refuses to start nested inside another Claude Code session (`CLAUDECODE` env var); the
   worker clears that variable in the child's environment and the test asserts it.
4. The prompt: the task request, then a fixed preamble kept in `CLERK_PREAMBLE` in the worker:
   you are a worker for a household assistant; work only inside this folder; write deliverables to
   `out\`; finish with a short plain-text summary for someone listening in a car, figures and file
   names in words; **never book, pay, send, submit, post, message or delete anything outside this
   folder; if the task needs that, stop before the step and end your answer with a line
   `NEEDS_CONFIRMATION: <one question>`**; never claim an outcome you did not observe. The worker
   parses that trailing line into `status=needs_confirmation`, `question`.
   `--append-system-prompt` is the right flag if the CLI has it (check `claude --help`); otherwise
   the preamble heads the prompt.
5. Finish: multipart to `tasks/{id}/finish/` with every file in `out\` (skip over 25 MB, say so in
   the summary). Keep the task folder; a `prune` subcommand deletes folders older than 30 days.
   Nothing from `in\` and no transcript is uploaded.
6. Cancel: the heartbeat response lists `cancel_requested`; if it names the running task, kill the
   CLI and finish `failed` with "cancelled".
7. Logs: `%APPDATA%\legion-clerk\logs\clerk.log`, rotating 5 x 2 MB, one line per state change,
   never the request or summary text (they are household data; the engine has them).
8. Offline engine: back off to 60 s, keep trying, never crash. The Task Scheduler restart covers
   the rest.

## Verification

- `python -m unittest tools/clerk/test_clerk_worker.py` green.
- `python -I tools/clerk/clerk_worker.py --once --dry-run` against the fake engine prints the CLI
  command it would run, with the preamble, and exits 0.
- A real `claude -p` smoke test on this laptop with a trivial prompt ("write hello.txt in out")
  from a worker-created folder, run from a plain shell (not from inside a Claude Code session),
  JSON parsed, `session_id` captured. If the environment you build in is itself a Claude Code
  session, run it with the `CLAUDECODE` variable cleared and say whether it worked.
- Install script dry-run (`-WhatIf`) prints the scheduled task it would register. Do not register
  it; that is ticket 05, Kevin's.
