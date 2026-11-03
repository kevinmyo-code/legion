---
map: claude-session
ticket: "02"
title: "Spike: a long-lived headless main session with background helpers, through a lock"
type: build
status: in-progress
status-detail: "Kevin 2026-11-01: build. Q1-Q6, Q8 (settings), Q9 answered on the Alienware; Q7 lock run pending Kevin."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Spike: the main session

Ruling 9 wants one main session per machine that stays free while helpers work, like Kevin's chat
window here. Nothing in the repo has run Claude Code that way headless. Answer these on the
Alienware with a throwaway script in the scratchpad, before tickets 03 to 05 commit to a shape.

## Questions

1. Does `claude -p --input-format stream-json --output-format stream-json` stay alive across
   several user messages written to stdin, and with what lifetime limits?
2. Inside it, does a background subagent (Agent tool, run in background) keep running after the
   main turn ends, and does its completion arrive as a new event the worker can read? Can two run
   at once?
3. Can a helper run `python report.py --task N --status done --summary "..." [--file PATH]` through
   Bash with the allowed-tools set ruling 5 implies, and does the worker see it immediately?
4. Does `--resume <id>` bring the main session back after the worker restarts, with its tasks in
   memory?
5. Flags that exist on the installed CLI for: appending a system prompt, permission mode, allowed
   tools, extra directories, turn caps. Names verbatim from `claude --help`.
6. The CLI refuses to nest when `CLAUDECODE` is set; confirm the worker clears it.
7. Lock screen: a Task Scheduler task "at logon, run only when user is logged on" keeps running
   through Win+L, and the CLI's subscription login works from it.
8. Keep-awake: `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` from the worker
   while on mains power keeps the PC from sleeping when locked. Lid closed on mains is a Windows
   power setting; record which one Kevin must set.
9. How the ask list (ruling 6) is enforced best: a `PreToolUse` hook that denies matching Bash and
   file operations and makes the session return a question, or prompt only. Prefer the hook.

## Outcome

A short findings section in this ticket, and the chosen shape written into the map's "Shape on
each laptop". If question 2 fails, the fallback is one `claude -p` per task with `--resume` for
follow-ups, several processes at once.

## Findings (2026-11-01, Alienware, Claude Code 2.1.316)

Harness: throwaway `scratchpad/spike/harness.py` (not in repo). Flags used:
`claude -p --input-format stream-json --output-format stream-json --verbose --forward-subagent-text
--include-hook-events --permission-mode dontAsk --permission-prompts none --allowedTools ...
--append-system-prompt ... --session-id <uuid>` (or `--resume <uuid>`). Stdin line:
`{"type":"user","message":{"role":"user","content":"[task 41] ..."}}`.

1. **Yes.** One process took several stdin messages, one `result` event per turn, memory kept
   across them. No lifetime limit hit in a 2.5 min run; the process exits when stdin closes.
2. **Yes, best case.** Main turn calls Agent with `run_in_background`, gets "Async agent launched",
   replies one line and emits `result` in ~8 s. Two helpers ran at once (tasks 41, 42). With stdin
   idle, each completion arrived unprompted: `system/task_notification` (status `completed`), then
   a fresh main turn and its own `result` ("[task 42] Found 2 .py files"). Also emitted:
   `task_started`, `task_progress` (helper's current step, usable as live status),
   `task_updated`, `background_tasks_changed` (the running list). No nudge prompt needed.
3. **Yes.** Helpers ran `python report.py --task N ...`; the line hit `reports.jsonl` before the
   main turn's summary. Gotcha: on Windows helpers pick PowerShell as often as Bash, so the
   allowlist needs both `Bash(python *)` and `PowerShell(python *)`; with only Bash, a helper was
   denied once and retried.
4. **Yes.** A new process with `--resume <same uuid>` listed tasks 41 to 43 from the earlier run.
   Pin the id with `--session-id` on first start; no parsing needed.
5. Flags verbatim: `--append-system-prompt`, `--system-prompt`, `--permission-mode` (acceptEdits,
   auto, bypassPermissions, manual, dontAsk, plan), `--permission-prompts none`,
   `--allowedTools`, `--disallowedTools`, `--tools`, `--add-dir`, `--settings <file-or-json>`,
   `--setting-sources`, `--max-budget-usd` (API only, n/a), `--fallback-model`, `--model`,
   `--effort`, `--name`. **No turn cap flag** in 2.1.316: the 30 min cap (ruling 9) and cancel are the worker's
   job, done by message, not by kill (a kill stops every helper). Tested: with tasks 50 (90 s) and
   51 (20 s) running, stdin `[cancel 50] stop task 50's helper now (TaskStop)` made the main turn
   call `TaskStop`; `task_updated` status `killed` for 50 only, 51 finished and reported. Kill and
   `--resume` is the last resort when the main session itself hangs.
6. 2.1.316 does **not** refuse to nest with `CLAUDECODE=1`. The worker still strips `CLAUDECODE`
   and `CLAUDE_CODE_*` (a child launched from a session inherits its messaging socket and token).
7. Partial, pending. Lock run: `harness.py lock` sends a task every ~30 s for ~7 min with
   keep-awake set; Kevin presses Win+L during it. That proves a process in Kevin's logon session
   keeps reaching the API through a lock. It does not prove a Task Scheduler-launched `claude`
   authenticates (credential read from a scheduled task is the real risk); that half moves to
   ticket 07, or Kevin creates the task by hand (a system change, ruling 6).
8. Settings read on the Alienware (Balanced): sleep on AC = never (0), display off on AC = never.
   Sleep on battery = 180 s, irrelevant (worker only promises "on mains"). Standby is S0 Modern
   Standby, "Network Disconnected": if it ever enters standby the worker is off. Lid close is not
   exposed by `powercfg /q`; Kevin sets Control Panel > Power Options > "Choose what closing the
   lid does" > Plugged in: **Do nothing**. Worker still calls
   `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` while on AC as a guard.
9. **Hook works; use it.** A `PreToolUse` hook passed with `--settings` (matcher
   `Bash|PowerShell|Write|Edit`) fires inside background helpers too (`agent_id` in the hook
   input). Deny with `permissionDecision: deny` and a reason; the helper did not work around it,
   reported `needs_confirmation`, and the main turn said "Not deleted ... OK to delete
   work/victim.txt?". The file survived. Prompt alone is not needed. The spike's regex hook is
   spike-grade: its Write/Edit rule denies any write to an existing file, including files the
   helper made two steps earlier. Ticket 04's hook allows writes under the task's own work folder
   and asks only for Kevin's existing files. Gotcha: the helper put the
   question in `--summary`, not `--question`; the worker prompt must name the field.

Other things seen:
- User hooks and plugins load in the headless session (SessionStart hook, a `ui_status` plugin
  line). The worker should pass `--setting-sources` explicitly so Kevin's interactive hooks
  (ticket 06 reporter) do not report the worker's own main session as an interactive one.
- **Source of truth for status is `report.py`**, not main-turn text. The unprompted turn after a
  helper finishes is tagged `[task N]` only by prompt discipline; use it as Alfred's phrasing at
  most. `task_notification` carries `tool_use_id`, which the worker can map to the task whose
  Agent call it saw.
- Context grows with every task in one main session. `--autocompact` exists; the daily rotation
  (new `--session-id`) is the real reset; open tasks are re-sent into the new session.
- Each turn costs about 2 to 8 s of main-session time before it is free again.
- **Alienware mode, recommended:** `dontAsk` + a broad explicit allowlist (`Bash PowerShell Read
  Write Edit Glob Grep Agent TaskStop WebFetch WebSearch`) + the ask-list hook, `--add-dir` for
  each fixed drive. That is the mode tested here and the allowlist is a second guard. Kevin to
  confirm. Untested and not needed: the hook under `--permission-mode bypassPermissions`. My test run was blocked by this session's auto-mode
  classifier; Kevin runs it by hand (`python harness.py hook --mode bypassPermissions --settings
  <abs path>/hook-settings.json`). Docs say PreToolUse deny applies in every mode; confirm.
- Untested: what a helper does when the process exits mid-task (worker restart). Assume it dies;
  the worker marks running tasks `failed` ("worker restarted") on start, or re-sends them.

## Chosen shape

Question 2 passed, so the map's shape stands: one long-lived main session per machine with
background helpers, no per-task process fallback. See the map's "Shape on each laptop".
